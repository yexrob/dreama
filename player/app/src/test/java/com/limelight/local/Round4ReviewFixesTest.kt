package com.limelight.local

import android.app.Application
import androidx.room.Room
import com.limelight.LimeLog
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.MediaSource
import com.limelight.local.media.Page as MediaPage
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.Shelf
import com.limelight.local.media.Source
import com.limelight.local.media.SourceKind
import com.limelight.local.media.WallDatabase
import com.limelight.local.media.WatchProgress
import com.limelight.local.stream.DiscoveredLanService
import com.limelight.local.stream.HostDiscovery
import com.limelight.local.stream.PhoneFormCopy
import com.limelight.local.stream.PhoneFormField
import com.limelight.local.stream.PhoneFormFieldKind
import com.limelight.local.stream.PhoneFormServer
import com.limelight.local.stream.PhoneInputCopy
import com.limelight.local.stream.PhoneInputServer
import com.limelight.local.stream.PhoneSubmitResult
import com.limelight.local.stream.UdpDiscoveryWindow
import com.limelight.local.wall.ContinueWatchingResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Round 4 final-polish regression tests:
 * 1. Real Room WatchProgress upsert preserves existing parentId on null/blank overwrites + ContinueWatching placeholder carries parentId
 * 2. Pure UdpDiscoveryWindow evicts servers missing 2 consecutive rounds, frees cap slots, and computes 5s cadence from round start
 * 3. PhoneFormServer & PhoneInputServer mark locked/closed and invoke onLockedOut BEFORE sending 5th 403 response
 * 5. HostDiscovery restores exact main mDNS resolved info log line
 * 6. PhoneFormServer concurrent submissions call onSubmitted once; retry after Rejected still succeeds
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Round4ReviewFixesTest {

    private lateinit var app: Application
    private lateinit var db: WallDatabase

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, WallDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
    }

    // ---- Item #1: WatchProgress preserves parentId on null/blank upsert + ContinueWatching carries parentId ----

    @Test
    fun r4_item01_watchProgressPreservesParentIdOnNullOverwriteAndContinueWatchingCarriesParentId() = runBlocking {
        val dao = db.progress()

        // 1. Initial playback from Series Detail saves parentId = "s2"
        dao.upsert(
            WatchProgress(
                sourceId = "plugin:jf",
                itemId = "ep-s2e1",
                title = "S2E1",
                positionMs = 120_000L,
                durationMs = 1_800_000L,
                updatedAt = 1_000L,
                parentId = "s2",
            ),
        )
        assertEquals("s2", dao.get("plugin:jf", "ep-s2e1")?.parentId)

        // 2. Subsequent write WITHOUT parentId (null) must update positionMs while PRESERVING parentId = "s2"
        dao.upsert(
            WatchProgress(
                sourceId = "plugin:jf",
                itemId = "ep-s2e1",
                title = "S2E1",
                positionMs = 360_000L,
                durationMs = 1_800_000L,
                updatedAt = 2_000L,
                parentId = null,
            ),
        )
        val afterNull = dao.get("plugin:jf", "ep-s2e1")!!
        assertEquals("Null parentId write must preserve previously stored parentId", "s2", afterNull.parentId)
        assertEquals(360_000L, afterNull.positionMs)

        // 3. Blank parentId also preserves existing parentId
        dao.upsert(
            WatchProgress(
                sourceId = "plugin:jf",
                itemId = "ep-s2e1",
                title = "S2E1",
                positionMs = 420_000L,
                durationMs = 1_800_000L,
                updatedAt = 3_000L,
                parentId = "   ",
            ),
        )
        assertEquals("s2", dao.get("plugin:jf", "ep-s2e1")?.parentId)

        // 4. Explicit new non-blank parentId updates normally
        dao.upsert(
            WatchProgress(
                sourceId = "plugin:jf",
                itemId = "ep-s2e1",
                title = "S2E1",
                positionMs = 450_000L,
                durationMs = 1_800_000L,
                updatedAt = 4_000L,
                parentId = "s2-v2",
            ),
        )
        assertEquals("s2-v2", dao.get("plugin:jf", "ep-s2e1")?.parentId)

        // 5. ContinueWatching placeholder Item (Phase 1) AND detail() returning null parentId (Phase 2)
        //    must BOTH preserve WatchProgress.parentId!
        val slowSrc = object : MediaSource {
            override val source = Source("plugin:jf", "Jellyfin", SourceKind.PLUGIN, "")
            override suspend fun home(): List<Shelf> = emptyList()
            override suspend fun browse(categoryId: String, page: Int): MediaPage<Item> = MediaPage(emptyList(), null)
            override suspend fun detail(itemId: String): Item {
                delay(40L)
                // Simulate a plugin detail() that omits parentId
                return Item(itemId, ItemKind.EPISODE, "S2E1 Full Title", durationMs = 1_800_000L, parentId = null)
            }
            override suspend fun playback(itemId: String): PlaybackInfo = PlaybackInfo("")
        }
        val row = WatchProgress("plugin:jf", "ep-s2e1", "S2E1", 360_000L, 1_800_000L, 2_000L, parentId = "s2")
        val emissions = ContinueWatchingResolver.resolveFlow(
            rows = listOf(row),
            sources = listOf(slowSrc),
        ).toList()

        assertTrue("Expected at least Phase 1 placeholder and Phase 2 resolved emission", emissions.size >= 2)
        assertEquals(
            "Phase 1 placeholder Item must carry WatchProgress.parentId",
            "s2",
            emissions.first().single().item.parentId,
        )
        assertEquals(
            "Phase 2 resolved Item with null parentId must preserve WatchProgress.parentId",
            "s2",
            emissions.last().single().item.parentId,
        )
    }

    // ---- Item #2: UdpDiscoveryWindow evicts after 2 consecutive misses, frees cap, 5s cadence from start ----

    @Test
    fun r4_item02_udpDiscoveryWindowEvictsAfterTwoConsecutiveMissesFreesCapAndComputes5sCadence() {
        fun svc(id: String, ip: String, name: String = "Server $id") = DiscoveredLanService(
            serviceType = "udp:jellyfin",
            serviceName = name,
            address = ip,
            port = 8096,
            txt = mapOf("id" to id, "address" to "http://$ip:8096"),
        )

        val a = svc("a", "192.168.1.10")
        val b = svc("b", "192.168.1.11")
        val c = svc("c", "192.168.1.12")
        val d = svc("d", "192.168.1.13")

        val window = UdpDiscoveryWindow(maxEntries = 3, evictAfterConsecutiveMisses = 2)

        // Round 1: A, B, C discovered (cap 3 full)
        assertEquals(listOf("a", "b", "c"), window.onRoundComplete(1, listOf(a, b, c)).map { it.txt["id"] })

        // Round 2: C misses 1 round -> still retained (UDP single-packet loss grace)
        assertEquals(listOf("a", "b", "c"), window.onRoundComplete(2, listOf(a, b)).map { it.txt["id"] })

        // Round 3: C misses 2nd consecutive round while D arrives -> C is evicted and D reclaims freed slot!
        val afterR3 = window.onRoundComplete(3, listOf(a, b, d))
        assertEquals(listOf("a", "b", "d"), afterR3.map { it.txt["id"] })

        // Stale partial callback from already-completed round 1 must NOT resurrect evicted server C
        assertEquals(listOf("a", "b", "d"), window.onPartial(1, listOf(c)).map { it.txt["id"] })

        // Round 4: B misses 1 round (misses=1), Round 5: B returns (misses reset to 0), Round 6: B misses 1 round -> NOT evicted
        assertEquals(listOf("a", "b", "d"), window.onRoundComplete(4, listOf(a, d)).map { it.txt["id"] })
        assertEquals(listOf("a", "b", "d"), window.onRoundComplete(5, listOf(a, b, d)).map { it.txt["id"] })
        assertEquals(listOf("a", "b", "d"), window.onRoundComplete(6, listOf(a, d)).map { it.txt["id"] })

        // Cadence measured from round START (5s interval minus 2s probe duration = 3s wait, not 5s)
        assertEquals(3_000L, UdpDiscoveryWindow.nextDelayMs(roundStartMs = 10_000L, nowMs = 12_000L, intervalMs = 5_000L))
        assertEquals(0L, UdpDiscoveryWindow.nextDelayMs(roundStartMs = 10_000L, nowMs = 16_000L, intervalMs = 5_000L))
    }

    // ---- Item #3: Lockout marks locked/closed and triggers onLockedOut BEFORE writing 5th 403 response ----

    @Test
    fun r4_item03_phoneServersMarkLockedAndInvokeCallbackBeforeFifth403ResponseReturns() {
        // Part A: PhoneFormServer
        val formCopy = PhoneFormCopy.from(app, "Jellyfin")
        val fields = listOf(PhoneFormField("server", PhoneFormFieldKind.URL, "Server", required = true))
        val formLocked = AtomicBoolean(false)
        val formLockCalls = AtomicInteger(0)
        PhoneFormServer(
            copy = formCopy,
            fields = fields,
            displayHost = "127.0.0.1",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onLockedOut = {
                formLocked.set(true)
                formLockCalls.incrementAndGet()
            },
            onSubmitted = { PhoneSubmitResult.Accepted },
        ).start().use { srv ->
            for (attempt in 1..PhoneInputServer.MAX_FAILED_ATTEMPTS) {
                val (code, body) = httpPost(
                    "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_CODE}",
                    "code=0000&f_server=" + URLEncoder.encode("http://192.168.1.10:8096", "UTF-8"),
                )
                assertEquals("Attempt $attempt must receive 403", 403, code)
                if (attempt == PhoneInputServer.MAX_FAILED_ATTEMPTS) {
                    assertTrue("5th response must carry locked-out copy", body.contains(formCopy.errLockedOut))
                }
            }
            // Immediately observable with ZERO sleep because lockout & callback ran BEFORE writeHtml!
            assertTrue("onLockedOut must already have fired before 5th response returns", formLocked.get())
            assertEquals(1, formLockCalls.get())
            assertTrue("isLockedOut must already be true", srv.isLockedOut)
            assertTrue("isClosed must already be true", srv.isClosed)
        }

        // Part B: PhoneInputServer (single-field mode)
        val inputCopy = PhoneInputCopy.from(app, PhoneInputServer.Mode.PLUGIN_REPO)
        val inputLocked = AtomicBoolean(false)
        PhoneInputServer(
            copy = inputCopy,
            displayHost = "127.0.0.1",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onLockedOut = { inputLocked.set(true) },
            onSubmitted = { PhoneSubmitResult.Accepted },
        ).start().use { srv ->
            for (attempt in 1..PhoneInputServer.MAX_FAILED_ATTEMPTS) {
                val (code, body) = httpPost(
                    "${srv.shortUrl}${PhoneInputServer.PATH_SUBMIT_CODE}",
                    "code=0000&value=https%3A%2F%2Fexample.com",
                )
                assertEquals(403, code)
                if (attempt == PhoneInputServer.MAX_FAILED_ATTEMPTS) {
                    assertTrue(body.contains(inputCopy.errLockedOut))
                }
            }
            assertTrue(inputLocked.get())
            assertTrue(srv.isLockedOut)
            assertTrue(srv.isClosed)
        }
    }

    // ---- Item #5: HostDiscovery restores exact main resolved info log line ----

    @Test
    fun r4_item05_hostDiscoveryEmitsMainResolvedLogLine() {
        val logger = Logger.getLogger(LimeLog::class.java.name)
        val captured = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                record.message?.let { captured += it }
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            val hostDiscovery = HostDiscovery(app)
            hostDiscovery.browser.ingestResolvedEndpoint(
                rawName = "StudioMac",
                addr = "192.168.1.10",
                port = 47990,
                txt = mapOf("id" to "mac-1", "name" to "Studio Mac", "v" to "1", "gs" to "47989", "pair" to "auto"),
            )
            // Invalid no-id record must NOT log resolved
            hostDiscovery.browser.ingestResolvedEndpoint(
                rawName = "NoIdHost",
                addr = "192.168.1.99",
                port = 47990,
                txt = mapOf("name" to "Bad"),
            )
        } finally {
            logger.removeHandler(handler)
        }

        val expected = "depth3d: host mDNS resolved Studio Mac at 192.168.1.10:47990 (gs=47989, pair=auto)"
        assertTrue(
            "Expected exact main Host resolved log line '$expected', got: $captured",
            captured.any { it == expected },
        )
        assertFalse(
            "Rejected no-id host must not emit resolved log line",
            captured.any { it.contains("192.168.1.99") },
        )
    }

    // ---- Item #6: PhoneFormServer concurrent submissions call onSubmitted once + retry after Rejected succeeds ----

    @Test
    fun r4_item06_phoneFormConcurrentSubmissionsCallOnSubmittedOnceAndRetryAfterRejectionSucceeds() {
        val copy = PhoneFormCopy.from(app, "Jellyfin")
        val fields = listOf(
            PhoneFormField("server", PhoneFormFieldKind.URL, "Server", required = true),
            PhoneFormField("username", PhoneFormFieldKind.TEXT, "Username", required = true),
            PhoneFormField("password", PhoneFormFieldKind.PASSWORD, "Password", required = true),
        )

        // Part A: Two truly concurrent POSTs with valid token -> onSubmitted invoked EXACTLY ONCE!
        val submitCalls = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            PhoneFormServer(
                copy = copy,
                fields = fields,
                displayHost = "127.0.0.1",
                code = "4444",
                bindAddress = InetAddress.getByName("127.0.0.1"),
                onSubmitted = {
                    submitCalls.incrementAndGet()
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    PhoneSubmitResult.Accepted
                },
            ).start().use { srv ->
                val body = "token=${srv.token}&f_server=" +
                    URLEncoder.encode("http://192.168.1.36:8096", "UTF-8") +
                    "&f_username=alice&f_password=secret"

                val f1 = pool.submit<Pair<Int, String>> { httpPost("${srv.shortUrl}/submit-qr", body) }
                assertTrue("First submission must enter onSubmitted", firstEntered.await(3, TimeUnit.SECONDS))

                // Fire 2nd concurrent POST while 1st is actively running inside onSubmitted
                val f2 = pool.submit<Pair<Int, String>> { httpPost("${srv.shortUrl}/submit-qr", body) }
                val (status2, _) = f2.get(5, TimeUnit.SECONDS)
                assertEquals("Concurrent duplicate submission must be rejected with 409 Conflict", 409, status2)

                releaseFirst.countDown()
                val (status1, html1) = f1.get(5, TimeUnit.SECONDS)
                assertEquals(200, status1)
                assertTrue(html1.contains(copy.doneTitle))
                assertEquals("Concurrent submissions must invoke onSubmitted EXACTLY ONCE", 1, submitCalls.get())
            }
        } finally {
            pool.shutdownNow()
        }

        // Part B: Rejected submission (e.g., wrong password) -> user retries with same token -> succeeds!
        val retryCalls = AtomicInteger(0)
        val acceptedCount = AtomicInteger(0)
        val acceptedLatch = CountDownLatch(1)
        PhoneFormServer(
            copy = copy,
            fields = fields,
            displayHost = "127.0.0.1",
            code = "5555",
            bindAddress = InetAddress.getByName("127.0.0.1"),
            onAcceptedFlushed = {
                acceptedCount.incrementAndGet()
                acceptedLatch.countDown()
            },
            onSubmitted = { values ->
                retryCalls.incrementAndGet()
                if (values["password"] != "good-pass") {
                    PhoneSubmitResult.Rejected("用户名或密码错误")
                } else {
                    PhoneSubmitResult.Accepted
                }
            },
        ).start().use { srv ->
            val badBody = "token=${srv.token}&f_server=" +
                URLEncoder.encode("http://192.168.1.36:8096", "UTF-8") +
                "&f_username=alice&f_password=wrong-pass"
            val (sBad, htmlBad) = httpPost("${srv.shortUrl}/submit-qr", badBody)
            assertEquals(400, sBad)
            assertTrue("Rejected response must show reason", htmlBad.contains("用户名或密码错误"))
            assertFalse("Rejected response must never echo password", htmlBad.contains("wrong-pass"))
            assertEquals(0, acceptedCount.get())

            val goodBody = "token=${srv.token}&f_server=" +
                URLEncoder.encode("http://192.168.1.36:8096", "UTF-8") +
                "&f_username=alice&f_password=good-pass"
            val (sGood, htmlGood) = httpPost("${srv.shortUrl}/submit-qr", goodBody)
            assertEquals(200, sGood)
            assertTrue(htmlGood.contains(copy.doneTitle))
            assertTrue("onAcceptedFlushed must fire on retry acceptance", acceptedLatch.await(3, TimeUnit.SECONDS))
            assertEquals(1, acceptedCount.get())

            // Re-submitting after acceptance must NOT invoke onSubmitted a 3rd time
            val (sDup, htmlDup) = httpPost("${srv.shortUrl}/submit-qr", goodBody)
            assertEquals(200, sDup)
            assertTrue(htmlDup.contains(copy.doneTitle))
            assertEquals("Expected exactly 2 onSubmitted calls (1 rejected + 1 accepted)", 2, retryCalls.get())
        }
    }

    private fun httpPost(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val status = conn.responseCode
        val stream = if (status in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return status to text
    }
}
