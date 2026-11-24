package com.limelight.local.plugin

import com.limelight.local.media.ItemNotFoundException
import com.limelight.local.media.PluginInternalException
import com.limelight.local.media.SourceAuthFailedException
import com.limelight.local.media.SourceForbiddenException
import com.limelight.local.media.SourceNeedsAuthException
import com.limelight.local.media.SourceNeedsSetupException
import com.limelight.local.media.SourceRateLimitedException
import com.limelight.local.media.SourceUnavailableException
import com.limelight.local.media.SourceUnsupportedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Executes real QuickJS (via `quickjs-kt-jvm`) to verify:
 * - Every `ctx` API (async storage & cookies, crypto, lazy DOM, log redaction, i18n, sealed `__d3_host`)
 * - B2: 8 MB binary fetch via ArrayBuffer inside 64 MB heap + 4-concurrent & 16 MB in-flight caps
 * - S3: true `clearTimeout` cancellation, `Promise.race([slowFetch, timeout])`, and OkHttp call abort
 * - S4: 450 KB+ HTML lazy `querySelectorAll('div')` + 8-doc / 4 MB caps
 * - S5: 3,000 cookie sets coalesced to 1 disk write + 256 KB cap
 * - S7: `SourceNeedsAuthException` detached from `SourceNeedsSetupException`
 * - Small #4: corrupted storage/cookie file preserved untouched on disk
 * - Small #6: safe `PluginRuntime.close()` while busy
 * - Timeout & OOM recovery
 */
class CtxApiAndRecoveryTest {

    @Test
    fun exercisesEveryCtxApiAndHostSealingInsideRealQuickJs() = runBlocking {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val pubPem = "-----BEGIN PUBLIC KEY-----\n" +
            PluginCrypto.base64Encode(kp.public.encoded) +
            "\n-----END PUBLIC KEY-----"

        val tmpDir = Files.createTempDirectory("d3-ctx-test").toFile()
        val secretBox = SoftwareAesSecretBox()
        val storageFile = File(tmpDir, "storage.enc")
        val cookieFile = File(tmpDir, "cookies.enc")
        val storage = InstanceStorage(storageFile, secretBox)
        val cookieJar = PluginCookieJar(cookieFile, secretBox)

        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.ctxtest",
            name = "\$t:title",
            version = "1.0.0",
            author = "depth3d",
            permissions = PluginPermissions(hosts = listOf("api.example.com")),
            config = listOf(ConfigField("pass", ConfigFieldType.PASSWORD, "密码")),
        )
        val locale = PluginLocale.fromMap(
            mapOf("zh-CN" to """{"title":"接口测试","hello":"你好，{{name}}"}"""),
            "zh-CN",
        )
        assertEquals("接口测试", locale.localizeManifest(manifest).name)

        val loggedLines = ArrayList<String>()
        val logger = PluginLogger(
            pluginId = manifest.id,
            instanceId = "inst-1",
            knownSecretsProvider = { listOf("TopSecretPass99!") },
            sink = { _, line -> loggedLines += line },
        )

        val mainJs = """
            export default {
              async home(ctx) {
                let configFrozen = false;
                try { ctx.config.pass = 'hacked'; } catch (e) { configFrozen = true; }

                // Small #1: Even if __d3_host is non-configurable on globalThis, calling it directly fails
                let hostSealed = false;
                if (typeof globalThis.__d3_host === 'undefined') {
                  hostSealed = true;
                } else {
                  try {
                    globalThis.__d3_host.getConfigJson('forged-token');
                  } catch (e) {
                    hostSealed = true;
                  }
                }

                // §11.1: storage & cookies MUST return Promises
                const sp = ctx.storage.get('nonexistent');
                const cp = ctx.cookies.get('https://api.example.com/');
                const apisAreAsync = (sp instanceof Promise) && (cp instanceof Promise);
                await sp;
                await cp;

                // Storage round-trip + quota
                await ctx.storage.set('token', { value: 'tok_secret_value', n: 7 });
                const stored = await ctx.storage.get('token');
                const keys = await ctx.storage.keys();
                await ctx.storage.remove('token');
                await ctx.storage.set('secretPersist', 'plain-should-be-encrypted');
                let quotaHit = false;
                try { await ctx.storage.set('huge', 'x'.repeat(1100 * 1024)); } catch (e) { quotaHit = true; }

                // Cookies
                await ctx.cookies.set('https://api.example.com/', 'sess=abc999; Path=/; Secure');
                const cookieStr = await ctx.cookies.get('https://api.example.com/v1');

                // Crypto
                const md5 = ctx.crypto.md5('hello');
                const sha1 = ctx.crypto.sha1('hello');
                const sha256 = ctx.crypto.sha256('hello');
                const hmac = ctx.crypto.hmac('sha256', 'k', 'm');
                const key = new Uint8Array(16).fill(4);
                const iv16 = new Uint8Array(16).fill(2);
                const iv12 = new Uint8Array(12).fill(9);
                const cbcCt = ctx.crypto.aes.encrypt('CBC', key, iv16, 'hello-cbc');
                const cbcPt = new TextDecoder().decode(ctx.crypto.aes.decrypt('CBC', key, iv16, cbcCt));
                const gcmCt = ctx.crypto.aes.encrypt({ mode: 'GCM', key, iv: iv12, data: 'hello-gcm', aad: 'meta' });
                const gcmPt = new TextDecoder().decode(ctx.crypto.aes.decrypt({ mode: 'GCM', key, iv: iv12, data: gcmCt, aad: 'meta' }));
                const rsaB64 = ctx.crypto.base64.encode(ctx.crypto.rsa.encrypt(ctx.config.pubPem, 'rsa-ok', 'OAEP-SHA256'));
                const randLen = ctx.crypto.randomBytes(24).length;
                const b64Url = ctx.crypto.base64.encode('a+b/c', true);
                const hex = ctx.crypto.hex.encode('AB');
                const hexBack = new TextDecoder().decode(ctx.crypto.hex.decode(hex));

                // Parse HTML & XML (lazy handles)
                const html = ctx.parse.html('<section><a class="link" href="/m/1">星际</a><a class="link" href="/m/2">沙丘</a></section>');
                const links = html.querySelectorAll('a.link').map((el) => el.attr('href') + ':' + el.text);
                const xml = ctx.parse.xml('<feed><entry id="e1"><name>第一集</name></entry></feed>');
                const xmlName = xml.querySelector('entry name').text;

                // i18n
                const greeting = ctx.t('hello', { name: '世界' });

                // Single-call setTimeout
                const timerRes = await new Promise((resolve) => setTimeout(() => resolve('fired'), 10));

                // Log redaction
                ctx.log.info('auth password=' + ctx.config.pass + ' Authorization: Bearer leakToken777 https://bob:myPw@host/x');

                await ctx.storage.set('out', {
                  configFrozen, hostSealed, apisAreAsync,
                  instanceId: ctx.instanceId, deviceModel: ctx.device.model,
                  stored, keys, quotaHit, cookieStr,
                  md5, sha1, sha256, hmac, cbcPt, gcmPt, rsaB64, randLen, b64Url, hex, hexBack,
                  links, xmlName, greeting, timerRes,
                });
                return [];
              },
              browse() { return { items: [] }; },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
            };
        """.trimIndent()

        val policy = NetworkPolicy(manifest.permissions)
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-1",
            mainJsSource = mainJs,
            config = mapOf(
                "pass" to JsonPrimitive("TopSecretPass99!"),
                "pubPem" to JsonPrimitive(pubPem),
            ),
            fetchGateway = PluginFetchGateway(policy, cookieJar),
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
            locale = locale,
            deviceInfo = DeviceInfo(model = "PICO 4"),
        )

        try {
            assertEquals(64L * 1024 * 1024, PluginRuntime.DEFAULT_MEMORY_LIMIT_BYTES)
            runtime.invoke("home")

            val rawDisk = String(storageFile.readBytes(), Charsets.ISO_8859_1)
            assertFalse(rawDisk.contains("plain-should-be-encrypted"))

            val reloaded = InstanceStorage(storageFile, secretBox)
            assertEquals("plain-should-be-encrypted", reloaded.get("secretPersist")?.jsonPrimitive?.content)

            val out = storage.get("out")!!.jsonObject
            assertTrue(out["configFrozen"]!!.jsonPrimitive.boolean)
            assertTrue("Host bridge must be sealed even if non-configurable", out["hostSealed"]!!.jsonPrimitive.boolean)
            assertTrue("storage/cookies must return Promises", out["apisAreAsync"]!!.jsonPrimitive.boolean)
            assertEquals("inst-1", out["instanceId"]!!.jsonPrimitive.content)
            assertEquals("PICO 4", out["deviceModel"]!!.jsonPrimitive.content)
            assertEquals("tok_secret_value", out["stored"]!!.jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals(listOf("token"), out["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(out["quotaHit"]!!.jsonPrimitive.boolean)
            assertEquals("sess=abc999", out["cookieStr"]!!.jsonPrimitive.content)

            assertEquals("5d41402abc4b2a76b9719d911017c592", out["md5"]!!.jsonPrimitive.content)
            assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", out["sha1"]!!.jsonPrimitive.content)
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                out["sha256"]!!.jsonPrimitive.content,
            )
            assertEquals(
                PluginCrypto.hmac("sha256", "k".toByteArray(), "m".toByteArray()),
                out["hmac"]!!.jsonPrimitive.content,
            )
            assertEquals("hello-cbc", out["cbcPt"]!!.jsonPrimitive.content)
            assertEquals("hello-gcm", out["gcmPt"]!!.jsonPrimitive.content)
            assertEquals(24, out["randLen"]!!.jsonPrimitive.int)
            assertEquals("4142", out["hex"]!!.jsonPrimitive.content)
            assertEquals("AB", out["hexBack"]!!.jsonPrimitive.content)

            val rsaBytes = PluginCrypto.base64Decode(out["rsaB64"]!!.jsonPrimitive.content)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                kp.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            assertEquals("rsa-ok", String(cipher.doFinal(rsaBytes), Charsets.UTF_8))

            assertEquals(
                listOf("/m/1:星际", "/m/2:沙丘"),
                out["links"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("第一集", out["xmlName"]!!.jsonPrimitive.content)
            assertEquals("你好，世界", out["greeting"]!!.jsonPrimitive.content)
            assertEquals("fired", out["timerRes"]!!.jsonPrimitive.content)

            assertTrue(loggedLines.any { it.startsWith("depth3d: plugin dev.depth3d.ctxtest") })
            for (line in loggedLines) {
                assertFalse("Password leaked in log: $line", line.contains("TopSecretPass99!"))
                assertFalse("Bearer token leaked in log: $line", line.contains("leakToken777"))
                assertFalse("URL password leaked in log: $line", line.contains("myPw"))
            }
        } finally {
            runtime.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun b2Handles8MbBinaryFetchViaArrayBufferAndBoundsConcurrentFetchesAndInFlightMemory() = runBlocking {
        val eightMb = ByteArray(8 * 1024 * 1024) { 0x5A }
        val over8Mb = ByteArray(8 * 1024 * 1024 + 4096) { 0x11 }
        val oneMb = ByteArray(1024 * 1024) { 0x22 }
        val activeNow = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/8mb" -> MockResponse().setResponseCode(200).setBody(Buffer().write(eightMb))
                    "/over8mb" -> MockResponse().setResponseCode(200).setBody(Buffer().write(over8Mb))
                    else -> {
                        val cur = activeNow.incrementAndGet()
                        maxObservedConcurrency.updateAndGet { prev -> maxOf(prev, cur) }
                        try {
                            Thread.sleep(25)
                        } finally {
                            activeNow.decrementAndGet()
                        }
                        MockResponse().setResponseCode(200).setBody(Buffer().write(oneMb))
                    }
                }
            }
        }
        server.start()

        val tmpDir = Files.createTempDirectory("d3-b2-test").toFile()
        val secretBox = SoftwareAesSecretBox()
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.b2mem",
            name = "B2Mem",
            version = "1.0.0",
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
            config = listOf(ConfigField("server", ConfigFieldType.URL, "Server")),
        )
        val baseUrl = "http://192.168.1.50:${server.port}"
        val mainJs = """
            export default {
              async home(ctx) {
                // 1. 8 MB response succeeds inside 64 MB QuickJS heap via direct binary transfer (no base64 blowup)
                const r = await ctx.fetch(ctx.config.server + '/8mb');
                const ab = r.bytes();
                if (!(ab instanceof ArrayBuffer)) throw new Error('bytes() must return ArrayBuffer');
                const u8 = new Uint8Array(ab);
                if (ab.byteLength !== 8 * 1024 * 1024 || u8[0] !== 0x5A || u8[u8.length - 1] !== 0x5A) {
                  throw new Error('8 MB payload mismatch: ' + ab.byteLength);
                }

                // 2. > 8 MB response MUST be rejected
                let overRejected = false;
                try {
                  await ctx.fetch(ctx.config.server + '/over8mb');
                } catch (e) {
                  overRejected = true;
                }
                return [{ id: 'ok', title: ab.byteLength + '|' + overRejected, items: [] }];
              },
              async browse(ctx) {
                // 3. 12 concurrent 1 MB fetches: concurrency must stay <= 4 & host in-flight budget <= 16 MB
                const ps = [];
                for (let i = 0; i < 12; i++) {
                  ps.push(ctx.fetch(ctx.config.server + '/chunk/' + i).then((r) => r.bytes().byteLength));
                }
                const sizes = await Promise.all(ps);
                return { items: [{ id: 'c', kind: 'video', title: String(sizes.reduce((a, b) => a + b, 0)) }] };
              },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
            };
        """.trimIndent()

        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = setOf("192.168.1.50"),
            dnsResolver = { listOf(InetAddress.getByName("192.168.1.50")) },
        )
        val okClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val gw = PluginFetchGateway(policy, cookieJar, baseClient = okClient)
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-b2",
            mainJsSource = mainJs,
            config = mapOf("server" to JsonPrimitive(baseUrl)),
            fetchGateway = gw,
            storage = storage,
            cookieJar = cookieJar,
            logger = PluginLogger(manifest.id, "inst-b2", sink = { _, _ -> }),
        )

        try {
            val h = runtime.invoke("home")
            assertEquals("${8 * 1024 * 1024}|true", h.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals("In-flight byte budget must be released after call", 0L, gw.inFlightBudget.currentUsed())

            val b = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive(""))))
            assertEquals("${12 * 1024 * 1024}", b.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertTrue(
                "Max concurrent fetches must be capped at 4, saw ${maxObservedConcurrency.get()}",
                maxObservedConcurrency.get() <= 4,
            )
            assertEquals(0L, gw.inFlightBudget.currentUsed())
        } finally {
            runtime.shutdown()
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun s3S4S5CancelTimersAndFetchesLazilyParseLargeHtmlAndCoalesce3000Cookies() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("late").setHeadersDelay(4, TimeUnit.SECONDS)
        }
        server.start()

        val tmpDir = Files.createTempDirectory("d3-s345-test").toFile()
        val secretBox = SoftwareAesSecretBox()
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieFile = File(tmpDir, "cookies.enc")
        val cookieJar = PluginCookieJar(cookieFile, secretBox)

        // S4: > 450 KB HTML with 4,200 nested <div> elements (would OOM eager serialization)
        val bigHtml = buildString(480_000) {
            append("<html><body>")
            for (i in 0 until 4200) {
                append("<div class=\"row\" data-idx=\"").append(i).append("\"><span>item-").append(i)
                append("-").append("x".repeat(60)).append("</span></div>")
            }
            append("</body></html>")
        }
        assertTrue(bigHtml.toByteArray(Charsets.UTF_8).size > 420 * 1024)

        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.s345",
            name = "S345",
            version = "1.0.0",
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
            config = listOf(
                ConfigField("server", ConfigFieldType.URL, "Server"),
                ConfigField("bigHtml", ConfigFieldType.TEXT, "HTML"),
            ),
        )
        val baseUrl = "http://192.168.1.50:${server.port}"
        val mainJs = """
            export default {
              async home(ctx) {
                // S3a: clearTimeout MUST cancel host-side wait immediately (not hang 2500ms)
                const t = setTimeout(() => {}, 2500);
                clearTimeout(t);

                // S3b: Promise.race([slowFetch, shortTimeout]) settles fast & cancels OkHttp call
                const winner = await Promise.race([
                  ctx.fetch(ctx.config.server + '/slow').then(() => 'fetch'),
                  new Promise((resolve) => setTimeout(() => resolve('timeout'), 50)),
                ]);
                return [{ id: 's3', title: winner, items: [] }];
              },
              async browse(ctx, { categoryId }) {
                if (categoryId === 'dom') {
                  const doc = ctx.parse.html(ctx.config.bigHtml);
                  const divs = doc.querySelectorAll('div.row');
                  const first = divs[0].attr('data-idx') + ':' + divs[0].querySelector('span').text.slice(0, 6);
                  const last = divs[divs.length - 1].attr('data-idx');
                  // 9th doc in same call MUST fail (§11.1: max 8 docs per call)
                  let ninthFailed = false;
                  try {
                    for (let i = 0; i < 8; i++) ctx.parse.html('<p>x</p>');
                  } catch (e) { ninthFailed = true; }
                  return { items: [{ id: 'dom', kind: 'video', title: divs.length + '|' + first + '|' + last + '|' + ninthFailed }] };
                }
                if (categoryId === 'cookies') {
                  for (let i = 0; i < 3000; i++) {
                    await ctx.cookies.set('https://api.example.com/', 'c' + (i % 500) + '=v' + i + '; Path=/; Secure');
                  }
                  let quotaHit = false;
                  try {
                    for (let i = 0; i < 200; i++) {
                      await ctx.cookies.set('https://api.example.com/', 'big' + i + '=' + 'A'.repeat(2048) + '; Path=/; Secure');
                    }
                  } catch (e) { quotaHit = true; }
                  return { items: [{ id: 'ck', kind: 'video', title: 'quota-' + quotaHit }] };
                }
                return { items: [] };
              },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
            };
        """.trimIndent()

        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = setOf("192.168.1.50"),
            dnsResolver = { listOf(InetAddress.getByName("192.168.1.50")) },
        )
        val okClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val gw = PluginFetchGateway(policy, cookieJar, baseClient = okClient)
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-s345",
            mainJsSource = mainJs,
            config = mapOf(
                "server" to JsonPrimitive(baseUrl),
                "bigHtml" to JsonPrimitive(bigHtml),
            ),
            fetchGateway = gw,
            storage = storage,
            cookieJar = cookieJar,
            logger = PluginLogger(manifest.id, "inst-s345", sink = { _, _ -> }),
        )

        try {
            // S3 verification
            val t0 = System.currentTimeMillis()
            val s3Res = runtime.invoke("home")
            val elapsed = System.currentTimeMillis() - t0
            assertEquals("timeout", s3Res.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertTrue("clearTimeout + Promise.race should settle quickly (<1000ms), took ${elapsed}ms", elapsed < 1000L)
            assertEquals("In-flight OkHttp calls must be cancelled on call settle", 0, gw.activeCallCount())

            // S4 verification
            val domRes = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("dom"))))
            assertEquals(
                "4200|0:item-0|4199|true",
                domRes.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content,
            )

            // S5 verification
            val ckT0 = System.currentTimeMillis()
            val ckRes = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("cookies"))))
            val ckElapsed = System.currentTimeMillis() - ckT0
            assertEquals(
                "quota-true",
                ckRes.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content,
            )
            assertTrue("3000 cookie sets must complete rapidly (<8000ms), took ${ckElapsed}ms", ckElapsed < 8000L)
            assertEquals("3000 cookie sets must coalesce to 1 disk write", 1, cookieJar.diskWriteCount)
        } finally {
            runtime.shutdown()
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun corruptedStorageAndCookiesPreserveOriginalFilesAndReportError() = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-corrupt-test").toFile()
        val secretBox = SoftwareAesSecretBox()
        val storageFile = File(tmpDir, "storage.enc")
        val cookieFile = File(tmpDir, "cookies.enc")
        val garbage = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 1, 2, 3, 4)
        storageFile.writeBytes(garbage)
        cookieFile.writeBytes(garbage)

        try {
            // Small #4: Storage decryption failure throws StorageCorruptedException & preserves original bytes untouched
            val storage = InstanceStorage(storageFile, secretBox)
            try {
                storage.get("token")
                fail("Expected StorageCorruptedException on corrupted storage.enc")
            } catch (_: StorageCorruptedException) {
            }
            assertArrayEquals("Original corrupted storage.enc must NOT be overwritten", garbage, storageFile.readBytes())

            val jar = PluginCookieJar(cookieFile, secretBox)
            try {
                jar.getHeaderForUrl("https://api.example.com/")
                fail("Expected StorageCorruptedException on corrupted cookies.enc")
            } catch (_: StorageCorruptedException) {
            }
            assertArrayEquals("Original corrupted cookies.enc must NOT be overwritten", garbage, cookieFile.readBytes())

            // Inside PluginRuntime, StorageCorruptedException maps cleanly to SourceNeedsAuthException
            val manifest = PluginManifest(api = 1, id = "dev.depth3d.corrupt", name = "Corrupt", version = "1.0.0")
            val mainJs = """
                export default {
                  async home(ctx) { await ctx.storage.get('token'); return []; },
                  browse() { return { items: [] }; },
                  detail() { return { id: '1', kind: 'video', title: '1' }; },
                  playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
                };
            """.trimIndent()
            val runtime = PluginRuntime(
                manifest = manifest,
                instanceId = "inst-c",
                mainJsSource = mainJs,
                config = emptyMap(),
                fetchGateway = PluginFetchGateway(NetworkPolicy(manifest.permissions), PluginCookieJar(File(tmpDir, "c2.enc"), secretBox)),
                storage = storage,
                cookieJar = PluginCookieJar(File(tmpDir, "c2.enc"), secretBox),
                logger = PluginLogger(manifest.id, "inst-c", sink = { _, _ -> }),
            )
            try {
                runtime.invoke("home")
                fail("Expected SourceNeedsAuthException when storage.enc cannot be decrypted")
            } catch (_: SourceNeedsAuthException) {
            } finally {
                runtime.close()
            }
            assertArrayEquals(garbage, storageFile.readBytes())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun mapsAllNineProtocolErrorCodesAndDetachesNeedsAuthFromNeedsSetupS7() {
        val cases = listOf(
            PluginErrorCode.NEEDS_AUTH to SourceNeedsAuthException::class.java,
            PluginErrorCode.AUTH_FAILED to SourceAuthFailedException::class.java,
            PluginErrorCode.NEEDS_SETUP to SourceNeedsSetupException::class.java,
            PluginErrorCode.UNAVAILABLE to SourceUnavailableException::class.java,
            PluginErrorCode.NOT_FOUND to ItemNotFoundException::class.java,
            PluginErrorCode.FORBIDDEN to SourceForbiddenException::class.java,
            PluginErrorCode.RATE_LIMITED to SourceRateLimitedException::class.java,
            PluginErrorCode.UNSUPPORTED to SourceUnsupportedException::class.java,
            PluginErrorCode.INTERNAL to PluginInternalException::class.java,
        )
        for ((code, expectedClass) in cases) {
            val ex = PluginErrorMapper.toException(
                PluginErrorPayload(code, message = null, retryAfterSec = if (code == PluginErrorCode.RATE_LIMITED) 15 else null),
                itemId = "item-1",
            )
            assertTrue("Expected $expectedClass for $code, got ${ex.javaClass}", expectedClass.isInstance(ex))
            val msg = ex.message.orEmpty()
            assertTrue("Default message must be non-empty Chinese: $msg", msg.any { it in '\u4e00'..'\u9fff' })
            assertFalse("Default message must not leak exception class name: $msg", msg.contains("Exception"))
            if (ex is SourceRateLimitedException) {
                assertEquals(15, ex.retryAfterSec)
                assertTrue(msg.contains("15"))
            }
        }
        // S7: SourceNeedsAuthException MUST NOT inherit from SourceNeedsSetupException
        val authEx = PluginErrorMapper.toException(PluginErrorPayload(PluginErrorCode.NEEDS_AUTH))
        assertFalse(
            "S7: SourceNeedsAuthException must NOT be a subtype of SourceNeedsSetupException",
            authEx is SourceNeedsSetupException,
        )
    }

    @Test
    fun recoversCleanlyAfterTimeoutOomAndSafeCloseWhileBusy(): Unit = runBlocking {
        val tmpDir = Files.createTempDirectory("d3-recovery-test").toFile()
        val secretBox = SoftwareAesSecretBox()
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.recovery",
            name = "Recovery",
            version = "1.0.0",
            author = "depth3d",
        )
        val mainJs = """
            export default {
              async init(ctx) {
                const n = ((await ctx.storage.get('initCount')) || 0) + 1;
                await ctx.storage.set('initCount', n);
              },
              async dispose(ctx) {
                await ctx.storage.set('disposed', true);
              },
              home() { return []; },
              async browse(ctx, { categoryId }) {
                if (categoryId === 'hang') {
                  while (true) {}
                }
                if (categoryId === 'oom') {
                  const buf = new Uint8Array(6 * 1024 * 1024);
                  return { items: [{ id: String(buf.length), kind: 'video', title: 'never' }] };
                }
                return { items: [{ id: 'ok', kind: 'video', title: 'run-' + (await ctx.storage.get('initCount')) }] };
              },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/1.mp4' }; },
            };
        """.trimIndent()

        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-rec",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = PluginFetchGateway(NetworkPolicy(manifest.permissions), cookieJar),
            storage = storage,
            cookieJar = cookieJar,
            logger = PluginLogger(manifest.id, "inst-rec", sink = { _, _ -> }),
            memoryLimitBytes = 4L * 1024 * 1024,
            defaultTimeoutMs = 300L,
        )

        try {
            val r1 = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("ok"))))
            assertEquals("run-1", r1.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(1, runtime.creationCount)

            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("hang"))))
                fail("Expected timeout to throw SourceUnavailableException")
            } catch (_: SourceUnavailableException) {
            }

            val r2 = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("ok"))))
            assertEquals("run-2", r2.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(2, runtime.creationCount)

            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("oom"))))
                fail("Expected OOM to throw PluginInternalException")
            } catch (_: PluginInternalException) {
            }

            val r3 = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("ok"))))
            assertEquals("run-3", r3.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(3, runtime.creationCount)

            runtime.shutdown()
            assertEquals(true, storage.get("disposed")?.jsonPrimitive?.boolean)

            // Small #6: Calling close() on a runtime while an infinite loop is active interrupts cleanly without crash
            val busyRuntime = PluginRuntime(
                manifest = manifest,
                instanceId = "inst-busy-close",
                mainJsSource = mainJs,
                config = emptyMap(),
                fetchGateway = PluginFetchGateway(NetworkPolicy(manifest.permissions), cookieJar),
                storage = InstanceStorage(File(tmpDir, "s2.enc"), secretBox),
                cookieJar = cookieJar,
                logger = PluginLogger(manifest.id, "inst-busy-close", sink = { _, _ -> }),
                defaultTimeoutMs = 5_000L,
            )
            val job = async(Dispatchers.Default) {
                runCatching {
                    busyRuntime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("hang"))))
                }
            }
            delay(80)
            busyRuntime.close()
            job.await()
        } finally {
            runtime.close()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun passesAllSharedDomVectorsAndEnforcesHostMemoryCapsParityAndImmutableGlobals(): Unit = runBlocking {
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val domVectorsRaw = File(repoRoot, "docs/specs/plugin-schema/dom-vectors.json").readText()
        val domVectors = kotlinx.serialization.json.Json.parseToJsonElement(domVectorsRaw).jsonObject["cases"]!!.jsonArray
        assertTrue(domVectors.size >= 4)

        val tmpDir = Files.createTempDirectory("d3-round3-ctx").toFile()
        val secretBox = SoftwareAesSecretBox()
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = PluginLogger(
            pluginId = "dev.depth3d.r3",
            instanceId = "inst-r3",
            sink = { level, line -> if (level == PluginLogLevel.WARN) warnings += line },
        )
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(
            file = File(tmpDir, "cookies.enc"),
            secretBox = secretBox,
            clockMs = { 1_700_000_000_000L },
            onWarning = { msg -> logger.warn(msg) },
        )

        // MockWebServer for single-consume response & sequential 3 x 6 MB budget release tests
        val server = MockWebServer()
        val sixMb = ByteArray(6 * 1024 * 1024) { 0x5A }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/small" -> MockResponse().setResponseCode(200).setBody("hello-once")
                "/6mb" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(sixMb))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.r3",
            name = "R3",
            version = "1.0.0",
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
        )
        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = setOf("192.168.1.50"),
            dnsResolver = { listOf(InetAddress.getByName("192.168.1.50")) },
        )
        val fetchGw = PluginFetchGateway(policy, cookieJar, baseClient = okHttp)

        val mainJs = """
            export default {
              home() { return []; },
              async browse(ctx, arg) {
                const mode = arg.categoryId;
                if (mode === 'dom-case') {
                  const doc = arg.mode === 'xml' ? ctx.parse.xml(arg.input) : ctx.parse.html(arg.input);
                  const out = [];
                  for (const a of arg.assertions) {
                    if (a.op === 'rootTag') out.push(doc.tag);
                    else if (a.op === 'rootText') out.push(doc.text);
                    else if (a.op === 'rootChildrenTags') out.push(doc.children.map((c) => c.tag));
                    else if (a.op === 'firstTag') out.push(doc.querySelector(a.selector)?.tag ?? null);
                    else if (a.op === 'firstText') out.push(doc.querySelector(a.selector)?.text ?? null);
                    else if (a.op === 'firstAttr') out.push(doc.querySelector(a.selector)?.attr(a.attr));
                    else if (a.op === 'allTexts') out.push(doc.querySelectorAll(a.selector).map((n) => n.text));
                    else if (a.op === 'allTags') out.push(doc.querySelectorAll(a.selector).map((n) => n.tag));
                    else if (a.op === 'allAttrs') out.push(doc.querySelectorAll(a.selector).map((n) => n.attr(a.attr)));
                    else if (a.op === 'childrenTags') out.push(doc.querySelector(a.selector).children.map((c) => c.tag));
                    else if (a.op === 'subFirstAttr') {
                      const p = doc.querySelector(a.parent);
                      out.push(p ? (p.querySelector(a.selector)?.attr(a.attr) ?? null) : null);
                    } else if (a.op === 'selectorThrows') {
                      let threw = false;
                      try { doc.querySelector(a.selector); } catch { threw = true; }
                      out.push(threw);
                    }
                  }
                  return out;
                }
                if (mode === 'log-flood') {
                  const big = 'A'.repeat(100000);
                  for (let i = 0; i < 200; i++) ctx.log.info(i + ':' + big);
                  return { ok: true };
                }
                if (mode === 'storage-oversize') {
                  await ctx.storage.set('huge', 'B'.repeat(2 * 1024 * 1024));
                  return { ok: true };
                }
                if (mode === 'return-oversize') {
                  return 'R'.repeat(9 * 1024 * 1024);
                }
                if (mode === 'dom-node-flood') {
                  ctx.parse.html('<a></a>'.repeat(210000));
                  return { ok: true };
                }
                if (mode === 'timer-flood') {
                  for (let i = 0; i < 257; i++) setTimeout(() => {}, 5000);
                  return { ok: true };
                }
                if (mode === 'response-second-read') {
                  const r = await ctx.fetch(arg.url);
                  const t = r.text();
                  let secondErr = '';
                  try { r.bytes(); } catch (e) { secondErr = e.name + ':' + e.message; }
                  let thirdErr = '';
                  try { r.text(); } catch (e) { thirdErr = e.name + ':' + e.message; }
                  return { t, secondErr, thirdErr };
                }
                if (mode === 'seq-18mb') {
                  let sum = 0;
                  for (let i = 0; i < 3; i++) {
                    const r = await ctx.fetch(arg.url);
                    sum += r.bytes().byteLength;
                  }
                  return { sum };
                }
                if (mode === 'plain-timeout-error') {
                  throw new Error('upstream timeout while parsing');
                }
                if (mode === 'explicit-unavailable') {
                  throw new ctx.Error('Unavailable', '上游维护中');
                }
                if (mode === 'immutable-globals') {
                  const names = ['__d3_invoke', '__d3_invoke_optional', '__d3_exports', '__d3_eval_probe'];
                  return names.map((n) => {
                    const d = Object.getOwnPropertyDescriptor(globalThis, n);
                    let writeFailed = false;
                    try { globalThis[n] = () => 'pwned'; } catch { writeFailed = true; }
                    let delFailed = false;
                    try { delete globalThis[n]; } catch { delFailed = true; }
                    return { name: n, writable: d.writable, configurable: d.configurable, writeFailed, delFailed };
                  });
                }
                return { items: [] };
              },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
            };
        """.trimIndent()

        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-r3",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = fetchGw,
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
        )

        try {
            // 1. Item #2: Shared DOM vectors (HTML + XML: DASH MPD, RSS CDATA & <link>, case, whitespace, attr)
            for (caseEl in domVectors) {
                val c = caseEl.jsonObject
                val caseName = c["name"]!!.jsonPrimitive.content
                val assertions = c["assertions"]!!.jsonArray
                val arg = JsonObject(
                    mapOf(
                        "categoryId" to JsonPrimitive("dom-case"),
                        "mode" to c["mode"]!!,
                        "input" to c["input"]!!,
                        "assertions" to assertions,
                    ),
                )
                val actual = runtime.invoke("browse", arg).jsonArray
                assertEquals(assertions.size, actual.size)
                for (i in assertions.indices) {
                    val expected = assertions[i].jsonObject["expected"]!!
                    assertEquals("DOM vector '$caseName' assertion #$i", expected, actual[i])
                }
            }

            // 2. Item #3a: ctx.log 200 x 100 KB -> each truncated <= 4 KB, total ring <= 256 KB
            runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("log-flood"))))
            val entries = logger.recentEntries()
            assertTrue(entries.isNotEmpty())
            assertTrue("Ring entries must be <= 200", entries.size <= PluginLogger.MAX_ENTRIES)
            for (entry in entries) {
                assertTrue(
                    "Each log entry must be <= 4 KB, got ${entry.message.toByteArray().size}",
                    entry.message.toByteArray(Charsets.UTF_8).size <= PluginLogger.MAX_MESSAGE_BYTES,
                )
            }
            assertTrue(
                "Total buffered log bytes must stay <= 256 KB, got ${logger.currentBufferedBytes()}",
                logger.currentBufferedBytes() <= PluginLogger.MAX_BUFFER_BYTES,
            )

            // 3. Item #3b: storage.set > 1 MB rejected BEFORE JSON parse; storage untouched & disk/heap bounded
            val storageFile = File(tmpDir, "storage.enc")
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("storage-oversize"))))
                fail("Oversized storage.set must fail")
            } catch (_: PluginInternalException) {
            }
            assertEquals(null, storage.get("huge"))
            assertFalse("Oversized write must not persist a multi-MB file", storageFile.exists() && storageFile.length() > 1024)
            // Direct proof that UTF-8 byte-length check runs BEFORE JSON parsing (malformed 2 MB JSON hits quota, not parse error)
            try {
                storage.setRawJson("bad", "{" + "x".repeat(2 * 1024 * 1024))
                fail("Oversized malformed JSON must fail with StorageQuotaExceededException before parsing")
            } catch (_: StorageQuotaExceededException) {
            }

            // 4. Item #3c & Round 4 Item #9: Return payload > 8 MB maps to Internal with bounded host heap
            fun settledUsedBytes(): Long {
                System.gc()
                Thread.sleep(20)
                val rt = Runtime.getRuntime()
                return rt.totalMemory() - rt.freeMemory()
            }
            val usedBeforeReturn = settledUsedBytes()
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("return-oversize"))))
                fail("Oversized return value (> 8 MB) must map to PluginInternalException")
            } catch (_: PluginInternalException) {
            }
            val returnHeapDelta = settledUsedBytes() - usedBeforeReturn
            assertTrue(
                "Host heap growth after rejecting 9 MB return must stay bounded (< 8 MB), got ${returnHeapDelta / (1024 * 1024)} MB",
                returnHeapDelta < 8L * 1024 * 1024,
            )
            // Runtime remains healthy after rejecting oversized return
            assertEquals(0, runtime.invoke("home").jsonArray.size)

            // 5. Item #3d: ctx.parse > 200k nodes rejected BEFORE building 210k Jsoup nodes
            val usedBeforeDomFlood = settledUsedBytes()
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("dom-node-flood"))))
                fail("DOM with 210k elements must be rejected")
            } catch (_: PluginInternalException) {
            }
            val domFloodDelta = settledUsedBytes() - usedBeforeDomFlood
            assertTrue(
                "Rejected 210k-node DOM flood must not grow host heap by > 16 MB, got ${domFloodDelta / (1024 * 1024)} MB",
                domFloodDelta < 16L * 1024 * 1024,
            )
            val directDom = PluginDomParser()
            try {
                directDom.parseHtml("<a></a>".repeat(210_000))
                fail("Direct PluginDomParser must reject 210k elements before building DOM tree")
            } catch (_: DomLimitExceededException) {
            }
            assertEquals("Rejected DOM parse must retain 0 nodes in host parser", 0, directDom.currentTotalNodes())

            // 6. Item #3e: setTimeout > 256 concurrent timers rejected immediately; runtime stays clean
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("timer-flood"))))
                fail("257th concurrent setTimeout must be rejected")
            } catch (_: PluginInternalException) {
            }
            assertEquals("Active timers must return to 0 after call finishes", 0, runtime.currentActiveTimerCount())
            assertEquals(0, runtime.invoke("home").jsonArray.size)

            // 7. Item #8a: Single-consume Response body (second read throws TypeError)
            val baseUrl = "http://192.168.1.50:${server.port}"
            val secondRead = runtime.invoke(
                "browse",
                JsonObject(mapOf("categoryId" to JsonPrimitive("response-second-read"), "url" to JsonPrimitive("$baseUrl/small"))),
            ).jsonObject
            assertEquals("hello-once", secondRead["t"]!!.jsonPrimitive.content)
            assertTrue(secondRead["secondErr"]!!.jsonPrimitive.content.contains("TypeError"))
            assertTrue(secondRead["thirdErr"]!!.jsonPrimitive.content.contains("TypeError"))

            // 8. Item #8b: Sequential 3 x 6 MB fetches succeed (18 MB > 16 MB in-flight cap) because budget releases on read
            val seq = runtime.invoke(
                "browse",
                JsonObject(mapOf("categoryId" to JsonPrimitive("seq-18mb"), "url" to JsonPrimitive("$baseUrl/6mb"))),
            ).jsonObject
            assertEquals(18 * 1024 * 1024, seq["sum"]!!.jsonPrimitive.content.toInt())
            assertEquals(0L, fetchGw.inFlightBudget.currentUsed())

            // 9. Item #8c & #8d: Plain Error('...timeout...') -> Internal; explicit ctx.Error('Unavailable') does NOT rebuild VM
            val creationsBefore = runtime.creationCount
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("plain-timeout-error"))))
                fail("Plain Error('timeout') must map to PluginInternalException")
            } catch (_: PluginInternalException) {
            }
            assertEquals("Plain timeout Error must not rebuild VM", creationsBefore, runtime.creationCount)

            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("explicit-unavailable"))))
                fail("Explicit Unavailable must throw SourceUnavailableException")
            } catch (_: SourceUnavailableException) {
            }
            assertEquals("Explicit ctx.Error('Unavailable') must not rebuild VM", creationsBefore, runtime.creationCount)

            // 10. Item #8e: Cookie jar parity (Max-Age > Expires, PSL rejection + WARN, Domain mismatch + WARN, IPv6 literal)
            warnings.clear()
            cookieJar.setCookie("https://api.example.com/", "m=1; Max-Age=0; Expires=Wed, 21 Oct 2099 07:28:00 GMT")
            assertEquals("Max-Age=0 must override future Expires", "", cookieJar.getHeaderForUrl("https://api.example.com/"))

            cookieJar.setCookie("https://api.example.com/", "psl1=bad; Domain=com")
            cookieJar.setCookie("https://shop.co.uk/", "psl2=bad; Domain=co.uk")
            cookieJar.setCookie("https://api.example.com/", "mismatch=bad; Domain=evil.com")
            assertEquals("", cookieJar.getHeaderForUrl("https://api.example.com/"))
            assertEquals("", cookieJar.getHeaderForUrl("https://shop.co.uk/"))
            assertEquals("PSL & domain-mismatch cookies must emit 3 WARN logs", 3, warnings.size)

            cookieJar.setCookie("http://[fd12:3456:789a::1]:8096/", "v6=ok; Path=/")
            assertEquals(
                "IPv6 literal host matching must canonicalize fd12:3456:789a::0001 == fd12:3456:789a::1",
                "v6=ok",
                cookieJar.getHeaderForUrl("http://[fd12:3456:789a::0001]:8096/Items"),
            )

            // 11. Item #12: __d3_invoke etc. are non-writable and non-configurable
            val globals = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("immutable-globals")))).jsonArray
            assertEquals(4, globals.size)
            for (g in globals) {
                val obj = g.jsonObject
                assertFalse("${obj["name"]} must not be writable", obj["writable"]!!.jsonPrimitive.boolean)
                assertFalse("${obj["name"]} must not be configurable", obj["configurable"]!!.jsonPrimitive.boolean)
                assertTrue("${obj["name"]} write must fail in strict mode", obj["writeFailed"]!!.jsonPrimitive.boolean)
                assertTrue("${obj["name"]} delete must fail in strict mode", obj["delFailed"]!!.jsonPrimitive.boolean)
            }
        } finally {
            runtime.close()
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun blocksDomFormattingAmplificationUnderConstrainedHeapAndEnforcesFetchRequestLimitsAndAtomicTimers(): Unit = runBlocking {
        // 1. Round 4 Item #1 [Blocking]: Exact Amp.java formatting-element amplification payload
        //    ("<div>" + 100 x <b id=i> + "</div>" + 190,000 x <p>x</p>, 1.52 MB, pre-scan 190,101 < 200k)
        //    MUST throw DomLimitExceededException / PluginInternalException (NOT OutOfMemoryError!)
        //    under a constrained -Xmx192m heap AND keep heap growth strictly bounded.
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            javaBin,
            "-Xmx192m",
            "-cp",
            classpath,
            DomAmplificationSubprocessMain::class.java.name,
        ).redirectErrorStream(true).start()
        val procOut = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        assertEquals("Constrained -Xmx192m DOM amplification probe must exit cleanly (not OOM):\n$procOut", 0, exitCode)
        assertTrue("Probe must report DOM_LIMIT_THROWN:\n$procOut", procOut.contains("DOM_LIMIT_THROWN"))
        val deltaMatch = Regex("""HEAP_DELTA_MB=(-?\d+)""").find(procOut)
        val peakMatch = Regex("""PEAK_DELTA_MB=(-?\d+)""").find(procOut)
        assertTrue("Probe must report HEAP_DELTA_MB and PEAK_DELTA_MB:\n$procOut", deltaMatch != null && peakMatch != null)
        val heapDeltaMb = deltaMatch!!.groupValues[1].toLong()
        val peakDeltaMb = peakMatch!!.groupValues[1].toLong()
        assertTrue(
            "Peak heap growth DURING parsing under -Xmx192m must stay bounded (< 96 MB, vs 388 MB OOM), got $peakDeltaMb MB:\n$procOut",
            peakDeltaMb < 96L,
        )
        assertTrue(
            "Retained heap growth after abort must stay bounded (< 32 MB), got $heapDeltaMb MB:\n$procOut",
            heapDeltaMb < 32L,
        )

        // Also verify through real QuickJS PluginRuntime -> maps cleanly to PluginInternalException
        val tmpDir = Files.createTempDirectory("d3-r4-limits").toFile()
        val secretBox = SoftwareAesSecretBox()
        val logger = PluginLogger("dev.depth3d.r4", "inst-r4", sink = { _, _ -> })
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)

        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/slow")) {
                    Thread.sleep(250)
                    return MockResponse().setResponseCode(200).setBody("ok")
                }
                return MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        server.start()

        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.r4",
            name = "R4",
            version = "1.0.0",
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
        )
        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = setOf("192.168.1.50"),
            dnsResolver = { listOf(InetAddress.getByName("192.168.1.50")) },
        )
        val fetchGw = PluginFetchGateway(policy, cookieJar, baseClient = okHttp)
        val baseUrl = "http://192.168.1.50:${server.port}"

        val mainJs = """
            export default {
              home() { return []; },
              async browse(ctx, arg) {
                const mode = arg.categoryId;
                if (mode === 'dom-amp') {
                  let s = '<div>';
                  for (let i = 0; i < 100; i++) s += '<b id=' + i + '>';
                  s += '</div>';
                  s += '<p>x</p>'.repeat(190000);
                  ctx.parse.html(s);
                  return { ok: true };
                }
                if (mode === 'fetch-20mb-post') {
                  // Unawaited multi-POST with 20 MB body must be rejected immediately before host buffering
                  const big = 'P'.repeat(20 * 1024 * 1024);
                  await ctx.fetch(arg.url, { method: 'POST', body: big });
                  return { ok: true };
                }
                if (mode === 'fetch-oversize-url') {
                  await ctx.fetch(arg.url + '/' + 'u'.repeat(9 * 1024));
                  return { ok: true };
                }
                if (mode === 'fetch-oversize-headers') {
                  await ctx.fetch(arg.url, { headers: { 'X-Big': 'H'.repeat(35 * 1024) } });
                  return { ok: true };
                }
                if (mode === 'fetch-queue-flood') {
                  const ps = [];
                  for (let i = 0; i < 25; i++) {
                    ps.push(
                      ctx.fetch(arg.url + '/slow?i=' + i).then(
                        () => 'ok',
                        (e) => 'err:' + (e.message || String(e)),
                      ),
                    );
                  }
                  return { results: await Promise.all(ps) };
                }
                return { items: [] };
              },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
            };
        """.trimIndent()

        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-r4",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = fetchGw,
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
        )

        try {
            // Item #1 via QuickJS runtime
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("dom-amp"))))
                fail("Formatting-element amplification bomb must map to PluginInternalException")
            } catch (_: PluginInternalException) {
            }

            // Item #2a: 20 MB POST rejected immediately with bounded host heap
            System.gc()
            Thread.sleep(20)
            val beforePost = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("fetch-20mb-post"), "url" to JsonPrimitive(baseUrl))))
                fail("20 MB POST body must be rejected")
            } catch (_: PluginInternalException) {
            }
            System.gc()
            Thread.sleep(20)
            val postDelta = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - beforePost
            assertTrue(
                "Host heap must not retain 20 MB POST payload, delta=${postDelta / (1024 * 1024)} MB",
                postDelta < 16L * 1024 * 1024,
            )

            // Item #2b: > 8 KB URL and > 32 KB headers rejected
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("fetch-oversize-url"), "url" to JsonPrimitive(baseUrl))))
                fail("> 8 KB URL must be rejected")
            } catch (_: PluginInternalException) {
            }
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("fetch-oversize-headers"), "url" to JsonPrimitive(baseUrl))))
                fail("> 32 KB headers must be rejected")
            } catch (_: PluginInternalException) {
            }

            // Item #2c: 25 unawaited fetches -> 4 active + 16 queued = 20 ok, 5 rejected immediately with queue cap
            val qRes = runtime.invoke(
                "browse",
                JsonObject(mapOf("categoryId" to JsonPrimitive("fetch-queue-flood"), "url" to JsonPrimitive(baseUrl))),
            ).jsonObject["results"]!!.jsonArray.map { it.jsonPrimitive.content }
            val okCount = qRes.count { it == "ok" }
            val errList = qRes.filter { it.startsWith("err:") }
            assertEquals("Expected 4 active + 16 queued = 20 succeeded, got $qRes", 20, okCount)
            assertEquals("Expected 5 rejected above 16-request queue cap, got $qRes", 5, errList.size)
            assertTrue("Queue rejections must cite queue limit: $errList", errList.all { it.contains("排队") })
            assertEquals("Queued count must return to 0 after completion", 0, fetchGw.currentQueuedCount())

            // Item #10: Atomic timer slot reservation under heavy concurrency NEVER exceeds 256
            val granted = java.util.concurrent.atomic.AtomicInteger(0)
            val maxObserved = java.util.concurrent.atomic.AtomicInteger(0)
            val workers = (1..500).map {
                async(Dispatchers.Default) {
                    if (runtime.tryReserveTimerSlot()) {
                        val cur = runtime.currentActiveTimerCount()
                        maxObserved.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                        granted.incrementAndGet()
                    }
                }
            }
            workers.forEach { it.await() }
            assertEquals("Exactly 256 timer slots must be granted across 500 concurrent callers", PluginRuntime.MAX_ACTIVE_TIMERS, granted.get())
            assertTrue("Observed active timer count must never exceed 256, got ${maxObserved.get()}", maxObserved.get() <= PluginRuntime.MAX_ACTIVE_TIMERS)
            repeat(granted.get()) { runtime.releaseTimerSlot() }
            assertEquals("Active timer count must return cleanly to 0", 0, runtime.currentActiveTimerCount())
        } finally {
            runtime.close()
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun enforcesLinearMemoizedSelectorsMatrix85ParityExactRecountAndQueuedFetchBodyCap(): Unit = runBlocking {
        // 1. Round 5 Item #3: Run ALL 85 cases2.json matrixCases from shared dom-vectors.json
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val vecRoot = kotlinx.serialization.json.Json.parseToJsonElement(
            File(repoRoot, "docs/specs/plugin-schema/dom-vectors.json").readText(),
        ).jsonObject
        val matrixCases = vecRoot["matrixCases"]!!.jsonArray
        assertTrue("Expected all 85+ matrixCases including knownDifference vectors", matrixCases.size >= 96)
        var knownDiffCount = 0
        for (mcEl in matrixCases) {
            val mc = mcEl.jsonObject
            val id = mc["id"]!!.jsonPrimitive.int
            val mode = mc["mode"]!!.jsonPrimitive.content
            val input = mc["input"]!!.jsonPrimitive.content
            val target = mc["target"]!!.jsonPrimitive.content
            val op = mc["op"]!!.jsonPrimitive.content
            val expectThrows = mc["throws"]!!.jsonPrimitive.boolean
            val isKnownDiff = mc["knownDifference"]?.jsonPrimitive?.boolean == true
            if (isKnownDiff) knownDiffCount += 1
            val expectedEl = if (isKnownDiff) mc["expectedAndroid"]!! else mc["expected"]!!

            val parser = PluginDomParser()
            var threw = false
            var actual: String? = null
            try {
                val root = if (mode == "xml") parser.parseXml(input) else parser.parseHtml(input)
                val nodeId = if (target == "#root") root else parser.selectFirstId(root, target)
                actual = when {
                    nodeId <= 0 -> null
                    op == "tag" -> parser.tag(nodeId)
                    op == "text" -> parser.text(nodeId)
                    op == "html" -> parser.html(nodeId)
                    op == "outer" -> parser.outerHtml(nodeId)
                    op == "attrs" -> parser.attrsJson(nodeId)
                    op == "children" -> parser.childrenIds(nodeId).joinToString(",") { parser.tag(it.jsonPrimitive.int) }
                    op.startsWith("attr:") -> parser.attr(nodeId, op.removePrefix("attr:"))
                    op.startsWith("all:") -> parser.selectAllIds(nodeId, op.removePrefix("all:"))
                        .joinToString("|") { el ->
                            val cid = el.jsonPrimitive.int
                            "${parser.tag(cid)}=${parser.text(cid)}"
                        }
                    else -> fail("Unknown op: $op").toString()
                }
            } catch (_: Exception) {
                threw = true
            }
            if (expectThrows) {
                assertTrue("matrixCase #$id ($mode / $target / $op) must throw", threw)
            } else {
                assertFalse("matrixCase #$id ($mode / $target / $op) must not throw", threw)
                val expectedStr = if (expectedEl is kotlinx.serialization.json.JsonNull) null else expectedEl.jsonPrimitive.content
                assertEquals("matrixCase #$id ($mode / $target / $op) mismatch", expectedStr, actual)
            }
        }
        assertTrue("Expected at least 6 knownDifference vectors (select, ws, template)", knownDiffCount >= 6)

        // 2. Round 5 Item #1 [Blocking]: Pathological selectors MUST complete in < 1s via O(N*S) memoized DP
        run {
            val p1 = PluginDomParser()
            val r1 = p1.parseHtml("<div>".repeat(500))
            val t1 = System.nanoTime()
            val m1 = p1.selectAllIds(r1, "span div div div div")
            val ms1 = (System.nanoTime() - t1) / 1_000_000L
            assertEquals(0, m1.size)
            assertTrue("500 nested divs + 'span div div div div' must finish in < 1000ms, took ${ms1}ms", ms1 < 1000L)
        }
        run {
            val p2 = PluginDomParser()
            val r2 = p2.parseHtml("<ul>" + "<li>x</li>".repeat(2000) + "</ul>")
            val t2a = System.nanoTime()
            val m2a = p2.selectAllIds(r2, "b ~ li ~ li")
            val ms2a = (System.nanoTime() - t2a) / 1_000_000L
            assertEquals(0, m2a.size)
            assertTrue("2000 li + 'b ~ li ~ li' must finish in < 1000ms, took ${ms2a}ms", ms2a < 1000L)

            val t2b = System.nanoTime()
            val m2b = p2.selectAllIds(r2, "b ~ li ~ li ~ li")
            val ms2b = (System.nanoTime() - t2b) / 1_000_000L
            assertEquals(0, m2b.size)
            assertTrue("2000 li + 'b ~ li ~ li ~ li' must finish in < 1000ms, took ${ms2b}ms", ms2b < 1000L)
        }
        // 100,000 sibling <li> + :nth-child / :last-child / :nth-last-of-type MUST each finish in < 1000ms!
        run {
            val p3 = PluginDomParser()
            val r3 = p3.parseHtml("<ul>" + "<li>x</li>".repeat(100_000) + "</ul>")
            val checks = listOf(
                "li:nth-child(2n+1)" to 50_000,
                "li:last-child" to 1,
                "li:nth-last-of-type(3n+1)" to 33_334,
            )
            for ((sel, expectedCount) in checks) {
                val t = System.nanoTime()
                val res = p3.selectAllIds(r3, sel)
                val ms = (System.nanoTime() - t) / 1_000_000L
                assertEquals("Selector $sel match count", expectedCount, res.size)
                assertTrue("100k siblings '$sel' must finish in < 1000ms, took ${ms}ms", ms < 1000L)
            }
        }
        // Step budget enforcement & deadline timeout enforcement
        run {
            val tight = PluginDomParser(maxSelectorSteps = 500)
            val r = tight.parseHtml("<ul>" + "<li>x</li>".repeat(2000) + "</ul>")
            try {
                tight.selectAllIds(r, "li")
                fail("Exceeding maxSelectorSteps must throw DomLimitExceededException")
            } catch (_: DomLimitExceededException) {
            }
            val expired = PluginDomParser()
            val rExp = expired.parseHtml("<ul>" + "<li>x</li>".repeat(3000) + "</ul>")
            expired.setCallDeadlineNanos(System.nanoTime() - 1L)
            try {
                expired.selectAllIds(rExp, "li")
                fail("Expired call deadline must throw DomTimeoutException")
            } catch (_: DomTimeoutException) {
            }
        }

        // 3. Round 5 Item #4: Exact post-parse element recount catches adoption-agency undercount + unified count excluding #document
        run {
            val p = PluginDomParser()
            try {
                p.parseHtml("<b><p>x</b>y</p>".repeat(95_000))
                fail("95,000x <b><p>x</b>y</p> (285k actual elements) must be rejected by exact post-parse recount")
            } catch (_: DomLimitExceededException) {
            }
            assertEquals(0, p.currentTotalNodes())

            val pCount = PluginDomParser()
            pCount.parseHtml("<p>x</p>")
            assertEquals("HTML <p>x</p> has 4 elements (html,head,body,p) excluding #document", 4, pCount.currentTotalNodes())
            pCount.parseXml("<r><a/></r>")
            assertEquals("XML <r><a/></r> adds 2 elements (r,a) excluding #document", 6, pCount.currentTotalNodes())
        }

        // 4. Round 5 Item #5: fetch method/redirect length pre-checks + 8 MB queued+active request body cap
        val tmpDir = Files.createTempDirectory("d3-r5-fetch").toFile()
        val secretBox = SoftwareAesSecretBox()
        val logger = PluginLogger("dev.depth3d.r5", "inst-r5", sink = { _, _ -> })
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/slow-post")) {
                    Thread.sleep(250)
                }
                return MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        server.start()
        val okHttp = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LocalRedirectSocketFactory("192.168.1.50", server.port))
            .build()
        val manifest = PluginManifest(
            api = 1,
            id = "dev.depth3d.r5",
            name = "R5",
            version = "1.0.0",
            permissions = PluginPermissions(hosts = emptyList(), userHosts = true, lan = true),
        )
        val policy = NetworkPolicy(
            permissions = manifest.permissions,
            userConfiguredHosts = setOf("192.168.1.50"),
            dnsResolver = { listOf(InetAddress.getByName("192.168.1.50")) },
        )
        val fetchGw = PluginFetchGateway(policy, cookieJar, baseClient = okHttp)
        val baseUrl = "http://192.168.1.50:${server.port}"
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-r5",
            mainJsSource = """
                export default {
                  home() { return []; },
                  async browse(ctx, arg) {
                    if (arg.categoryId === 'bad-method') {
                      await ctx.fetch(arg.url, { method: 'G'.repeat(500) });
                    }
                    if (arg.categoryId === 'bad-redirect') {
                      await ctx.fetch(arg.url, { redirect: 'follow'.repeat(500) });
                    }
                    if (arg.categoryId === 'body-budget-flood') {
                      const body = 'B'.repeat(1800 * 1024); // 1.8 MB each -> 6 * 1.8 MB = 10.8 MB > 8 MB cap
                      const ps = [];
                      for (let i = 0; i < 6; i++) {
                        ps.push(ctx.fetch(arg.url + '/slow-post?i=' + i, { method: 'POST', body }).then(() => 'ok', (e) => 'err:' + (e.message || e)));
                      }
                      return { results: await Promise.all(ps) };
                    }
                    return { items: [] };
                  },
                  detail() { return { id: '1', kind: 'video', title: '1' }; },
                  playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
                };
            """.trimIndent(),
            config = emptyMap(),
            fetchGateway = fetchGw,
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
        )
        try {
            for (bad in listOf("bad-method", "bad-redirect")) {
                try {
                    runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive(bad), "url" to JsonPrimitive(baseUrl))))
                    fail("Oversized $bad must be rejected")
                } catch (_: PluginInternalException) {
                }
            }
            val floodRes = runtime.invoke(
                "browse",
                JsonObject(mapOf("categoryId" to JsonPrimitive("body-budget-flood"), "url" to JsonPrimitive(baseUrl))),
            ).jsonObject["results"]!!.jsonArray.map { it.jsonPrimitive.content }
            val errs = floodRes.filter { it.startsWith("err:") }
            assertTrue("6 x 1.8 MB concurrent POSTs must trip 8 MB queued+active request body cap: $floodRes", errs.isNotEmpty())
            assertEquals("Request body budget must return to 0 after call finishes", 0L, fetchGw.requestBodyBudget.currentUsed())
        } finally {
            runtime.close()
            server.shutdown()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun enforcesRound6HardCapsBoundedMemoryDocCloseAndBridgeErrorTrapsUnderXmx256m(): Unit = runBlocking {
        // 1. Run ALL Round 6 worst-case probes inside a strict -Xmx256m child JVM process,
        //    proving every single worst-case input returns or throws a clear error in < 1s
        //    without OutOfMemoryError or StackOverflowError.
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            javaBin,
            "-Xmx256m",
            "-cp",
            classpath,
            Round6WorstCaseSubprocessMain::class.java.name,
        ).redirectErrorStream(true).start()
        val procOut = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        assertEquals(
            "Constrained -Xmx256m Round 6 worst-case probe suite MUST pass cleanly:\n$procOut",
            0,
            exitCode,
        )
        assertTrue("Probe must emit ROUND6_ALL_PASSED:\n$procOut", procOut.contains("ROUND6_ALL_PASSED"))

        // 2. Verify doc.close(), live-doc reclamation, handle reuse, and host bridge
        //    StackOverflowError / OutOfMemoryError traps inside real QuickJS PluginRuntime.
        val tmpDir = Files.createTempDirectory("d3-r6-runtime").toFile()
        val secretBox = SoftwareAesSecretBox()
        val logger = PluginLogger("dev.depth3d.r6", "inst-r6", sink = { _, _ -> })
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val manifest = PluginManifest(api = 1, id = "dev.depth3d.r6", name = "R6", version = "1.0.0")
        val mainJs = """
            export default {
              home() { return []; },
              browse(ctx, arg) {
                const m = arg.categoryId;
                if (m === 'doc-close-loop') {
                  // Parse & close 20 docs sequentially (> 8 live cap) -> MUST succeed!
                  for (let i = 0; i < 20; i++) {
                    const d = ctx.parse.html('<p>doc-' + i + '</p>');
                    const p = d.querySelector('p');
                    if (p.text !== 'doc-' + i) throw new Error('mismatch');
                    d.close();
                    // Accessing closed handles must degrade safely without crash
                    if (d.tag !== '' || p.text !== '') throw new Error('closed handle not cleared');
                  }
                  return { ok: true };
                }
                if (m === 'doc-unclosed-9') {
                  for (let i = 0; i < 9; i++) ctx.parse.html('<p>' + i + '</p>');
                  return { ok: true };
                }
                if (m === 'trigger-bridge') {
                  const d = ctx.parse.html('<p>hi</p>');
                  return { tag: d.querySelector('p').tag };
                }
                return { ok: true };
              },
              detail() { return { id: '1', kind: 'video', title: '1' }; },
              playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
            };
        """.trimIndent()
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-r6",
            mainJsSource = mainJs,
            config = emptyMap(),
            fetchGateway = PluginFetchGateway(NetworkPolicy(manifest.permissions), cookieJar),
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
        )
        try {
            val closeRes = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("doc-close-loop"))))
            assertTrue(closeRes.jsonObject["ok"]!!.jsonPrimitive.boolean)

            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("doc-unclosed-9"))))
                fail("9 unclosed live documents must fail with PluginInternalException")
            } catch (_: PluginInternalException) {
            }

            // Bridge StackOverflowError trap: NEVER leaks StackOverflowError out of host function!
            val creationsBeforeSo = runtime.creationCount
            runtime.bridgeFaultInjectorForTesting = { op ->
                if (op == "domSelectFirstId") throw StackOverflowError("simulated bridge stack overflow")
            }
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("trigger-bridge"))))
                fail("Bridge StackOverflowError must map cleanly to PluginInternalException")
            } catch (_: PluginInternalException) {
            } finally {
                runtime.bridgeFaultInjectorForTesting = null
            }
            assertEquals(creationsBeforeSo, runtime.creationCount)
            // Runtime remains completely healthy after trapped StackOverflowError
            val afterSo = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("trigger-bridge"))))
            assertEquals("p", afterSo.jsonObject["tag"]!!.jsonPrimitive.content)

            // Bridge OutOfMemoryError trap: NEVER leaks OutOfMemoryError & destroys/rebuilds VM!
            val creationsBeforeOom = runtime.creationCount
            runtime.bridgeFaultInjectorForTesting = { op ->
                if (op == "domSelectFirstId") throw OutOfMemoryError("simulated bridge OOM")
            }
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("trigger-bridge"))))
                fail("Bridge OutOfMemoryError must map cleanly to PluginInternalException")
            } catch (_: PluginInternalException) {
            } finally {
                runtime.bridgeFaultInjectorForTesting = null
            }
            val afterOom = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("trigger-bridge"))))
            assertEquals("p", afterOom.jsonObject["tag"]!!.jsonPrimitive.content)
            assertEquals("VM must rebuild after bridge OutOfMemoryError", creationsBeforeOom + 1, runtime.creationCount)
        } finally {
            runtime.close()
            tmpDir.deleteRecursively()
        }
    }
}

/**
 * Isolated `-Xmx256m` child JVM proving EVERY Round 6 worst-case DOM/selector
 * input completes or throws a clear plugin limit/syntax/timeout error in < 1s
 * with bounded memory and zero StackOverflowError / OutOfMemoryError.
 */
object Round6WorstCaseSubprocessMain {
    private inline fun assertFastUnder1s(name: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000L
        if (ms >= 1000L) {
            throw AssertionError("Case '$name' exceeded 1000ms budget: ${ms}ms")
        }
        println("OK [$name] ${ms}ms")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Pre-build worst-case documents once to exercise query & registry behavior under -Xmx256m
        val html190k = "<div>" + "<i></i>".repeat(190_000) + "</div>"

        // Case 1 (Blocking #1): 190k <i> + `x` followed by 2999 x ` x` (~6 KB) -> rejected at parse in < 1s
        run {
            val p = PluginDomParser()
            val root = p.parseHtml(html190k)
            val hugeSel = "x" + " x".repeat(2999)
            assertFastUnder1s("B1_long_selector_6kb") {
                try {
                    p.selectAllIds(root, hugeSel)
                    throw AssertionError("6 KB / 3000-compound selector must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Valid 32-compound non-matching selector on 190k <i> -> lazy memo allocates 0 bytes & finishes in < 1s
            val valid32NonMatch = "x" + " x".repeat(31)
            assertFastUnder1s("B1_valid32_nonmatch_zero_memo") {
                val res = p.selectAllIds(root, valid32NonMatch)
                if (res.size != 0) throw AssertionError("Expected 0 matches, got ${res.size}")
            }
            // Valid 32-compound matching-tail descendant selector on 190k <i> -> parent-keyed DP finishes in < 1s
            val valid32MatchTail = "x" + " i".repeat(31)
            assertFastUnder1s("B1_valid32_matchtail_parent_dp") {
                val res = p.selectAllIds(root, valid32MatchTail)
                if (res.size != 0) throw AssertionError("Expected 0 matches, got ${res.size}")
            }
            // Valid 32-compound sibling selector on 190k <i> -> trips 16 MB query memory budget -> DomLimitExceededException in < 1s!
            val siblingBudgetTrip = "x" + " ~ i".repeat(31)
            assertFastUnder1s("B1_16mb_memory_budget_trip") {
                try {
                    p.selectAllIds(root, siblingBudgetTrip)
                    throw AssertionError("190k siblings x 32 compounds must trip 16 MB memo memory budget")
                } catch (_: DomLimitExceededException) {
                }
            }
            p.clear()
        }

        // Case 2 (Blocking #2): 2000 x `~ li` -> rejected at parse (> 32 compounds) + max allowed depth 4 x 32 no SOE
        run {
            val p = PluginDomParser()
            val ul2k = "<ul>" + "<li></li>".repeat(2010) + "</ul>"
            val root = p.parseHtml(ul2k)
            val deepSib = "li" + " ~ li".repeat(1999) + ":last-child"
            assertFastUnder1s("B2_2000_sibling_rejected") {
                try {
                    p.selectAllIds(root, deepSib)
                    throw AssertionError("2000-compound selector must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Nesting depth > 4 rejected
            assertFastUnder1s("B2_nesting_5_rejected") {
                try {
                    p.selectAllIds(root, ":not(:not(:not(:not(:not(li)))))")
                    throw AssertionError("Nesting depth 5 must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Max legal complexity (depth 4 nesting + 32 compounds): :not(:is(:not(span))) == span (false on li),
            // so `chain32` (32 sibling `li`s) matches indices 32..2010 = 1979 elements, no StackOverflowError, < 1s
            val chain32 = "li" + " ~ li".repeat(31)
            val maxLegal = "li:is(:not(:is(:not(span))), $chain32)"
            assertFastUnder1s("B2_max_legal_depth4_32compounds") {
                val res = p.selectAllIds(root, maxLegal)
                if (res.size != 1979) throw AssertionError("Expected 1979, got ${res.size}")
            }
            p.clear()
        }

        // Case 3 (Required #3): 2 MB attribute + [x*="a...(20k)b"] / [x*="a...(250)b"] / 32x[x*=b i], 1M-token class + .z
        run {
            val p = PluginDomParser()
            val attr2Mb = "<p x=\"" + "a".repeat(2_000_000) + "\"></p>"
            val rAttr = p.parseHtml(attr2Mb)
            // 20k value rejected at parse (> 256 chars)
            assertFastUnder1s("R3_attr_value_20k_rejected") {
                try {
                    p.selectAllIds(rAttr, "[x*=\"" + "a".repeat(20_000) + "b\"]")
                    throw AssertionError("20k attr value must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Legal 251-char needle against 2 MB attr with 1s deadline -> budgeted KMP throws DomLimitExceededException in < 1s
            val needle251 = "[x*=\"" + "a".repeat(250) + "b\"]"
            p.setCallDeadlineNanos(System.nanoTime() + 1_000_000_000L)
            assertFastUnder1s("R3_attr_2mb_kmp_budgeted") {
                try {
                    p.selectAllIds(rAttr, needle251)
                    throw AssertionError("2 MB attr KMP scan must hit 2M step budget")
                } catch (_: DomLimitExceededException) {
                }
            }
            // 32 groups of case-insensitive [x*=b i] on 2 MB attr -> hits 2M step budget in < 1s (zero 2MB lowercase copies!)
            val ci32 = (1..32).joinToString(",") { "p[x*=b i]" }
            assertFastUnder1s("R3_attr_2mb_ci_32groups") {
                try {
                    p.selectAllIds(rAttr, ci32)
                    throw AssertionError("2 MB attr x 32 [x*=b i] must hit 2M step budget")
                } catch (_: DomLimitExceededException) {
                }
            }
            p.clear()

            // 1M-token class + 3000 groups `.z` (rejected > 32 groups) and 32 groups `.z` (cached once + step budgeted)
            val cls1M = "<p class=\"" + "a ".repeat(1_000_000) + "\"></p>"
            val rCls = p.parseHtml(cls1M)
            assertFastUnder1s("R3_class_3000_groups_rejected") {
                try {
                    p.selectAllIds(rCls, (1..3000).joinToString(",") { ".z" })
                    throw AssertionError("3000 comma groups must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            assertFastUnder1s("R3_class_1m_tokens_32groups") {
                try {
                    val res = p.selectAllIds(rCls, (1..32).joinToString(",") { ".z" })
                    if (res.size != 0) throw AssertionError("Expected 0 matches")
                } catch (_: DomLimitExceededException) {
                    // Acceptable when 2M char scan hits 2M step budget
                }
            }
            // 500k-token class (1 MB) + 32 groups `.z`: tokenizes ONCE, caches Set, completes cleanly in < 1s!
            p.clear()
            val rCls500k = p.parseHtml("<p class=\"" + "a ".repeat(500_000) + "\"></p>")
            assertFastUnder1s("R3_class_500k_tokens_cached_32groups") {
                val res = p.selectAllIds(rCls500k, (1..32).joinToString(",") { ".z" })
                if (res.size != 0) throw AssertionError("Expected 0 matches")
            }
            p.clear()
        }

        // Case 4 (Required #4): 190k <i> + select('*') looped 40x -> deduplicated handles, zero OOM under -Xmx256m;
        // plus 8 live docs / 400k live elements / doc.close()
        run {
            val p = PluginDomParser()
            val root = p.parseHtml(html190k)
            val firstIds = p.selectAllIds(root, "*")
            val expectedHandles = p.currentHandleCount()
            if (expectedHandles != 190_005) {
                throw AssertionError("Expected 190005 handles (html,head,body,div,190k*i + #document), got $expectedHandles")
            }
            // Repeat 40x (previously OOMed at 34x!) -> handles stay strictly deduplicated
            for (iter in 0 until 40) {
                val t0 = System.nanoTime()
                val curIds = p.selectAllIds(root, "*")
                val ms = (System.nanoTime() - t0) / 1_000_000L
                if (ms >= 1000L) throw AssertionError("select('*') iter #$iter took ${ms}ms >= 1000ms")
                if (curIds.size != firstIds.size) throw AssertionError("Match count drifted")
                if (p.currentHandleCount() != expectedHandles) {
                    throw AssertionError("Handle leak at iter #$iter: ${p.currentHandleCount()} != $expectedHandles")
                }
            }
            println("OK [R4_dedup_190k_select_star_40x] handles=$expectedHandles")

            // Live total element cap = 400k across live docs + doc.close() reclamation
            val root2 = p.parseHtml(html190k) // 190,004 * 2 = 380,008 <= 400,000
            if (p.currentLiveDocCount() != 2 || p.currentTotalNodes() != 380_008) {
                throw AssertionError("Expected 2 live docs / 380008 nodes, got ${p.currentLiveDocCount()} / ${p.currentTotalNodes()}")
            }
            val html50k = "<div>" + "<i></i>".repeat(50_000) + "</div>"
            assertFastUnder1s("R4_live_400k_cap_enforced") {
                try {
                    p.parseHtml(html50k)
                    throw AssertionError("380k + 50k > 400k live cap must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Closing doc #1 immediately reclaims its 190,004 elements and handles!
            if (!p.closeDoc(root)) throw AssertionError("closeDoc(root) returned false")
            if (p.currentLiveDocCount() != 1 || p.currentTotalNodes() != 190_004) {
                throw AssertionError("After closeDoc: docs=${p.currentLiveDocCount()} nodes=${p.currentTotalNodes()}")
            }
            val root3 = p.parseHtml(html50k) // 190,004 + 50,004 = 240,008 <= 400,000 -> succeeds!
            if (p.currentLiveDocCount() != 2 || p.currentTotalNodes() != 240_008) {
                throw AssertionError("After reclaim+parse: docs=${p.currentLiveDocCount()} nodes=${p.currentTotalNodes()}")
            }
            p.closeDoc(root2)
            p.closeDoc(root3)
            if (p.currentLiveDocCount() != 0 || p.currentTotalNodes() != 0 || p.currentHandleCount() != 0) {
                throw AssertionError("All closed docs must release to 0")
            }
            println("OK [R4_live_400k_and_doc_close]")
        }

        // Case 5 (Small #7 & #9): :nth-* overflow rejection + identical exception class for step & memory budgets
        run {
            val p = PluginDomParser()
            val root = p.parseHtml("<ul><li>1</li><li>2</li></ul>")
            val badNths = listOf(
                "li:nth-child(n-2147483648)",
                "li:nth-child(2n-2147483647)",
                "li:nth-child(-2147483648n+3)",
                "li:nth-child(2147483648)",
                "li:nth-child(1000001)",
                "li:nth-child(-1000001)",
                "li:nth-child(99999999999999999999n+1)",
            )
            assertFastUnder1s("S7_nth_overflow_rejected") {
                for (sel in badNths) {
                    try {
                        p.selectAllIds(root, sel)
                        throw AssertionError("Expected rejection for $sel")
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
            // Small #9: Step budget & memory budget throw the EXACT same exception class (DomLimitExceededException)
            val stepParser = PluginDomParser(maxSelectorSteps = 10)
            val rStep = stepParser.parseHtml("<ul>" + "<li></li>".repeat(50) + "</ul>")
            val stepErr = try {
                stepParser.selectAllIds(rStep, "li")
                throw AssertionError("Expected step budget error")
            } catch (e: Throwable) {
                e
            }
            val memParser = PluginDomParser(maxSelectorMemoryBytes = 1024)
            val rMem = memParser.parseHtml("<ul>" + "<li></li>".repeat(200) + "</ul>")
            val memErr = try {
                memParser.selectAllIds(rMem, "li ~ li")
                throw AssertionError("Expected memory budget error")
            } catch (e: Throwable) {
                e
            }
            if (stepErr.javaClass != DomLimitExceededException::class.java ||
                memErr.javaClass != DomLimitExceededException::class.java
            ) {
                throw AssertionError("Budget error types diverged: step=${stepErr.javaClass}, mem=${memErr.javaClass}")
            }
            println("OK [S9_same_budget_error_class]")
        }

        println("ROUND6_ALL_PASSED")
        kotlin.system.exitProcess(0)
    }
}

/**
 * Isolated `-Xmx192m` child JVM entry point invoked by
 * [CtxApiAndRecoveryTest.blocksDomFormattingAmplificationUnderConstrainedHeapAndEnforcesFetchRequestLimitsAndAtomicTimers]
 * to prove the exact 1.52 MB HTML5 formatting-element amplification bomb throws
 * [DomLimitExceededException] rather than [OutOfMemoryError].
 */
object DomAmplificationSubprocessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val sb = StringBuilder("<div>")
        for (i in 0 until 100) sb.append("<b id=").append(i).append(">")
        sb.append("</div>")
        repeat(190_000) { sb.append("<p>x</p>") }
        val payload = sb.toString()

        val rt = Runtime.getRuntime()
        System.gc()
        Thread.sleep(30)
        val before = rt.totalMemory() - rt.freeMemory()
        // Reset JVM heap MemoryPool peak counters right before parsing so we measure true peak DURING parse
        val heapPools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }
        heapPools.forEach { runCatching { it.resetPeakUsage() } }

        val parser = PluginDomParser()
        try {
            parser.parseHtml(payload)
            System.err.println("UNEXPECTED_SUCCESS nodes=${parser.currentTotalNodes()}")
            kotlin.system.exitProcess(2)
        } catch (e: DomLimitExceededException) {
            val peakHeapBytes = heapPools.sumOf { it.peakUsage?.used ?: 0L }
            val peakDeltaMb = ((peakHeapBytes - before).coerceAtLeast(0L)) / (1024L * 1024L)
            System.gc()
            Thread.sleep(30)
            val after = rt.totalMemory() - rt.freeMemory()
            val deltaMb = (after - before) / (1024L * 1024L)
            if (parser.currentTotalNodes() != 0) {
                System.err.println("NONZERO_NODES=${parser.currentTotalNodes()}")
                kotlin.system.exitProcess(3)
            }
            println("DOM_LIMIT_THROWN PEAK_DELTA_MB=$peakDeltaMb HEAP_DELTA_MB=$deltaMb MSG=${e.message}")
            kotlin.system.exitProcess(0)
        }
    }
}
