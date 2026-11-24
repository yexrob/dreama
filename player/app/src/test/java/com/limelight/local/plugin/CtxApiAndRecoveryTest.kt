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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Executes real QuickJS (via `quickjs-kt-jvm`) to verify every `ctx` API,
 * encrypted storage isolation, credential log redaction, protocol error
 * mapping, and runtime destroy+rebuild recovery after timeout and OOM.
 */
class CtxApiAndRecoveryTest {

    @Test
    fun exercisesEveryCtxApiInsideRealQuickJs() = runBlocking {
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

                // Storage round-trip + quota
                ctx.storage.set('token', { value: 'tok_secret_value', n: 7 });
                const stored = ctx.storage.get('token');
                const keys = ctx.storage.keys();
                ctx.storage.remove('token');
                ctx.storage.set('secretPersist', 'plain-should-be-encrypted');
                let quotaHit = false;
                try { ctx.storage.set('huge', 'x'.repeat(1100 * 1024)); } catch (e) { quotaHit = true; }

                // Cookies
                ctx.cookies.set('https://api.example.com/', 'sess=abc999; Path=/; Secure');
                const cookieStr = ctx.cookies.get('https://api.example.com/v1');

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

                // Parse HTML & XML
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

                ctx.storage.set('out', {
                  configFrozen, instanceId: ctx.instanceId, deviceModel: ctx.device.model,
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
            runtime.invoke("home")

            // Verify encrypted storage on disk does not contain plaintext
            val rawDisk = String(storageFile.readBytes(), Charsets.ISO_8859_1)
            assertFalse(rawDisk.contains("plain-should-be-encrypted"))

            // Reload from disk with same SecretBox succeeds
            val reloaded = InstanceStorage(storageFile, secretBox)
            assertEquals("plain-should-be-encrypted", reloaded.get("secretPersist")?.jsonPrimitive?.content)

            val out = storage.get("out")!!.jsonObject
            assertTrue(out["configFrozen"]!!.jsonPrimitive.boolean)
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

            // Decrypt RSA OAEP-SHA256 ciphertext with private key
            val rsaBytes = PluginCrypto.base64Decode(out["rsaB64"]!!.jsonPrimitive.content)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                kp.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            assertEquals("rsa-ok", String(cipher.doFinal(rsaBytes), Charsets.UTF_8))

            // Parse HTML & XML
            assertEquals(
                listOf("/m/1:星际", "/m/2:沙丘"),
                out["links"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("第一集", out["xmlName"]!!.jsonPrimitive.content)
            assertEquals("你好，世界", out["greeting"]!!.jsonPrimitive.content)
            assertEquals("fired", out["timerRes"]!!.jsonPrimitive.content)

            // Log prefix & secret redaction
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
    fun mapsAllNineProtocolErrorCodesWithChineseDefaults() {
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
        // NeedsAuth is a subtype of SourceNeedsSetupException for wall compatibility
        assertTrue(PluginErrorMapper.toException(PluginErrorPayload(PluginErrorCode.NEEDS_AUTH)) is SourceNeedsSetupException)
    }

    @Test
    fun recoversCleanlyAfterTimeoutAndOutOfMemory() = runBlocking {
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
              init(ctx) {
                const n = (ctx.storage.get('initCount') || 0) + 1;
                ctx.storage.set('initCount', n);
              },
              dispose(ctx) {
                ctx.storage.set('disposed', true);
              },
              home() { return []; },
              browse(ctx, { categoryId }) {
                if (categoryId === 'hang') {
                  while (true) {}
                }
                if (categoryId === 'oom') {
                  const buf = new Uint8Array(6 * 1024 * 1024);
                  return { items: [{ id: String(buf.length), kind: 'video', title: 'never' }] };
                }
                return { items: [{ id: 'ok', kind: 'video', title: 'run-' + ctx.storage.get('initCount') }] };
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

            // 1. Infinite loop timeout -> SourceUnavailableException + destroy
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("hang"))))
                fail("Expected timeout to throw SourceUnavailableException")
            } catch (_: SourceUnavailableException) {
            }

            // Next call rebuilds VM and re-runs init()
            val r2 = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("ok"))))
            assertEquals("run-2", r2.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(2, runtime.creationCount)

            // 2. OOM exceeding 4 MB limit -> PluginInternalException + destroy
            try {
                runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("oom"))))
                fail("Expected OOM to throw PluginInternalException")
            } catch (_: PluginInternalException) {
            }

            // Next call rebuilds VM cleanly again
            val r3 = runtime.invoke("browse", JsonObject(mapOf("categoryId" to JsonPrimitive("ok"))))
            assertEquals("run-3", r3.jsonObject["items"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(3, runtime.creationCount)

            // 3. Shutdown invokes dispose(ctx)
            runtime.shutdown()
            assertEquals(true, storage.get("disposed")?.jsonPrimitive?.boolean)
        } finally {
            runtime.close()
            tmpDir.deleteRecursively()
        }
    }
}
