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
}
