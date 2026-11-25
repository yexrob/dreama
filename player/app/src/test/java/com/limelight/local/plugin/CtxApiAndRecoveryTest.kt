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
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            id = "dev.dreama.ctxtest",
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

            assertTrue(loggedLines.any { it.startsWith("depth3d: plugin dev.dreama.ctxtest") })
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
            id = "dev.dreama.b2mem",
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
            id = "dev.dreama.s345",
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
            val manifest = PluginManifest(api = 1, id = "dev.dreama.corrupt", name = "Corrupt", version = "1.0.0")
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
            id = "dev.dreama.recovery",
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
            pluginId = "dev.dreama.r3",
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
            id = "dev.dreama.r3",
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
                  ctx.parse.html('<i>x'.repeat(300000));
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

            // 5. Item #3d & Round 7/8 #1: ctx.parse > 32 MB estimated DOM memory rejected mid-parse
            val usedBeforeDomFlood = settledUsedBytes()
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("dom-node-flood"))))
                fail("DOM exceeding 32 MB single-doc memory budget must be rejected")
            } catch (_: PluginInternalException) {
            }
            val domFloodDelta = settledUsedBytes() - usedBeforeDomFlood
            assertTrue(
                "Rejected DOM flood must not grow host heap by > 16 MB, got ${domFloodDelta / (1024 * 1024)} MB",
                domFloodDelta < 16L * 1024 * 1024,
            )
            val directDom = PluginDomParser()
            try {
                directDom.parseHtml("<i>x".repeat(300_000))
                fail("Direct PluginDomParser must reject > 32 MB DOM mid-parse")
            } catch (_: DomLimitExceededException) {
            } finally {
                directDom.clear()
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
        val logger = PluginLogger("dev.dreama.r4", "inst-r4", sink = { _, _ -> })
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
            id = "dev.dreama.r4",
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
                    op == "children" -> parser.childrenIds(nodeId).joinToString(",") { parser.tag(it.jsonPrimitive.long) }
                    op.startsWith("attr:") -> parser.attr(nodeId, op.removePrefix("attr:"))
                    op.startsWith("all:") -> parser.selectAllIds(nodeId, op.removePrefix("all:"))
                        .joinToString("|") { el ->
                            val cid = el.jsonPrimitive.long
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

        // 1b. Round 9 & 10: Run ALL shared estimatedBytesVectors from dom-vectors.json
        //     asserting Android & plugin-kit agree on estimated DOM bytes within ±5%.
        val estVectors = vecRoot["estimatedBytesVectors"]!!.jsonArray
        assertTrue("Expected at least 24 shared estimatedBytesVectors", estVectors.size >= 24)
        for (evEl in estVectors) {
            val ev = evEl.jsonObject
            val name = ev["name"]!!.jsonPrimitive.content
            val mode = ev["mode"]!!.jsonPrimitive.content
            val input = ev["input"]?.jsonPrimitive?.content ?: buildString {
                append(ev["prefix"]?.jsonPrimitive?.content ?: "")
                val unit = ev["unit"]?.jsonPrimitive?.content ?: ""
                val rep = ev["repeat"]?.jsonPrimitive?.int ?: 1
                repeat(rep) { append(unit) }
                append(ev["suffix"]?.jsonPrimitive?.content ?: "")
            }
            val expectedBytes = ev["expectedBytes"]!!.jsonPrimitive.long
            val tolPct = ev["tolerancePct"]?.jsonPrimitive?.content?.toDouble() ?: 5.0
            val p = PluginDomParser()
            try {
                if (mode == "xml") p.parseXml(input) else p.parseHtml(input)
                val actualBytes = p.currentEstimatedDomBytes()
                val relDiffPct = kotlin.math.abs(actualBytes - expectedBytes).toDouble() * 100.0 / expectedBytes.toDouble()
                assertTrue(
                    "estimatedBytesVector '$name': actual=$actualBytes expected=$expectedBytes diff=${"%.2f".format(relDiffPct)}% > $tolPct%",
                    relDiffPct <= tolPct,
                )
            } finally {
                p.clear()
            }
        }

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

        // 3. Round 5/8: Exact post-parse recount catches adoption-agency expansion (> 32 MB) + unified count excluding #document
        run {
            val p = PluginDomParser()
            try {
                p.parseHtml("<b><p>x</b>y</p>".repeat(130_000))
                fail("130,000x <b><p>x</b>y</p> (~390k actual elements > 32 MB) must be rejected")
            } catch (_: DomLimitExceededException) {
            }
            assertEquals(0, p.currentTotalNodes())

            val pCount = PluginDomParser()
            pCount.parseHtml("<p>x</p>")
            assertEquals("HTML <p>x</p> has 4 elements (html,head,body,p) excluding #document", 4, pCount.currentTotalNodes())
            pCount.parseXml("<r><a/></r>")
            assertEquals("XML <r><a/></r> adds 2 elements (r,a) excluding #document", 6, pCount.currentTotalNodes())
            pCount.clear()
        }

        // 4. Round 5 Item #5: fetch method/redirect length pre-checks + 8 MB queued+active request body cap
        val tmpDir = Files.createTempDirectory("d3-r5-fetch").toFile()
        val secretBox = SoftwareAesSecretBox()
        val logger = PluginLogger("dev.dreama.r5", "inst-r5", sink = { _, _ -> })
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
            id = "dev.dreama.r5",
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
        val logger = PluginLogger("dev.dreama.r6", "inst-r6", sink = { _, _ -> })
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val manifest = PluginManifest(api = 1, id = "dev.dreama.r6", name = "R6", version = "1.0.0")
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

    @Test
    fun round7CalibratesDomByteEstimatorEnforcesOutputCapsEmptyCachingClass200kLongIdsAnd30sSoakUnderXmx256m() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")

        // 1. Calibration probe: across 14 distinct document shapes (pure elements, elements+text,
        //    unclosed formatting elements, PIs, bogus comments, comments, text+comments, many attrs,
        //    long attr, long text, deep nesting, classed elements, XML PI, XML CDATA), estimated bytes
        //    MUST be >= 80% of real JVM retained heap.
        val calibProc = ProcessBuilder(
            javaBin,
            "-Xmx512m",
            "-cp",
            classpath,
            Round7CalibrationSubprocessMain::class.java.name,
        ).redirectErrorStream(true).start()
        val calibOut = calibProc.inputStream.bufferedReader().readText()
        val calibExit = calibProc.waitFor()
        assertEquals("Round 7 DOM byte estimator calibration MUST pass:\n$calibOut", 0, calibExit)
        assertTrue("Calibration probe must report CALIBRATION_PASSED:\n$calibOut", calibOut.contains("CALIBRATION_PASSED"))

        // 2. Round 7 worst-case & 30-second soak probe under strict -Xmx256m:
        //    - 2 MB input cap, 24 MB single-doc cap, 48 MB VM cap (docs + handles + class cache)
        //    - PI flood (`<?>`), text+comment flood (`x<!>`), `<i>x` x 200k, 8 docs near cap
        //    - 30s loop of parse + select('*') + outerHtml without OOM and retained heap <= 96 MB
        //    - :empty caching + budgeted childNodes/attributes loops + entry deadline checks
        //    - 8 MB html()/outerHtml()/text() serialization output cap
        //    - 200k classed elements `.a`, `i.a`, `.a.b`, `.a .b` without false-positive memory error
        //    - Monotonic Long nextId never reset across clear()
        val soakProc = ProcessBuilder(
            javaBin,
            "-Xmx256m",
            "-cp",
            classpath,
            Round7SoakAndFixesSubprocessMain::class.java.name,
        ).redirectErrorStream(true).start()
        val soakOut = soakProc.inputStream.bufferedReader().readText()
        val soakExit = soakProc.waitFor()
        assertEquals("Round 7 -Xmx256m 30s soak & fixes suite MUST pass:\n$soakOut", 0, soakExit)
        assertTrue("Soak probe must report ROUND7_SOAK_PASSED:\n$soakOut", soakOut.contains("ROUND7_SOAK_PASSED"))
    }

    @Test
    fun round8CalibratesAllR7DomShapesAndRealPagesEnforcesProcessBudgetNameCapAttrsJsonAndSibling200k() {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")

        // 1. Exhaustive Round 8 calibration across ALL 39 malicious shapes from r7dom/G.java
        //    + ALL 13 realistic pages (pages/ & pages2/, incl. 2 MB table_pretty, tracker2, table_min):
        //    - All 13 real pages MUST pass under the default 32 MB single-doc cap.
        //    - Every single shape & page MUST have `0.75 <= actual / estimated <= 1.15` both right after
        //      parse AND after `outerHtml + text` (and after `select('*')` against total VM estimate).
        val calibProc = ProcessBuilder(
            javaBin,
            "-Xmx1024m",
            "-cp",
            classpath,
            Round8CalibrationSubprocessMain::class.java.name,
        ).redirectErrorStream(true).start()
        val calibOut = calibProc.inputStream.bufferedReader().readText()
        val calibExit = calibProc.waitFor()
        assertEquals("Round 8 full 52-dataset calibration MUST pass:\n$calibOut", 0, calibExit)
        assertTrue("Round 8 calibration must report ROUND8_CALIB_PASSED:\n$calibOut", calibOut.contains("ROUND8_CALIB_PASSED"))

        // 2. Round 8 fixes + concurrent 2-VM 64 MB process-wide DOM budget stress test under -Xmx256m:
        //    - 256-char tag/attr name cap (rejected in < 100ms, incl. 2 MB tag/attr names) + cached lowercasing
        //    - Escaped attrsJson 8 MB cap (2 MB `\u0001` -> 12 MB JSON rejected cleanly)
        //    - `.a ~ .b` on 200,000 classed elements succeeds under default 16 MB query memory cap
        //    - Two concurrent VMs each trying to fill 48 MB VM cap are jointly capped at 64 MB process-wide
        //      with actual combined retained heap <= 80 MB, zero OOM under -Xmx256m, and full return to 0
        val r8Proc = ProcessBuilder(
            javaBin,
            "-Xmx256m",
            "-cp",
            classpath,
            Round8ProcessBudgetAndFixesSubprocessMain::class.java.name,
        ).redirectErrorStream(true).start()
        val r8Out = r8Proc.inputStream.bufferedReader().readText()
        val r8Exit = r8Proc.waitFor()
        assertEquals("Round 8 -Xmx256m process-budget & fixes suite MUST pass:\n$r8Out", 0, r8Exit)
        assertTrue("Round 8 fixes must report ROUND8_FIXES_PASSED:\n$r8Out", r8Out.contains("ROUND8_FIXES_PASSED"))
    }

    @Test
    fun round10VerifiesDeterministicEstimationAndFailClosedJsoupReflection(): Unit = runBlocking {
        // 1. Round 10 Item #1: Estimation must be 100% deterministic across ANY JVM identityHashCode history.
        //    Consume k identity hash codes (k in 0, 287, 539, 616, 896, 1337, 2048) before parsing & estimating
        //    EVERY shared estimatedBytesVector in dom-vectors.json plus a 2 MB DASH mpd_list.xml document, and
        //    assert byte-for-byte identical estimates across every k.
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val vectorsFile = File(repoRoot, "docs/specs/plugin-schema/dom-vectors.json")
        val vecRoot = kotlinx.serialization.json.Json.parseToJsonElement(vectorsFile.readText()).jsonObject
        val estVectors = vecRoot["estimatedBytesVectors"]!!.jsonArray
        assertTrue("Expected at least 24 shared estimatedBytesVectors", estVectors.size >= 24)

        data class VecInput(val name: String, val isXml: Boolean, val input: String, val expectedBytes: Long?)
        val inputs = ArrayList<VecInput>(estVectors.size + 1)
        for (evEl in estVectors) {
            val ev = evEl.jsonObject
            val name = ev["name"]!!.jsonPrimitive.content
            val isXml = ev["mode"]!!.jsonPrimitive.content == "xml"
            val input = ev["input"]?.jsonPrimitive?.content ?: buildString {
                append(ev["prefix"]?.jsonPrimitive?.content ?: "")
                val unit = ev["unit"]?.jsonPrimitive?.content ?: ""
                val rep = ev["repeat"]?.jsonPrimitive?.int ?: 1
                repeat(rep) { append(unit) }
                append(ev["suffix"]?.jsonPrimitive?.content ?: "")
            }
            inputs.add(VecInput(name, isXml, input, ev["expectedBytes"]!!.jsonPrimitive.long))
        }
        // Add 2 MB mpd_list.xml (which previously fluctuated up to +42.7% under System.identityHashCode collisions)
        val mpdList = buildString(2 * 1024 * 1024) {
            val head = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n<Period><AdaptationSet><Representation id=\"1\" bandwidth=\"1000\"><SegmentList duration=\"2\">\n"
            val tail = "</SegmentList></Representation></AdaptationSet></Period></MPD>\n"
            val lim = 2 * 1024 * 1024 - 256
            append(head)
            var bytes = head.length + tail.length
            var idx = 0
            while (true) {
                val row = "<SegmentURL media=\"seg-${idx++}.m4s\"/>\n"
                if (bytes + row.length > lim) break
                append(row)
                bytes += row.length
            }
            append(tail)
        }
        inputs.add(VecInput("mpd_list_2mb_xml", true, mpdList, null))

        val hashOffsets = listOf(0, 287, 539, 616, 896, 1337, 2048)
        val baseline = LinkedHashMap<String, Long>()
        for (k in hashOffsets) {
            var sink = 0
            repeat(k) { sink = sink xor Any().hashCode() }
            assertTrue(sink != Int.MIN_VALUE || k >= 0) // keep hash calls live
            for (v in inputs) {
                val p = PluginDomParser()
                try {
                    if (v.isXml) p.parseXml(v.input) else p.parseHtml(v.input)
                    val est = p.currentEstimatedDomBytes()
                    if (k == 0) {
                        baseline[v.name] = est
                        if (v.expectedBytes != null) {
                            assertEquals(
                                "Baseline deterministic estimate mismatch for '${v.name}'",
                                v.expectedBytes,
                                est,
                            )
                        }
                    } else {
                        assertEquals(
                            "Estimate for '${v.name}' drifted after consuming k=$k identity hashes",
                            baseline[v.name],
                            est,
                        )
                    }
                } finally {
                    p.clear()
                }
            }
        }
        assertEquals(0L, PluginDomParser.currentProcessDomMemoryBytes())

        // 2. Round 10 Item #2: Verify ALL 7 required Jsoup internal reflection fields are non-null under
        //    the current Jsoup version (upgrading Jsoup MUST pass this test first!).
        assertNotNull("Jsoup Attributes.size field must be accessible", PluginDomParser.JSOUP_ATTRS_SIZE_FIELD)
        assertNotNull("Jsoup Attributes.keys field must be accessible", PluginDomParser.JSOUP_ATTRS_KEYS_FIELD)
        assertNotNull("Jsoup Element.attributes field must be accessible", PluginDomParser.JSOUP_EL_ATTRS_FIELD)
        assertNotNull("Jsoup Element.childNodes field must be accessible", PluginDomParser.JSOUP_EL_CHILDS_FIELD)
        assertNotNull("Jsoup Element.EmptyNodeList field must be accessible", PluginDomParser.JSOUP_EMPTY_NODE_LIST)
        assertNotNull("Jsoup LeafNode.value field must be accessible", PluginDomParser.JSOUP_LEAF_VALUE_FIELD)
        assertNotNull("Jsoup FormElement.linkedEls field must be accessible", PluginDomParser.JSOUP_FORM_LINKED_FIELD)
        assertTrue(
            "Expected zero missing Jsoup fields, found: ${PluginDomParser.JSOUP_MISSING_FIELDS}",
            PluginDomParser.JSOUP_MISSING_FIELDS.isEmpty(),
        )
        assertTrue("JSOUP_REFLECTION_READY must be true", PluginDomParser.JSOUP_REFLECTION_READY)

        // 3. Round 10 Item #2: Inject simulated missing field for EACH of the 7 fields and assert parseHtml,
        //    parseXml, and estimateDocumentBytes fail closed with DomHostIncompatibleException (never silently
        //    returning an underestimated document or leaking ledger bytes).
        val requiredFields = listOf(
            "Attributes.size",
            "Attributes.keys",
            "Element.attributes",
            "Element.childNodes",
            "Element.EmptyNodeList",
            "LeafNode.value",
            "FormElement.linkedEls",
        )
        val probeDoc = org.jsoup.Jsoup.parse("<p a a>x</p>")
        for (fieldName in requiredFields) {
            val p = PluginDomParser()
            PluginDomParser.simulatedMissingJsoupFieldForTesting = fieldName
            try {
                try {
                    p.parseHtml("<p a a a>x</p>")
                    fail("parseHtml must fail closed when $fieldName is missing")
                } catch (e: DomHostIncompatibleException) {
                    assertTrue(
                        "Error message must name missing field '$fieldName': ${e.message}",
                        e.message?.contains(fieldName) == true,
                    )
                }
                try {
                    p.parseXml("<r><p a='1' a='1'/></r>")
                    fail("parseXml must fail closed when $fieldName is missing")
                } catch (e: DomHostIncompatibleException) {
                    assertTrue(
                        "Error message must name missing field '$fieldName': ${e.message}",
                        e.message?.contains(fieldName) == true,
                    )
                }
                try {
                    PluginDomParser.estimateDocumentBytes(probeDoc)
                    fail("estimateDocumentBytes must fail closed when $fieldName is missing")
                } catch (e: DomHostIncompatibleException) {
                    assertTrue(
                        "Error message must name missing field '$fieldName': ${e.message}",
                        e.message?.contains(fieldName) == true,
                    )
                }
                assertEquals(0, p.currentLiveDocCount())
                assertEquals(0L, p.currentVmMemoryBytes())
                assertEquals(0L, PluginDomParser.currentProcessDomMemoryBytes())
            } finally {
                PluginDomParser.simulatedMissingJsoupFieldForTesting = null
                p.clear()
            }
        }

        // End-to-end through QuickJS PluginRuntime: simulated missing Jsoup field surfaces as PluginInternalException
        val tmpDir = Files.createTempDirectory("plugin-r10-reflect-").toFile()
        val secretBox = SoftwareAesSecretBox()
        val logger = PluginLogger("dev.dreama.r10reflect", "inst-r10-reflect", sink = { _, _ -> })
        val storage = InstanceStorage(File(tmpDir, "storage.enc"), secretBox)
        val cookieJar = PluginCookieJar(File(tmpDir, "cookies.enc"), secretBox)
        val manifest = PluginManifest(
            api = 1,
            id = "dev.dreama.r10reflect",
            name = "R10Reflect",
            version = "1.0.0",
            permissions = PluginPermissions(hosts = listOf("api.example.com")),
        )
        val runtime = PluginRuntime(
            manifest = manifest,
            instanceId = "inst-r10-reflect",
            mainJsSource = """
                export default {
                  home() { return []; },
                  browse(ctx) {
                    const d = ctx.parse.html('<p a a>hello</p>');
                    return { text: d.querySelector('p').text };
                  },
                  detail() { return { id: '1', kind: 'video', title: '1' }; },
                  playback() { return { url: 'https://cdn.example.com/a.mp4' }; },
                };
            """.trimIndent(),
            config = emptyMap(),
            fetchGateway = PluginFetchGateway(NetworkPolicy(manifest.permissions), cookieJar),
            storage = storage,
            cookieJar = cookieJar,
            logger = logger,
        )
        try {
            PluginDomParser.simulatedMissingJsoupFieldForTesting = "Attributes.keys"
            try {
                runtime.invoke("browse", JsonObject(emptyMap()))
                fail("ctx.parse.html must reject with PluginInternalException when Jsoup reflection fails")
            } catch (_: PluginInternalException) {
            } finally {
                PluginDomParser.simulatedMissingJsoupFieldForTesting = null
            }
            // Recovers immediately once field is available
            val ok = runtime.invoke("browse", JsonObject(emptyMap()))
            assertEquals("hello", ok.jsonObject["text"]!!.jsonPrimitive.content)
        } finally {
            PluginDomParser.simulatedMissingJsoupFieldForTesting = null
            runtime.close()
            tmpDir.deleteRecursively()
        }
    }
}

/**
 * Isolated `-Xmx1024m` child JVM calibrating [PluginDomParser] against real JVM
 * retained heap across all 39 synthetic shapes (`r7dom/G.java`) and all 13 realistic
 * pages (`r7dom/pages/` and `r7dom/pages2/`), asserting:
 * - All 13 real pages pass under the default 32 MB single-doc limit.
 * - `0.75 <= actual / estimated <= 1.15` right after parse AND after `outerHtml + text`,
 *   and after `select('*')` against total VM memory (`vmDocBytes + vmHandleBytes`).
 */
object Round8CalibrationSubprocessMain {
    private const val MAX_GEN = 2 * 1024 * 1024 - 64

    private fun retainedBytes(): Long {
        val mem = java.lang.management.ManagementFactory.getMemoryMXBean()
        repeat(4) {
            System.gc()
            Thread.sleep(12)
        }
        return mem.heapMemoryUsage.used
    }

    private fun fill(pre: String, unit: String, post: String, f: Double): String {
        val n = (((MAX_GEN - pre.length - post.length) / unit.length) * f).toInt().coerceAtLeast(0)
        return pre + unit.repeat(n) + post
    }

    private fun buildPage(limitBytes: Int, head: String, row: (Int) -> String, tail: String): String {
        val sb = StringBuilder(limitBytes + 1024)
        sb.append(head)
        var n = head.toByteArray(Charsets.UTF_8).size + tail.toByteArray(Charsets.UTF_8).size
        var i = 0
        while (true) {
            val r = row(i++)
            val b = r.toByteArray(Charsets.UTF_8).size
            if (n + b > limitBytes) break
            sb.append(r)
            n += b
        }
        sb.append(tail)
        return sb.toString()
    }

    private fun genShape(k: String, f: Double): String = when (k) {
        "pi" -> fill("", "<?>", "", f)
        "bogus" -> fill("", "<!>", "", f)
        "comment" -> fill("", "<!---->", "", f)
        "textcmt" -> fill("", "x<!>", "", f)
        "elem" -> fill("<div>", "<p></p>", "</div>", f)
        "elemshort" -> fill("", "<i>", "", f)
        "elemtext" -> fill("<div>", "<p>x</p>", "</div>", f)
        "ws" -> fill("<div>", "<p> </p>", "</div>", f)
        "attr1" -> fill("<div>", "<p a=b></p>", "</div>", f)
        "attrnull" -> fill("<div>", "<p a></p>", "</div>", f)
        "manyattr" -> fill("<div>", "<p a b c d e f g h i j k l m n o q r s t u v w x y z></p>", "</div>", f)
        "manyattr3" -> buildString {
            append("<div>")
            var i = 0
            while (length < MAX_GEN * f - 200) {
                append("<p")
                for (j in 0 until 40) append(' ').append(Integer.toString(i++, 36))
                append("></p>")
            }
            append("</div>")
        }
        "uniqattr" -> buildString {
            append("<p")
            var i = 0
            while (length < MAX_GEN * f - 50) append(" a").append(Integer.toString(i++, 36))
            append("></p>")
        }
        "longattrval" -> fill("<div>", "<p a=" + "v".repeat(1000) + "></p>", "</div>", f)
        "hugeattrval" -> "<p a='" + "v".repeat((MAX_GEN * f).toInt() - 20) + "'></p>"
        "uniqtag" -> buildString {
            append("<div>")
            var i = 0
            while (length < MAX_GEN * f - 40) {
                val t = "t" + Integer.toString(i++, 36)
                append('<').append(t).append("></").append(t).append('>')
            }
            append("</div>")
        }
        "uniqtagopen" -> buildString {
            append("<div>")
            var i = 0
            while (length < MAX_GEN * f - 40) {
                append("<t").append(Integer.toString(i++, 36)).append("/>")
            }
            append("</div>")
        }
        "deep" -> buildString {
            while (length < MAX_GEN * f - 4000) {
                append("<div>".repeat(500)).append("</div>".repeat(500))
            }
        }
        "deeptext" -> buildString {
            while (length < MAX_GEN * f - 6000) {
                append("<div>x".repeat(500)).append("</div>".repeat(500))
            }
        }
        "entity" -> fill("<p>", "&lt;<!>", "</p>", f)
        "entity2" -> fill("<p>", "&NotNestedGreaterGreater;<!>", "</p>", f)
        "cjk" -> fill("<p>", "中<!>", "</p>", f)
        "fostercmt" -> fill("<table><b>", "<!>", "", f)
        "fostertext" -> fill("<table>", "x<b></b>", "", f)
        "fosterpi" -> fill("<table><b>", "x<!>", "", f)
        "script" -> fill("<script>", "x", "</script>", f)
        "scripts" -> fill("", "<script>x</script>", "", f)
        "xpi" -> fill("<r>", "<?a?>", "</r>", f)
        "xpiattr" -> fill("<r>", "<?a b c d e f g h?>", "</r>", f)
        "xdoctype" -> fill("<r>", "<!DOCTYPE a>", "</r>", f)
        "xcdata" -> fill("<r>", "<![CDATA[]]>", "</r>", f)
        "xcdata1" -> fill("<r>", "<![CDATA[x]]>", "</r>", f)
        "xelem" -> fill("<r>", "<a/>", "</r>", f)
        "xtextcmt" -> fill("<r>", "x<!---->", "</r>", f)
        "xuniqtag" -> buildString {
            append("<r>")
            var i = 0
            while (length < MAX_GEN * f - 40) append("<t").append(Integer.toString(i++, 36)).append("/>")
            append("</r>")
        }
        "xcase" -> buildString {
            append("<r>")
            var i = 0
            while (length < MAX_GEN * f - 40) append("<T").append(Integer.toString(i++, 36)).append(" A=b/>")
            append("</r>")
        }
        "xent" -> fill("<r>", "&a;<!---->", "</r>", f)
        "xattr" -> fill("<r>", "<a b='c' d='e'/>", "</r>", f)
        "xns" -> fill("<r>", "<n:a xmlns:n='u'/>", "</r>", f)
        // Round 9 Item #1: duplicate attributes (HTML & XML & XmlDeclaration), moved children, FormElement
        "dup7" -> fill("<div>", "<p" + " a".repeat(7) + "></p>", "</div>", f)
        "dup13" -> fill("<div>", "<p" + " a".repeat(13) + "></p>", "</div>", f)
        "dup49" -> fill("<div>", "<p" + " a".repeat(49) + "></p>", "</div>", f)
        "dup97" -> fill("<div>", "<p" + " a".repeat(97) + "></p>", "</div>", f)
        "dup385" -> fill("<div>", "<p" + " a".repeat(385) + "></p>", "</div>", f)
        "dup97b" -> fill("", "<b" + " a".repeat(97) + ">", "", f)
        "dup97i" -> fill("<div>", "<i" + " a".repeat(97) + ">", "</div>", f)
        "xdup7" -> fill("<r>", "<p" + " a='1'".repeat(7) + "/>", "</r>", f)
        "xdup13" -> fill("<r>", "<p" + " a='1'".repeat(13) + "/>", "</r>", f)
        "xdup49" -> fill("<r>", "<p" + " a='1'".repeat(49) + "/>", "</r>", f)
        "xdup97" -> fill("<r>", "<p" + " a='1'".repeat(97) + "/>", "</r>", f)
        "xdup385" -> fill("<r>", "<p" + " a='1'".repeat(385) + "/>", "</r>", f)
        "xdecl_dup385" -> fill("<r>", "<?xml" + " a='1'".repeat(385) + "?>", "</r>", f)
        "adopt_moved_500" -> fill("", "<b><div>" + "<br>".repeat(500) + "</b></div>", "", f * 0.5)
        "adopt" -> fill("", "<b><p>x</b>y</p>", "", f)
        "many_forms" -> "<form><input></form>".repeat((80_000 * f).toInt().coerceAtLeast(1))
        "form_inputs" -> "<form>" + "<input>".repeat((200_000 * f).toInt().coerceAtLeast(1)) + "</form>"
        else -> throw IllegalArgumentException("unknown shape $k")
    }

    private fun exerciseSerialize(p: PluginDomParser, root: Long): Int {
        var acc = 0
        runCatching { acc += p.outerHtml(root).length }
        runCatching { acc += p.text(root).length }
        return acc
    }

    private fun exerciseSelectAll(p: PluginDomParser, root: Long): Int =
        p.selectAllIds(root, "*").size

    private fun checkDataset(name: String, input: String, isXml: Boolean, requireDefaultAccept: Boolean) {
        val p = PluginDomParser()
        val b0 = retainedBytes()
        val root = try {
            if (isXml) p.parseXml(input) else p.parseHtml(input)
        } catch (e: DomLimitExceededException) {
            if (requireDefaultAccept) {
                throw AssertionError("Normal page '$name' was rejected under 32 MB default cap: ${e.message}")
            }
            throw e
        }
        val r1 = retainedBytes()
        val est1 = p.currentVmMemoryBytes()
        val real1 = (r1 - b0).coerceAtLeast(1L)
        val ratio1 = real1.toDouble() / est1.toDouble()

        // Exercise outerHtml + text in a separate stack frame so their transient return Strings
        // are NOT pinned in local variable slots during retainedBytes() measurement!
        exerciseSerialize(p, root)
        val r2 = retainedBytes()
        val real2 = (r2 - b0).coerceAtLeast(1L)
        val ratio2 = real2.toDouble() / est1.toDouble()

        // Also verify post select('*') when it fits inside 48 MB VM budget
        var ratio3Str = "n/a"
        var ratio3 = ratio2
        try {
            exerciseSelectAll(p, root)
            val r3 = retainedBytes()
            val est3 = p.currentVmMemoryBytes()
            val real3 = (r3 - b0).coerceAtLeast(1L)
            ratio3 = real3.toDouble() / est3.toDouble()
            ratio3Str = String.format(java.util.Locale.ROOT, "%.3f", ratio3)
        } catch (_: DomLimitExceededException) {
            // Expected if doc + all handles > 48 MB VM cap
        }

        println(
            String.format(
                java.util.Locale.ROOT,
                "R8CALIB %-24s real=%.2fMB est=%.2fMB ratio=%.3f postSer=%.3f postSel=%s",
                name,
                real1 / 1048576.0,
                est1 / 1048576.0,
                ratio1,
                ratio2,
                ratio3Str,
            ),
        )
        p.clear()

        // Skip noisy tiny < 256 KB docs (e.g. uniqattr = 30 KB where GC jitter is ±20 KB)
        if (est1 >= 256L * 1024L) {
            if (ratio1 < 0.75 || ratio1 > 1.15) {
                throw AssertionError("Shape '$name' parse real/est=$ratio1 outside [0.75, 1.15] (real=$real1 est=$est1)")
            }
            if (ratio2 < 0.75 || ratio2 > 1.15) {
                throw AssertionError("Shape '$name' post-outerHtml/text real/est=$ratio2 outside [0.75, 1.15]")
            }
            if (ratio3Str != "n/a" && (ratio3 < 0.75 || ratio3 > 1.15)) {
                throw AssertionError("Shape '$name' post-select('*') real/est=$ratio3 outside [0.75, 1.15]")
            }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Warm up parser & serialization code paths
        PluginDomParser().apply {
            val r = parseHtml("<p a='1'>hi<!--c--></p>")
            selectAllIds(r, "*")
            outerHtml(r)
            text(r)
            clear()
            val rx = parseXml("<r><?a b?><![CDATA[x]]><!DOCTYPE a></r>")
            outerHtml(rx)
            text(rx)
            clear()
        }

        // 1. All 39 synthetic shapes + Round 9 container-compaction shapes (dup7/13/49/97/385 HTML & XML,
        //    dup97b/dup97i, xdecl_dup385, adopt_moved_500, adopt, many_forms, form_inputs)
        val shapes = listOf(
            "pi", "bogus", "comment", "textcmt", "elem", "elemshort", "elemtext", "ws",
            "attr1", "attrnull", "manyattr", "manyattr3", "uniqattr", "longattrval", "hugeattrval",
            "uniqtag", "uniqtagopen", "deep", "deeptext", "entity", "entity2", "cjk",
            "fostercmt", "fostertext", "fosterpi", "script", "scripts",
            "xpi", "xpiattr", "xdoctype", "xcdata", "xcdata1", "xelem", "xtextcmt",
            "xuniqtag", "xcase", "xent", "xattr", "xns",
            "dup7", "dup13", "dup49", "dup97", "dup385", "dup97b", "dup97i",
            "xdup7", "xdup13", "xdup49", "xdup97", "xdup385", "xdecl_dup385",
            "adopt_moved_500", "adopt", "many_forms", "form_inputs",
        )
        for (shape in shapes) {
            val isXml = shape.startsWith("x")
            var f = 1.0
            var accepted = false
            for (tries in 0 until 10) {
                val inp = genShape(shape, f)
                try {
                    checkDataset(shape, inp, isXml, requireDefaultAccept = false)
                    accepted = true
                    break
                } catch (_: DomLimitExceededException) {
                    f *= 0.8
                }
            }
            if (!accepted) throw AssertionError("Shape '$shape' was never accepted even after downscaling")
        }

        // 2. All 13 realistic pages (exact generators from r7dom/gen_pages.mjs & gen2.mjs) — MUST ALL PASS under 32 MB!
        val lim2m = 2 * 1024 * 1024 - 256
        val words = arrayOf("alpha", "beta", "gamma", "delta", "视频", "电影", "剧集", "第一季", "HD", "1080p")
        fun w(i: Int, k: Int) = words[(i * 7 + k) % words.size]

        val realPages = listOf(
            Triple(
                "pages/table_pretty.html",
                false,
                buildPage(
                    lim2m,
                    "<!DOCTYPE html>\n<html>\n<head><title>t</title></head>\n<body>\n<table class=\"list\">\n<tbody>\n",
                    { i -> "  <tr>\n    <td>$i</td>\n    <td><a href=\"/item/$i\">${w(i, 1)} ${w(i, 2)}</a></td>\n    <td class=\"size\">${(i * 37) % 9999} MB</td>\n    <td>2024-0${i % 9 + 1}-1${i % 9}</td>\n  </tr>\n" },
                    "</tbody>\n</table>\n</body>\n</html>\n",
                ),
            ),
            Triple(
                "pages/table_min.html",
                false,
                buildPage(
                    lim2m,
                    "<html><body><table>",
                    { i -> "<tr><td>${i % 100}</td><td>${w(i, 1)}</td><td>${i % 7}</td><td>x</td></tr>" },
                    "</table></body></html>",
                ),
            ),
            Triple(
                "pages/list.html",
                false,
                buildPage(
                    lim2m,
                    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><ul class=\"items\">\n",
                    { i -> "<li class=\"item card\" data-id=\"$i\"><a class=\"title\" href=\"/v/$i.html\" title=\"${w(i, 3)}\"><img src=\"/p/$i.jpg\" alt=\"${w(i, 4)}\" loading=\"lazy\"></a><span class=\"meta\">${w(i, 5)} · ${i % 60}分钟</span><span class=\"tag\">${w(i, 6)}</span></li>\n" },
                    "</ul></body></html>",
                ),
            ),
            Triple(
                "pages/script_heavy.html",
                false,
                buildPage(
                    lim2m,
                    "<!DOCTYPE html><html><head><style>" + ".c{color:red;margin:0 auto}\n".repeat(20_000) + "</style></head><body><div id=\"__next\">",
                    { i -> if (i % 50 == 0) "<script>self.__next_f.push([1,\"${"x".repeat(4000)}\"])</script>" else "<div class=\"r\"><a href=\"/a/$i\">${w(i, 1)}</a></div>" },
                    "</div></body></html>",
                ),
            ),
            Triple(
                "pages/rss.xml",
                true,
                buildPage(
                    lim2m,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">\n<channel>\n<title>t</title>\n",
                    { i -> "  <item>\n    <title>${w(i, 1)} $i</title>\n    <link>https://example.com/p/$i</link>\n    <guid isPermaLink=\"false\">id-$i</guid>\n    <pubDate>Mon, 0${i % 9 + 1} Jan 2024 10:00:00 GMT</pubDate>\n    <enclosure url=\"https://cdn.example.com/$i.mp3\" length=\"${i * 1000}\" type=\"audio/mpeg\"/>\n    <itunes:duration>00:${i % 60}:00</itunes:duration>\n    <description><![CDATA[<p>${w(i, 2)} ${w(i, 3)}</p>]]></description>\n  </item>\n" },
                    "</channel>\n</rss>\n",
                ),
            ),
            Triple(
                "pages/mpd_timeline.xml",
                true,
                buildPage(
                    lim2m,
                    "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"dynamic\">\n<Period id=\"0\">\n<AdaptationSet mimeType=\"video/mp4\">\n<SegmentTemplate media=\"v-\$Time\$.m4s\" timescale=\"90000\">\n<SegmentTimeline>\n",
                    { i -> "          <S t=\"${i * 180000}\" d=\"180000\"/>\n" },
                    "</SegmentTimeline>\n</SegmentTemplate>\n</AdaptationSet>\n</Period>\n</MPD>\n",
                ),
            ),
            Triple(
                "pages/mpd_list.xml",
                true,
                buildPage(
                    lim2m,
                    "<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">\n<Period>\n<AdaptationSet>\n<Representation id=\"1\" bandwidth=\"1000\">\n<SegmentList duration=\"2\">\n",
                    { i -> "<SegmentURL media=\"seg-$i.m4s\"/>\n" },
                    "</SegmentList>\n</Representation>\n</AdaptationSet>\n</Period>\n</MPD>\n",
                ),
            ),
            Triple(
                "pages/article.html",
                false,
                buildPage(
                    lim2m,
                    "<html><body><article>",
                    { i -> "<p>${(w(i, 1) + " ").repeat(20)}<a href=\"#n$i\">[$i]</a> ${(w(i, 2) + " ").repeat(10)}</p>\n" },
                    "</article></body></html>",
                ),
            ),
            Triple(
                "pages2/apache1.html",
                false,
                buildPage(
                    1 * 1048576 - 256,
                    "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2 Final//EN\">\n<html><head><title>Index of /tv</title></head><body><h1>Index of /tv</h1><table>\n<tr><th valign=\"top\"><img src=\"/icons/blank.gif\" alt=\"[ICO]\"></th><th><a href=\"?C=N;O=D\">Name</a></th></tr>\n",
                    { i -> val s = i.toString().padStart(4, '0'); "<tr><td valign=\"top\"><img src=\"/icons/movie.gif\" alt=\"[VID]\"></td><td><a href=\"Show.S01E$s.1080p.WEB-DL.mkv\">Show.S01E$s.1080p.WEB-DL.mkv</a></td><td align=\"right\">2024-01-${10 + i % 18} 10:${10 + i % 50}  </td><td align=\"right\">1.${i % 9}G</td><td>&nbsp;</td></tr>\n" },
                    "</table></body></html>\n",
                ),
            ),
            Triple(
                "pages2/apache15.html",
                false,
                buildPage(
                    (1.5 * 1048576).toInt() - 256,
                    "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2 Final//EN\">\n<html><head><title>Index of /tv</title></head><body><h1>Index of /tv</h1><table>\n<tr><th valign=\"top\"><img src=\"/icons/blank.gif\" alt=\"[ICO]\"></th><th><a href=\"?C=N;O=D\">Name</a></th></tr>\n",
                    { i -> val s = i.toString().padStart(4, '0'); "<tr><td valign=\"top\"><img src=\"/icons/movie.gif\" alt=\"[VID]\"></td><td><a href=\"Show.S01E$s.1080p.WEB-DL.mkv\">Show.S01E$s.1080p.WEB-DL.mkv</a></td><td align=\"right\">2024-01-${10 + i % 18} 10:${10 + i % 50}  </td><td align=\"right\">1.${i % 9}G</td><td>&nbsp;</td></tr>\n" },
                    "</table></body></html>\n",
                ),
            ),
            Triple(
                "pages2/apache2.html",
                false,
                buildPage(
                    lim2m,
                    "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2 Final//EN\">\n<html><head><title>Index of /tv</title></head><body><h1>Index of /tv</h1><table>\n<tr><th valign=\"top\"><img src=\"/icons/blank.gif\" alt=\"[ICO]\"></th><th><a href=\"?C=N;O=D\">Name</a></th></tr>\n",
                    { i -> val s = i.toString().padStart(4, '0'); "<tr><td valign=\"top\"><img src=\"/icons/movie.gif\" alt=\"[VID]\"></td><td><a href=\"Show.S01E$s.1080p.WEB-DL.mkv\">Show.S01E$s.1080p.WEB-DL.mkv</a></td><td align=\"right\">2024-01-${10 + i % 18} 10:${10 + i % 50}  </td><td align=\"right\">1.${i % 9}G</td><td>&nbsp;</td></tr>\n" },
                    "</table></body></html>\n",
                ),
            ),
            Triple(
                "pages2/tracker2.html",
                false,
                buildPage(
                    lim2m,
                    "<html><head></head><body><table class=\"list\">\n",
                    { i -> "<tr class=\"${if (i % 2 != 0) "odd" else "even"}\">\n  <td class=\"cat\"><a href=\"/cat/5\"><img src=\"/i/tv.png\" alt=\"TV\"></a></td>\n  <td class=\"name\"><a href=\"/t/$i\" title=\"Show S01E$i 1080p\">Show S01E$i 1080p WEB-DL</a> <span class=\"tag new\">NEW</span></td>\n  <td class=\"size\">${(i % 90) / 10.0 + 1} GB</td>\n  <td class=\"seed\">${i % 300}</td>\n  <td class=\"leech\">${i % 40}</td>\n  <td class=\"date\">2024-03-${10 + i % 18}</td>\n</tr>\n" },
                    "</table></body></html>\n",
                ),
            ),
            Triple(
                "pages2/tracker2_min.html",
                false,
                buildPage(
                    lim2m,
                    "<html><head></head><body><table class=\"list\">",
                    { i -> "<tr class=\"${if (i % 2 != 0) "odd" else "even"}\"><td class=\"cat\"><a href=\"/cat/5\"><img src=\"/i/tv.png\" alt=\"TV\"></a></td><td class=\"name\"><a href=\"/t/$i\" title=\"Show S01E$i 1080p\">Show S01E$i 1080p WEB-DL</a> <span class=\"tag new\">NEW</span></td><td class=\"size\">${(i % 90) / 10.0 + 1} GB</td><td class=\"seed\">${i % 300}</td><td class=\"leech\">${i % 40}</td><td class=\"date\">2024-03-${10 + i % 18}</td></tr>" },
                    "</table></body></html>",
                ),
            ),
            Triple(
                "pages3/big.mpd.xml",
                true,
                buildPage(
                    lim2m,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT2H\"><Period id=\"0\"><AdaptationSet mimeType=\"video/mp4\" segmentAlignment=\"true\"><Representation id=\"v1\" bandwidth=\"3000000\" width=\"1920\" height=\"1080\"><SegmentList duration=\"2\">\n",
                    { i -> "<SegmentURL media=\"chunk-$i.m4s\" mediaRange=\"${i * 4096}-${(i + 1) * 4096 - 1}\"/>\n" },
                    "</SegmentList></Representation></AdaptationSet></Period></MPD>\n",
                ),
            ),
            Triple(
                "pages3/playlist.xspf.xml",
                true,
                buildPage(
                    lim2m,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\"><trackList>\n",
                    { i -> "<track><location>http://iptv.ex.com/live/$i.m3u8</location><title>频道$i ${w(i, 1)}</title><image>http://ex.com/logo/$i.png</image><extension application=\"x\"><group>${w(i, 2)}</group></extension></track>\n" },
                    "</trackList></playlist>\n",
                ),
            ),
        )
        for ((name, isXml, content) in realPages) {
            checkDataset(name, content, isXml, requireDefaultAccept = true)
        }

        if (PluginDomParser.currentProcessDomMemoryBytes() != 0L) {
            throw AssertionError("Process DOM memory ledger leaked: ${PluginDomParser.currentProcessDomMemoryBytes()}")
        }
        println("ROUND8_CALIB_PASSED")
        kotlin.system.exitProcess(0)
    }
}

/**
 * Isolated `-Xmx256m` child JVM verifying Round 8 items 1, 4, 5, 6, 7:
 * - Tag/attr name length cap = 256 (fast rejection of 257 / 1000 / 2 MB names + cached lowercasing)
 * - Escaped `attrsJson` 8 MB cap (2 MB `\u0001` -> 12 MB JSON rejected)
 * - `.a ~ .b` on 200,000 classed elements under default 16 MB query memory cap
 * - Process-wide 64 MB DOM budget across 2 concurrent VMs trying to fill 48 MB each,
 *   asserting actual combined retained heap <= 80 MB and zero OOM under `-Xmx256m`
 */
object Round8ProcessBudgetAndFixesSubprocessMain {
    private fun retainedBytes(): Long {
        val mem = java.lang.management.ManagementFactory.getMemoryMXBean()
        repeat(4) {
            System.gc()
            Thread.sleep(15)
        }
        return mem.heapMemoryUsage.used
    }

    private inline fun assertFastUnder(name: String, maxMs: Long, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000L
        if (ms >= maxMs) throw AssertionError("Case '$name' exceeded ${maxMs}ms: ${ms}ms")
        println("OK [$name] ${ms}ms")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. Item #1 & #5: 256-char tag/attr name cap (rejects > 256 fast; accepts == 256)
        run {
            val p = PluginDomParser()
            val okTag = "t".repeat(256)
            val okAttr = "a".repeat(256)
            val rOk = p.parseHtml("<$okTag $okAttr='1'></$okTag>")
            if (p.selectAllIds(rOk, okTag).size != 1) throw AssertionError("256-char tag name must be accepted")
            p.clear()

            val badHtmls = listOf(
                "tag_257" to "<${"t".repeat(257)}></${"t".repeat(257)}>",
                "tag_1000" to "<div><${"t".repeat(1000)}/></div>",
                "tag_2mb" to "<${"t".repeat(2_000_000)}>",
                "attr_257" to "<p ${"k".repeat(257)}='1'></p>",
                "attr_1000" to "<div><p ${"k".repeat(1000)}='1'></p></div>",
                "attr_2mb" to "<p ${"k".repeat(2_000_000)}='1'></p>",
            )
            for ((label, html) in badHtmls) {
                assertFastUnder("R8_name_cap_$label", 200L) {
                    try {
                        p.parseHtml(html)
                        throw AssertionError("Expected DomLimitExceededException for $label")
                    } catch (_: DomLimitExceededException) {
                    }
                }
            }
            assertFastUnder("R8_xml_tag_2mb", 200L) {
                try {
                    p.parseXml("<r><${"T".repeat(2_000_000)}/></r>")
                    throw AssertionError("Expected DomLimitExceededException for 2 MB XML tag")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Item #5: 256-char uppercase attr keys x 400 elems with 50ms deadline stays bounded
            val k256 = "K".repeat(256)
            val rLower = p.parseHtml("<div>" + "<p $k256='1' x='2'></p>".repeat(400) + "</div>")
            p.setCallDeadlineNanos(System.nanoTime() + 50_000_000L)
            assertFastUnder("R8_cached_lower_50ms_deadline", 120L) {
                try {
                    p.selectAllIds(rLower, "p" + "[x]".repeat(1300))
                } catch (_: DomTimeoutException) {
                } catch (_: DomLimitExceededException) {
                }
            }
            p.clear()
        }

        // 2. Item #6: attrsJson counts ESCAPED JSON bytes (2 MB '\u0001' -> 12 MB JSON > 8 MB cap)
        run {
            val p = PluginDomParser()
            val rCtrl = p.parseHtml("<p a='" + "\u0001".repeat(2_000_000) + "'></p>")
            val pId = p.selectFirstId(rCtrl, "p")
            assertFastUnder("R8_attrsJson_escaped_8mb_cap", 500L) {
                try {
                    p.attrsJson(pId)
                    throw AssertionError("2 MB control chars (12 MB escaped JSON) must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Normal control char escaping produces valid JSON matching RFC 8259
            val rSmall = p.parseHtml("<p a=\"line1\n\t\u0001&quot;\\end\"></p>")
            val jsonStr = p.attrsJson(p.selectFirstId(rSmall, "p"))
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
            if (parsed.toString().isEmpty()) throw AssertionError("Invalid JSON from attrsJson")
            p.clear()
        }

        // 3. Item #7: `.a ~ .b` on 200,000 classed elements succeeds under default 16 MB query memory budget!
        run {
            val p200k = PluginDomParser(
                maxDocBytes = 4 * 1024 * 1024,
                maxDocMemoryBytes = 48L * 1024 * 1024,
                maxVmMemoryBytes = 96L * 1024 * 1024,
                maxProcessMemoryBytes = 96L * 1024 * 1024,
            )
            val doc200k = "<div>" + "<i class=\"a b\"></i>".repeat(200_000) + "</div>"
            val r200k = p200k.parseHtml(doc200k)
            assertFastUnder("R8_200k_general_sibling_a_tilde_b", 1000L) {
                val res = p200k.selectAllIds(r200k, ".a ~ .b")
                if (res.size != 199_999) throw AssertionError("Expected 199999 for .a ~ .b, got ${res.size}")
            }
            assertFastUnder("R8_200k_general_sibling_nonmatch", 1000L) {
                val res = p200k.selectAllIds(r200k, ".zz ~ .a")
                if (res.size != 0) throw AssertionError("Expected 0 for .zz ~ .a, got ${res.size}")
            }
            assertFastUnder("R8_200k_adjacent_sibling_a_plus_b", 1000L) {
                val res = p200k.selectAllIds(r200k, "i.a + i.b")
                if (res.size != 199_999) throw AssertionError("Expected 199999 for i.a + i.b, got ${res.size}")
            }
            p200k.clear()
        }

        // 4. Item #4: Process-wide 64 MB budget across 2 concurrent VMs under -Xmx256m!
        //    Each VM tries to load docs up to its own 48 MB VM limit concurrently; total process
        //    estimated DOM memory MUST stay <= 64 MB, actual combined retained heap MUST stay <= 80 MB,
        //    and closing/clearing both VMs MUST return process budget to 0.
        run {
            val baseHeap = retainedBytes()
            // Each doc is ~11.2 MB estimated (real ~10.8 MB): 150k * <p a=b></p>? Use uniqtagopen ~14.5 MB
            val payload = "<div>" + "<p a=b>hi</p>".repeat(65_000) + "</div>" // ~14 MB estimated
            val vm1 = PluginDomParser()
            val vm2 = PluginDomParser()
            val hitLimitCount = java.util.concurrent.atomic.AtomicInteger(0)
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val latch = java.util.concurrent.CountDownLatch(2)

            fun fillVm(vm: PluginDomParser) {
                try {
                    for (i in 0 until 6) {
                        val r = vm.parseHtml(payload)
                        vm.outerHtml(r)
                        vm.text(r)
                        try {
                            vm.selectAllIds(r, "p")
                        } catch (_: DomLimitExceededException) {
                            hitLimitCount.incrementAndGet()
                            break
                        }
                    }
                } catch (_: DomLimitExceededException) {
                    hitLimitCount.incrementAndGet()
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    latch.countDown()
                }
            }

            val t1 = Thread { fillVm(vm1) }
            val t2 = Thread { fillVm(vm2) }
            t1.start()
            t2.start()
            latch.await()

            if (errors.isNotEmpty()) {
                throw AssertionError("Concurrent 2-VM fill threw unexpected error: ${errors.first()}", errors.first())
            }
            val procEst = PluginDomParser.currentProcessDomMemoryBytes()
            val vm1Est = vm1.currentVmMemoryBytes()
            val vm2Est = vm2.currentVmMemoryBytes()
            val retainedDeltaMb = ((retainedBytes() - baseHeap).coerceAtLeast(0L)) / (1024L * 1024L)
            println(
                String.format(
                    java.util.Locale.ROOT,
                    "OK [R8_2vm_process_64mb_cap] vm1=%.1fMB vm2=%.1fMB procEst=%.1fMB retainedDelta=%dMB hits=%d",
                    vm1Est / 1048576.0,
                    vm2Est / 1048576.0,
                    procEst / 1048576.0,
                    retainedDeltaMb,
                    hitLimitCount.get(),
                ),
            )
            if (procEst > PluginDomParser.MAX_PROCESS_MEMORY_BYTES) {
                throw AssertionError("Process DOM estimate exceeded 64 MB: $procEst")
            }
            if (hitLimitCount.get() < 1) {
                throw AssertionError("At least one VM must hit 64 MB process cap when 2 VMs try to fill 48 MB each")
            }
            if (retainedDeltaMb > 80L) {
                throw AssertionError("Combined 2-VM retained heap exceeded 80 MB: ${retainedDeltaMb}MB")
            }
            vm1.clear()
            vm2.clear()
            if (PluginDomParser.currentProcessDomMemoryBytes() != 0L) {
                throw AssertionError("Process DOM budget leaked after clearing both VMs: ${PluginDomParser.currentProcessDomMemoryBytes()}")
            }
        }

        // 5. Round 9 Item #1 [Blocking]: Fill.java scenario — 60 MB ballast + 2 concurrent VMs each
        //    holding 8 live 2 MB dup385 docs + select('*') + outerHtml + text under -Xmx256m -> ZERO OOM!
        run {
            val ballast = Array(60) { ByteArray(1024 * 1024 - 64) }
            val unit385 = "<p" + " a".repeat(385) + "></p>"
            val count385 = (2 * 1024 * 1024 - 128) / unit385.length
            val dup385 = "<div>" + unit385.repeat(count385) + "</div>"
            val baseHeap = retainedBytes()
            val vm1 = PluginDomParser()
            val vm2 = PluginDomParser()
            val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val bar = java.util.concurrent.CyclicBarrier(3)

            fun runFill(vm: PluginDomParser) {
                try {
                    val roots = ArrayList<Long>(8)
                    repeat(8) {
                        roots.add(vm.parseHtml(dup385))
                    }
                    for (r in roots) {
                        vm.selectAllIds(r, "*")
                        vm.outerHtml(r)
                        vm.text(r)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    bar.await()
                    bar.await()
                }
            }

            val t1 = Thread { runFill(vm1) }
            val t2 = Thread { runFill(vm2) }
            t1.start()
            t2.start()
            bar.await()
            if (errors.isNotEmpty()) {
                bar.await()
                throw AssertionError("Fill 2x8 dup385 under -Xmx256m + 60MB ballast failed: ${errors.first()}", errors.first())
            }
            val afterHeap = retainedBytes()
            val realDelta = (afterHeap - baseHeap).coerceAtLeast(1L)
            val estTotal = vm1.currentVmMemoryBytes() + vm2.currentVmMemoryBytes()
            val ratio = realDelta.toDouble() / estTotal.toDouble()
            println(
                String.format(
                    java.util.Locale.ROOT,
                    "OK [R9_fill_60mb_ballast_2x8_dup385] real=%.2fMB est=%.2fMB ratio=%.3f ballast=%dMB",
                    realDelta / 1048576.0,
                    estTotal / 1048576.0,
                    ratio,
                    ballast.size,
                ),
            )
            if (ratio < 0.75 || ratio > 1.15) {
                bar.await()
                throw AssertionError("Fill 2x8 dup385 real/est=$ratio outside [0.75, 1.15]")
            }
            bar.await()
            t1.join()
            t2.join()
            vm1.clear()
            vm2.clear()
            if (PluginDomParser.currentProcessDomMemoryBytes() != 0L) {
                throw AssertionError("Process DOM budget leaked after Fill 2x8 dup385 clear")
            }
        }

        println("ROUND8_FIXES_PASSED")
        kotlin.system.exitProcess(0)
    }
}

/**
 * Isolated JVM process calibrating [PluginDomParser.estimateDocumentBytes] against
 * real JVM retained heap across 14 document shapes, asserting `estimated >= 0.80 * actual`.
 */
object Round7CalibrationSubprocessMain {
    private fun retainedBytes(): Long {
        val mem = java.lang.management.ManagementFactory.getMemoryMXBean()
        repeat(5) {
            System.gc()
            Thread.sleep(20)
        }
        return mem.heapMemoryUsage.used
    }

    private fun checkShape(name: String, input: String, isXml: Boolean) {
        val base = retainedBytes()
        val parser = if (isXml) {
            org.jsoup.parser.Parser.xmlParser().settings(org.jsoup.parser.ParseSettings.preserveCase)
        } else {
            org.jsoup.parser.Parser.htmlParser().settings(org.jsoup.parser.ParseSettings.htmlDefault)
        }
        parser.setMaxDepth(512)
        val doc = parser.parseInput(input, "")
        val est = PluginDomParser.estimateDocumentBytes(doc)
        val after = retainedBytes()
        val actual = (after - base).coerceAtLeast(1L)
        val ratio = est.toDouble() / actual.toDouble()
        println(
            String.format(
                java.util.Locale.ROOT,
                "CALIB %-18s actual=%.2fMB est=%.2fMB ratio=%.2fx",
                name,
                actual / 1048576.0,
                est / 1048576.0,
                ratio,
            ),
        )
        // Keep doc strongly reachable until measurement finishes
        if (doc.childNodeSize() < 0) println(doc)
        if (ratio < 0.80) {
            throw AssertionError(
                "Estimator under-estimated shape '$name': est=${est}B < 80% of actual=${actual}B (ratio=${"%.2f".format(ratio)})",
            )
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Warm up Jsoup classes before measuring retained heap deltas
        org.jsoup.Jsoup.parse("<p>warmup</p>")

        val multiAttr = buildString {
            append("<div>")
            repeat(200) {
                append("<p")
                for (i in 0 until 200) append(" a").append(i).append("=v").append(i)
                append("></p>")
            }
            append("</div>")
        }
        val deep = buildString {
            repeat(500) { append("<div>") }
            append("<i></i>".repeat(50_000))
        }

        checkShape("pure_elem", "<div>" + "<i></i>".repeat(100_000) + "</div>", isXml = false)
        checkShape("elem_text", "<div>" + "<i>x</i>".repeat(100_000) + "</div>", isXml = false)
        checkShape("elem_short", "<i>x".repeat(100_000), isXml = false)
        checkShape("pi_html", "<?>".repeat(200_000), isXml = false)
        checkShape("bogus_html", "<!>".repeat(200_000), isXml = false)
        checkShape("comment_html", "<!--a-->".repeat(200_000), isXml = false)
        checkShape("text_comment", "x<!>".repeat(150_000), isXml = false)
        checkShape("many_attr", multiAttr, isXml = false)
        checkShape("long_attr", "<p x='" + "a".repeat(1_000_000) + "'></p>", isXml = false)
        checkShape("long_text", "<p>" + "a".repeat(1_000_000) + "</p>", isXml = false)
        checkShape("deep_nest", deep, isXml = false)
        checkShape("classed", "<div>" + "<i class=\"card item\"></i>".repeat(100_000) + "</div>", isXml = false)
        checkShape("xml_pi", "<r>" + "<?p x?>".repeat(100_000) + "</r>", isXml = true)
        checkShape("xml_cdata", "<r>" + "<![CDATA[hello]]>".repeat(50_000) + "</r>", isXml = true)

        println("CALIBRATION_PASSED")
        kotlin.system.exitProcess(0)
    }
}

/**
 * Isolated `-Xmx256m` child JVM verifying all Round 7 fixes and running a full
 * 30-second soak loop of `parse + select('*') + outerHtml` across worst-case inputs
 * and 8 simultaneous documents right below the VM memory cap, asserting zero OOM
 * and retained heap <= 96 MB.
 */
object Round7SoakAndFixesSubprocessMain {
    private fun retainedMb(): Long {
        val mem = java.lang.management.ManagementFactory.getMemoryMXBean()
        repeat(4) {
            System.gc()
            Thread.sleep(15)
        }
        return mem.heapMemoryUsage.used / (1024L * 1024L)
    }

    private inline fun assertFastUnder1s(name: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        val ms = (System.nanoTime() - t0) / 1_000_000L
        if (ms >= 1000L) throw AssertionError("Case '$name' exceeded 1000ms: ${ms}ms")
        println("OK [$name] ${ms}ms")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. Input > 2 MB rejected immediately
        run {
            val p = PluginDomParser()
            val over2Mb = "<p>" + "a".repeat(2 * 1024 * 1024 + 16) + "</p>"
            assertFastUnder1s("R7_input_over_2mb_rejected") {
                try {
                    p.parseHtml(over2Mb)
                    throw AssertionError("Input > 2 MB must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
        }

        // 2. Three exact OOM payloads from Round 7 Item #1 (scaled to <= 2 MB input)
        val pi2Mb = "<?>".repeat((2 * 1024 * 1024) / 3 - 10)
        val textCmt2Mb = "x<!>".repeat((2 * 1024 * 1024) / 4 - 10)
        val elemText300k = "<i>x".repeat(300_000) // 1.20 MB, ~35.5 MB > 32 MB single-doc cap
        val elemText200k = "<i>x".repeat(200_000) // 800 KB, ~23.7 MB <= 32 MB single-doc cap

        run {
            val p = PluginDomParser()
            val tPi = System.nanoTime()
            try {
                p.parseHtml(pi2Mb)
                throw AssertionError("2 MB PI flood must throw DomLimitExceededException")
            } catch (_: DomLimitExceededException) {
            }
            val piMs = (System.nanoTime() - tPi) / 1_000_000L
            if (piMs >= 5000L) throw AssertionError("R7_pi_2mb_aborted_midparse exceeded 5000ms: ${piMs}ms")
            println("OK [R7_pi_2mb_aborted_midparse] ${piMs}ms")

            val tTc = System.nanoTime()
            try {
                p.parseHtml(textCmt2Mb)
                throw AssertionError("2 MB text+comment flood must throw DomLimitExceededException")
            } catch (_: DomLimitExceededException) {
            }
            val tcMs = (System.nanoTime() - tTc) / 1_000_000L
            if (tcMs >= 5000L) throw AssertionError("R7_textcmt_2mb_aborted_midparse exceeded 5000ms: ${tcMs}ms")
            println("OK [R7_textcmt_2mb_aborted_midparse] ${tcMs}ms")

            val tEt = System.nanoTime()
            try {
                p.parseHtml(elemText300k)
                throw AssertionError("<i>x * 300k must exceed 32 MB single-doc budget")
            } catch (_: DomLimitExceededException) {
            }
            val etMs = (System.nanoTime() - tEt) / 1_000_000L
            if (etMs >= 5000L) throw AssertionError("R7_elemtext_300k_rejected exceeded 5000ms: ${etMs}ms")
            println("OK [R7_elemtext_300k_rejected] ${etMs}ms")
            // 2 x (<i>x * 200k) (~23.7 MB each = ~47.4 MB <= 48 MB) succeed, then select('*') hits 48 MB VM budget!
            val d1 = p.parseHtml(elemText200k)
            val d2 = p.parseHtml(elemText200k)
            assertFastUnder1s("R7_two_docs_plus_handles_48mb_cap") {
                try {
                    p.selectAllIds(d1, "*")
                    throw AssertionError("47 MB live docs + handles must exceed 48 MB VM budget")
                } catch (_: DomLimitExceededException) {
                }
            }
            p.closeDoc(d1)
            p.closeDoc(d2)
            if (p.currentVmMemoryBytes() != 0L) throw AssertionError("closeDoc must zero VM memory")
        }

        // 3. Item #2: :empty caching + childNodes/attributes step accounting + entry deadline checks
        run {
            // 220k comments inside <div> (~21 MB <= 24 MB) + div:empty x680 with 1s deadline -> < 1s!
            val p = PluginDomParser()
            val rEmpty = p.parseHtml("<div>" + "<!>".repeat(220_000) + "</div>")
            p.setCallDeadlineNanos(System.nanoTime() + 1_000_000_000L)
            assertFastUnder1s("R7_empty_x680_cached_and_budgeted") {
                val res = p.selectAllIds(rEmpty, "div" + ":empty".repeat(680))
                if (res.size != 1) throw AssertionError("div with only comments is :empty, expected 1 got ${res.size}")
            }
            p.clear()

            // Even with lifted doc budget for 1,390,000 comments -> step budget/deadline aborts in < 1s!
            val bigP = PluginDomParser(
                maxDocBytes = 5 * 1024 * 1024,
                maxDocMemoryBytes = 200L * 1024 * 1024,
                maxVmMemoryBytes = 200L * 1024 * 1024,
            )
            val rHugeEmpty = bigP.parseHtml("<div>" + "<!>".repeat(1_390_000) + "</div>")
            bigP.setCallDeadlineNanos(System.nanoTime() + 1_000_000_000L)
            assertFastUnder1s("R7_empty_1390k_comments_under_1s") {
                try {
                    bigP.selectAllIds(rHugeEmpty, "div" + ":empty".repeat(680))
                    // Either cached in 1 pass (< 1s) or tripped step budget (< 1s)
                } catch (_: DomLimitExceededException) {
                } catch (_: DomTimeoutException) {
                }
            }
            bigP.clear()

            // Multi-attribute loop (`MA.java`): 512 attrs/elem x 400 elems x 1000 [id] -> trips 2M step budget in < 1s
            val oneMany = buildString {
                append("<p")
                for (i in 0 until 510) append(" a").append(i.toString(36))
                append(" id=a class=a></p>")
            }
            val manyAttrDoc = "<div>" + oneMany.repeat(400) + "</div>"
            val rMa = p.parseHtml(manyAttrDoc)
            p.setCallDeadlineNanos(System.nanoTime() + 1_000_000_000L)
            assertFastUnder1s("R7_many_attr_list_budgeted") {
                try {
                    p.selectAllIds(rMa, "p" + "[id]".repeat(1000))
                    throw AssertionError("400 elems x 512 attrs x 1000 [id] must hit 2M step budget")
                } catch (_: DomLimitExceededException) {
                }
            }

            // Entry deadline check on every DOM operation
            p.setCallDeadlineNanos(System.nanoTime() - 1L)
            val ops: List<Pair<String, () -> Unit>> = listOf(
                "parseHtml" to { p.parseHtml("<p>x</p>") },
                "selectFirstId" to { p.selectFirstId(rMa, "p") },
                "selectAllIds" to { p.selectAllIds(rMa, "p") },
                "childrenIds" to { p.childrenIds(rMa) },
                "tag" to { p.tag(rMa) },
                "text" to { p.text(rMa) },
                "html" to { p.html(rMa) },
                "outerHtml" to { p.outerHtml(rMa) },
                "attr" to { p.attr(rMa, "id") },
                "attrsJson" to { p.attrsJson(rMa) },
            )
            for ((opName, fn) in ops) {
                try {
                    fn()
                    throw AssertionError("Expired deadline must throw DomTimeoutException at entry of $opName")
                } catch (_: DomTimeoutException) {
                }
            }
            p.clear()
        }

        // 4. Item #3: 8 MB serialization cap on html(), outerHtml(), and text()
        run {
            val p = PluginDomParser()
            // 1.6 MB of `"` expands 6x to 9.6 MB (`&quot;`) > 8 MB cap -> aborts cleanly with DomLimitExceededException
            val quotDoc = "<p a='" + "\"".repeat(1_600_000) + "'></p>"
            val rQuot = p.parseHtml(quotDoc)
            assertFastUnder1s("R7_outerHtml_8mb_cap") {
                try {
                    p.outerHtml(rQuot)
                    throw AssertionError("9.6 MB escaped outerHtml must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            assertFastUnder1s("R7_html_8mb_cap") {
                try {
                    p.html(rQuot)
                    throw AssertionError("9.6 MB escaped html must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            p.clear()

            // text() cap verified with custom small maxOutputBytes = 64 KB
            val smallOut = PluginDomParser(maxOutputBytes = 64 * 1024)
            val rTxt = smallOut.parseHtml("<p>" + "x".repeat(100_000) + "</p>")
            assertFastUnder1s("R7_text_output_cap") {
                try {
                    smallOut.text(rTxt)
                    throw AssertionError("text() exceeding maxOutputBytes must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            smallOut.clear()
        }

        // 5. Item #4: 200,000 classed elements with `.a`, `i.a`, `.a.b`, `.a .b` MUST succeed
        //    under the default 16 MB selector memory budget (zero false-positive cache overflow!)
        run {
            // 5a: Default 2 MB / 24 MB / 48 MB limits with 120k classed elements
            val defP = PluginDomParser()
            val defDoc = "<div class=\"a\">" + "<p class=\"a b\">".repeat(120_000) + "</div>"
            val rDef = defP.parseHtml(defDoc)
            assertFastUnder1s("R7_class_default_120k") {
                if (defP.selectAllIds(rDef, ".a").size != 120_001) throw AssertionError(".a mismatch")
                if (defP.selectAllIds(rDef, "p.a").size != 120_000) throw AssertionError("p.a mismatch")
                if (defP.selectAllIds(rDef, ".a.b").size != 120_000) throw AssertionError(".a.b mismatch")
                if (defP.selectAllIds(rDef, ".a .b").size != 120_000) throw AssertionError(".a .b mismatch")
            }
            defP.clear()

            // 5b: Explicit 200,000 classed elements (default 16 MB selector memory budget!)
            val cls200kP = PluginDomParser(
                maxDocBytes = 4 * 1024 * 1024,
                maxDocMemoryBytes = 48L * 1024 * 1024,
                maxVmMemoryBytes = 80L * 1024 * 1024,
            )
            val doc200k = "<div class=\"a\">" + "<i class=\"a b\"></i>".repeat(200_000) + "</div>"
            val r200k = cls200kP.parseHtml(doc200k)
            assertFastUnder1s("R7_class_200k_dot_a") {
                if (cls200kP.selectAllIds(r200k, ".a").size != 200_001) throw AssertionError(".a on 200k mismatch")
            }
            assertFastUnder1s("R7_class_200k_i_dot_a") {
                if (cls200kP.selectAllIds(r200k, "i.a").size != 200_000) throw AssertionError("i.a on 200k mismatch")
            }
            assertFastUnder1s("R7_class_200k_dot_a_dot_b") {
                if (cls200kP.selectAllIds(r200k, ".a.b").size != 200_000) throw AssertionError(".a.b on 200k mismatch")
            }
            assertFastUnder1s("R7_class_200k_dot_a_space_dot_b") {
                if (cls200kP.selectAllIds(r200k, ".a .b").size != 200_000) throw AssertionError(".a .b on 200k mismatch")
            }
            cls200kP.clear()
        }

        // 6. Item #5: Monotonic Long nextId never reset across clear()
        run {
            val p = PluginDomParser()
            val id1: Long = p.parseHtml("<p>one</p>")
            p.clear()
            val id2: Long = p.parseHtml("<p>two</p>")
            if (id2 <= id1) throw AssertionError("nextId must be monotonic across clear(): id1=$id1 id2=$id2")
            if (p.tag(id1) != "") throw AssertionError("Stale handle id1 must not resolve after clear()")
            if (p.tag(id2) != "#document") throw AssertionError("Fresh handle id2 must resolve")
            p.clear()
            println("OK [R7_monotonic_long_nextId] id1=$id1 id2=$id2")
        }

        // 7. 30-second -Xmx256m soak loop:
        //    8 simultaneous live docs each right below the per-doc share of the 48 MB VM cap
        //    + the 3 worst-case attack payloads (PI, text+comment, <i>x * 200k)
        //    + select('*') + outerHtml -> assert zero OOM and retained heap <= 96 MB!
        run {
            val p = PluginDomParser()
            // Each doc is ~4.3 MB estimated (30k * <i>x -> ~4.3 MB? Let's use 20k * <i>x = ~4.4 MB);
            // 8 simultaneous live docs = ~35.2 MB <= 48 MB, leaving headroom for handles & outerHtml!
            val nearCapDocs = arrayOf(
                "<div>" + "<i>x</i>".repeat(20_000) + "</div>",
                "<div>" + "<i class=\"a b\"></i>".repeat(25_000) + "</div>",
                "<div>" + "<!--c-->".repeat(45_000) + "<p>ok</p></div>",
                "<div>" + "<i></i>".repeat(65_000) + "</div>",
                "<div>" + "<i>x</i>".repeat(20_000) + "</div>",
                "<div>" + "<i class=\"a b\"></i>".repeat(25_000) + "</div>",
                "<div>" + "<!--c-->".repeat(45_000) + "<p>ok</p></div>",
                "<div>" + "<i></i>".repeat(65_000) + "</div>",
            )
            val startMs = System.currentTimeMillis()
            var cycles = 0
            var maxRetainedMb = 0L
            while (System.currentTimeMillis() - startMs < 30_000L) {
                // a) Hit all 3 attack payloads — every one must fail cleanly without leaking
                for (attack in listOf(pi2Mb, textCmt2Mb, elemText300k)) {
                    try {
                        p.parseHtml(attack)
                        throw AssertionError("Attack payload unexpectedly succeeded during soak cycle #$cycles")
                    } catch (_: DomLimitExceededException) {
                    }
                }
                // b) Keep 8 simultaneous live docs near the 48 MB VM cap, run select('*') + outerHtml
                val roots = LongArray(8)
                for (i in 0 until 8) {
                    roots[i] = p.parseHtml(nearCapDocs[i])
                }
                if (p.currentLiveDocCount() != 8) {
                    throw AssertionError("Expected 8 live docs, got ${p.currentLiveDocCount()}")
                }
                // 9th doc must be rejected
                try {
                    p.parseHtml("<p>ninth</p>")
                    throw AssertionError("9th live doc must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
                for (i in 0 until 8) {
                    try {
                        p.selectAllIds(roots[i], "*")
                    } catch (_: DomLimitExceededException) {
                        // Expected if handle table pushes total past 48 MB VM cap
                    }
                    val html = p.outerHtml(roots[i])
                    if (html.isEmpty()) throw AssertionError("outerHtml returned empty")
                }
                if (cycles % 10 == 0) {
                    val ret = retainedMb()
                    if (ret > maxRetainedMb) maxRetainedMb = ret
                    if (ret > 96L) {
                        throw AssertionError("Retained heap exceeded 96 MB while 8 docs live: ${ret}MB")
                    }
                }
                for (i in 0 until 8) p.closeDoc(roots[i])
                if (p.currentLiveDocCount() != 0 || p.currentVmMemoryBytes() != 0L) {
                    throw AssertionError("Leak after closing 8 docs: vmMem=${p.currentVmMemoryBytes()}")
                }
                cycles += 1
            }
            val finalRetained = retainedMb()
            if (finalRetained > maxRetainedMb) maxRetainedMb = finalRetained
            println("SOAK_30S cycles=$cycles maxRetainedMb=$maxRetainedMb finalRetainedMb=$finalRetained")
            if (maxRetainedMb > 96L) {
                throw AssertionError("Peak retained heap exceeded 96 MB cap: ${maxRetainedMb}MB")
            }
        }

        println("ROUND7_SOAK_PASSED")
        kotlin.system.exitProcess(0)
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

            // Live VM DOM memory cap = 48 MiB (docs + handles) + doc.close() reclamation
            // doc1 (7.6 MiB) + 190,005 handles * 184B (33.3 MiB) = 40.9 MiB <= 48 MiB
            val html100k = "<div>" + "<i></i>".repeat(100_000) + "</div>" // ~4.1 MiB
            val root2 = p.parseHtml(html100k) // 40.9 + 4.1 = 45.0 MiB <= 48 MiB
            if (p.currentLiveDocCount() != 2 || p.currentTotalNodes() != 290_008) {
                throw AssertionError("Expected 2 live docs / 290008 nodes, got ${p.currentLiveDocCount()} / ${p.currentTotalNodes()}")
            }
            assertFastUnder1s("R4_live_48mb_vm_cap_enforced") {
                try {
                    p.parseHtml(html100k) // 45.0 + 4.1 = 49.1 MiB > 48 MiB -> throws!
                    throw AssertionError("Exceeding 48 MB VM DOM budget must throw DomLimitExceededException")
                } catch (_: DomLimitExceededException) {
                }
            }
            // Closing doc #1 immediately reclaims BOTH its doc bytes AND its 190,005 handle bytes!
            if (!p.closeDoc(root)) throw AssertionError("closeDoc(root) returned false")
            if (p.currentLiveDocCount() != 1 || p.currentTotalNodes() != 100_004) {
                throw AssertionError("After closeDoc: docs=${p.currentLiveDocCount()} nodes=${p.currentTotalNodes()}")
            }
            val root3 = p.parseHtml(html100k) // 4.1 + 4.1 = 8.2 MiB <= 48 MiB -> succeeds!
            if (p.currentLiveDocCount() != 2 || p.currentTotalNodes() != 200_008) {
                throw AssertionError("After reclaim+parse: docs=${p.currentLiveDocCount()} nodes=${p.currentTotalNodes()}")
            }
            p.closeDoc(root2)
            p.closeDoc(root3)
            if (p.currentLiveDocCount() != 0 || p.currentTotalNodes() != 0 || p.currentHandleCount() != 0 ||
                p.currentVmMemoryBytes() != 0L
            ) {
                throw AssertionError("All closed docs must release to 0 (vmMem=${p.currentVmMemoryBytes()})")
            }
            println("OK [R4_live_48mb_and_doc_close]")
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
