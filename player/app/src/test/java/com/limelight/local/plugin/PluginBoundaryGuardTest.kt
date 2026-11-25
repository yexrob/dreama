package com.limelight.local.plugin

import com.limelight.local.media.Artwork
import com.limelight.local.media.Item
import com.limelight.local.media.ItemKind
import com.limelight.local.media.PlaybackInfo
import com.limelight.local.media.SourceKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Architecture boundary guard supporting `docs/specs/plugin-host-integration.md`.
 *
 * Real coverage scope (important — do NOT overstate):
 * 1. **Static source scan** over `.kt`/`.java` files outside `local/plugin/`:
 *    - Forbids referencing plugin-internal implementation classes/helpers anywhere
 *      outside `local/plugin/`.
 *    - Verifies `Artworks.kt` keeps its non-crashing runtime fallback (`LimeLog.warning`
 *      + `return null`) for `SourceKind.PLUGIN`.
 *    - Flags unguarded mixing of `PluginMediaSource` / `SourceKind.PLUGIN` with raw
 *      HTTP / `DefaultHttpDataSource` / `MediaMetadataRetriever` / `Artworks.load` /
 *      `PlaybackInfo.url|headers`, while **explicitly allowing** UI/player files that
 *      branch via `PluginHostGuards.isPlugin*` or `PluginHostGuards.requireNonPlugin*`.
 * 2. **Unit test of [PluginHostGuards]** helper methods in isolation.
 *
 * What this test does NOT verify:
 * - Only `Artworks.load` / `Artworks.frame` currently have runtime refusal wired in
 *   production code (tested in `Media3DashAndRoomTest`).
 * - `WallActivity.launchPlayer`, `LocalPlayerXR.java` frame probing, and DLNA/cast
 *   (`local/dlna/`, `CastScreen.kt`) are NOT wired yet; the UI/player integration
 *   task MUST call [PluginHostGuards] in those entry points.
 */
class PluginBoundaryGuardTest {

    private val pluginPkgPrefix = "com/limelight/local/plugin/"
    private val localPkgPrefix = "com/limelight/local/"

    private val forbiddenInternalSymbols = listOf(
        "PlaybackMediaSourceFactory",
        "SafePluginHttpDataSource",
        "SafePluginHttpExecutor",
        "PluginImageFetcher",
        "FailoverUrlDataSource",
        "FailoverUrlSelector",
        "JsonSubtitleVttDataSource",
        "PluginImages.loadBytes",
        "createPlaybackFactory",
        "createImageFetcher",
    )

    private val legacyLocalRawClientFiles = setOf(
        "com/limelight/local/LocalPlayerXR.java",
        "com/limelight/local/WebDav.java",
        "com/limelight/local/media/Artworks.kt",
        "com/limelight/local/media/Model.kt",
        "com/limelight/local/wall/WallActivity.kt",
        "com/limelight/local/wall/Components.kt",
        // Talks only to a paired depth3d Host over its pinned-certificate control port.
        "com/limelight/local/stream/HostProtocolClient.kt",
    )

    private val rawPlaybackOrHttpPatterns = listOf(
        Regex("""\bDefaultHttpDataSource\b""") to "DefaultHttpDataSource (must use PluginPlayback for plugins)",
        Regex("""\bOkHttpClient\b""") to "raw OkHttpClient (must use PluginImages.loadBitmap / PluginPlayback for plugins)",
        Regex("""\bHttpURLConnection\b""") to "HttpURLConnection (must use PluginImages.loadBitmap / PluginPlayback for plugins)",
        Regex("""\bMediaMetadataRetriever\b""") to "MediaMetadataRetriever (forbidden for plugin URLs; use PluginImages.loadBitmap / ExoPlayer Format)",
        Regex("""\bArtworks\.load\b""") to "Artworks.load (forbidden for plugin artwork; use PluginImages.loadBitmap)",
    )
    private val rawPlaybackFieldAccess = Regex("""\b[a-zA-Z_][a-zA-Z0-9_]*\.(?:url|headers)\b""")
    private val playbackTypeMention = Regex("""\bPlaybackInfo\b""")
    private val pluginMarkerRegex = Regex("""\b(?:PluginMediaSource|SourceKind\.PLUGIN)\b""")
    private val hostGuardBranchRegex = Regex(
        """\bPluginHostGuards\.(?:isPlugin(?:Source|Artwork|Playback)|requireNonPlugin(?:Artwork|Playback|FrameProbe|Cast))\b""",
    )

    internal fun auditSourceText(rel: String, rawText: String): List<String> {
        if (rel.startsWith(pluginPkgPrefix)) return emptyList()
        val violations = ArrayList<String>()
        val codeOnly = stripComments(rawText)

        // Rule 1: ZERO references to plugin-internal classes/helpers anywhere outside local/plugin/
        for (sym in forbiddenInternalSymbols) {
            if (Regex("""\b${Regex.escape(sym)}\b""").containsMatchIn(codeOnly)) {
                violations += "$rel references internal plugin symbol '$sym' (use PluginPlayback / PluginImages.loadBitmap instead)"
            }
        }

        // Rule 2: Inside local/, audit raw HTTP/player clients and PlaybackInfo.url/headers access
        if (rel.startsWith(localPkgPrefix)) {
            if (rel == "com/limelight/local/media/Artworks.kt") {
                // Artworks.kt must keep its non-crashing runtime fallback (log warning + return null)
                if (!codeOnly.contains("art.sourceKind == SourceKind.PLUGIN") ||
                    !codeOnly.contains("LimeLog.warning") ||
                    !codeOnly.contains("return null")
                ) {
                    violations += "$rel must retain non-crashing runtime fallback (LimeLog.warning + return null) for SourceKind.PLUGIN"
                }
                if (codeOnly.contains("throw UnsupportedOperationException")) {
                    violations += "$rel must return null instead of throwing UnsupportedOperationException so Wall UI does not crash"
                }
                if (codeOnly.contains("PluginMediaSource")) {
                    violations += "$rel must not handle PluginMediaSource directly"
                }
                return violations
            }

            val mentionsPlugin = pluginMarkerRegex.containsMatchIn(codeOnly)
            val usesHostGuards = hostGuardBranchRegex.containsMatchIn(codeOnly)
            val isLegacyAllowlisted = rel in legacyLocalRawClientFiles

            for ((pattern, desc) in rawPlaybackOrHttpPatterns) {
                if (pattern.containsMatchIn(codeOnly)) {
                    if (!isLegacyAllowlisted) {
                        violations += "$rel uses $desc outside audited legacy allowlist (see docs/specs/plugin-host-integration.md)"
                    } else if (mentionsPlugin && !usesHostGuards) {
                        violations += "$rel mixes plugin source handling with $desc without branching via PluginHostGuards.isPlugin* / requireNonPlugin*"
                    }
                }
            }

            if (playbackTypeMention.containsMatchIn(codeOnly) && rawPlaybackFieldAccess.containsMatchIn(codeOnly)) {
                if (!isLegacyAllowlisted) {
                    violations += "$rel directly accesses PlaybackInfo.url/headers outside audited legacy allowlist (use PluginPlayback instead)"
                } else if (mentionsPlugin && !usesHostGuards) {
                    violations += "$rel references plugin source AND directly accesses PlaybackInfo.url/headers without PluginHostGuards branching"
                }
            }
        } else {
            // Rule 3: Outside local/, never mix plugin source with raw HTTP/player APIs unless explicitly guarded
            if (pluginMarkerRegex.containsMatchIn(codeOnly) && !hostGuardBranchRegex.containsMatchIn(codeOnly)) {
                for ((pattern, desc) in rawPlaybackOrHttpPatterns) {
                    if (pattern.containsMatchIn(codeOnly)) {
                        violations += "$rel outside local/ mixes plugin source with $desc without PluginHostGuards"
                    }
                }
            }
        }
        return violations
    }

    @Test
    fun enforcesPluginPlaybackAndImageBoundaryOutsidePluginPackage() {
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val mainJavaRoot = File(repoRoot, "player/app/src/main/java")
        assertTrue("Expected Android source root at $mainJavaRoot", mainJavaRoot.isDirectory)

        val violations = ArrayList<String>()
        mainJavaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val rel = file.relativeTo(mainJavaRoot).path.replace(File.separatorChar, '/')
                violations += auditSourceText(rel, file.readText())
            }

        if (violations.isNotEmpty()) {
            fail(
                "Plugin security boundary violations found (see docs/specs/plugin-host-integration.md):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    fun staticAuditAllowsPluginHostGuardsBranchingInUiFilesAndRejectsUnguardedMixing() {
        // UI file branching cleanly on PluginHostGuards.isPluginArtwork -> MUST be allowed!
        val guardedUiCode = """
            package com.limelight.local.wall
            import com.limelight.local.media.Artworks
            import com.limelight.local.plugin.PluginHostGuards
            import com.limelight.local.plugin.PluginImages
            import com.limelight.local.plugin.PluginMediaSource
            suspend fun loadArt(src: PluginMediaSource?, art: Artwork) =
                if (PluginHostGuards.isPluginArtwork(art)) PluginImages.loadBitmap(src!!, art)
                else Artworks.load(ctx, art, Artworks.Size.CARD)
        """.trimIndent()
        assertEquals(
            "UI file branching via PluginHostGuards.isPluginArtwork must be allowed",
            emptyList<String>(),
            auditSourceText("com/limelight/local/wall/Components.kt", guardedUiCode),
        )

        // Legacy UI file branching on PluginHostGuards.isPluginPlayback -> MUST be allowed!
        val guardedLaunchCode = """
            package com.limelight.local.wall
            import com.limelight.local.media.PlaybackInfo
            import com.limelight.local.media.SourceKind
            import com.limelight.local.plugin.PluginHostGuards
            fun launch(info: PlaybackInfo) {
                if (PluginHostGuards.isPluginPlayback(info) || info.sourceKind == SourceKind.PLUGIN) return
                openLegacy(info.url, info.headers)
            }
        """.trimIndent()
        assertEquals(
            "WallActivity branching via PluginHostGuards.isPluginPlayback must be allowed",
            emptyList<String>(),
            auditSourceText("com/limelight/local/wall/WallActivity.kt", guardedLaunchCode),
        )

        // Legacy UI file mixing PluginMediaSource with Artworks.load WITHOUT PluginHostGuards -> MUST fail!
        val unguardedUiCode = """
            package com.limelight.local.wall
            import com.limelight.local.media.Artworks
            import com.limelight.local.plugin.PluginMediaSource
            suspend fun bad(src: PluginMediaSource, art: Artwork) = Artworks.load(ctx, art, Artworks.Size.CARD)
        """.trimIndent()
        assertTrue(
            "Unguarded mixing of PluginMediaSource and Artworks.load must be rejected",
            auditSourceText("com/limelight/local/wall/Components.kt", unguardedUiCode).isNotEmpty(),
        )

        // Referencing internal plugin class outside local/plugin/ -> ALWAYS fails even with PluginHostGuards!
        val internalLeakCode = """
            package com.limelight.local.wall
            import com.limelight.local.plugin.PluginHostGuards
            import com.limelight.local.plugin.SafePluginHttpDataSource
            fun leak() { PluginHostGuards.isPluginSource(null); SafePluginHttpDataSource::class }
        """.trimIndent()
        assertTrue(
            "Internal plugin symbol outside local/plugin/ must always be rejected",
            auditSourceText("com/limelight/local/wall/Components.kt", internalLeakCode).isNotEmpty(),
        )
    }

    @Test
    fun runtimeGuardHelpersRejectPluginItemsAndPlaybackInIsolation() {
        val policy = NetworkPolicy(
            permissions = PluginPermissions(hosts = listOf("cdn.example.com"), lan = false),
            dnsResolver = { listOf(java.net.InetAddress.getByName("93.184.216.34")) },
        )
        val mapper = PluginProtocolMapper(policy)

        val rawDetail = Json.parseToJsonElement(
            """
            {
              "id": "ep-1",
              "kind": "movie",
              "title": "Plugin Movie",
              "poster": {"url": "https://cdn.example.com/poster.jpg", "headers": {"Authorization": "Bearer secret"}},
              "thumb": "https://cdn.example.com/thumb.jpg",
              "backdrop": "https://cdn.example.com/backdrop.jpg",
              "people": [{"name": "Alice", "image": "https://cdn.example.com/alice.jpg"}]
            }
            """.trimIndent(),
        )
        val item: Item = mapper.mapItem(rawDetail)
        val poster = item.poster!!
        val thumb = item.thumb!!
        val backdrop = item.backdrop!!
        val personImg = item.people.first().image!!

        // Every Artwork mapped from a plugin MUST carry sourceKind == SourceKind.PLUGIN
        assertEquals(SourceKind.PLUGIN, poster.sourceKind)
        assertEquals(SourceKind.PLUGIN, thumb.sourceKind)
        assertEquals(SourceKind.PLUGIN, backdrop.sourceKind)
        assertEquals(SourceKind.PLUGIN, personImg.sourceKind)
        assertTrue(PluginHostGuards.isPluginArtwork(poster))

        val rawPb = Json.parseToJsonElement(
            """
            {
              "url": "https://cdn.example.com/video.m3u8",
              "headers": {"Authorization": "Bearer secret"}
            }
            """.trimIndent(),
        )
        val playback: PlaybackInfo = mapper.mapPlayback(rawPb, fallbackTitle = "Plugin Movie")
        assertEquals(SourceKind.PLUGIN, playback.sourceKind)
        assertTrue(PluginHostGuards.isPluginPlayback(playback))

        // 1. requireNonPluginArtwork rejects plugin artwork
        for (art in listOf(poster, thumb, backdrop, personImg)) {
            try {
                PluginHostGuards.requireNonPluginArtwork(art)
                fail("requireNonPluginArtwork must reject plugin artwork ${art.url}")
            } catch (_: UnsupportedOperationException) {
            }
        }

        // 2. requireNonPluginPlayback rejects plugin PlaybackInfo
        try {
            PluginHostGuards.requireNonPluginPlayback(playback)
            fail("requireNonPluginPlayback must reject plugin PlaybackInfo")
        } catch (_: UnsupportedOperationException) {
        }

        // 3. requireNonPluginFrameProbe rejects plugin source/artwork/playback
        try {
            PluginHostGuards.requireNonPluginFrameProbe(SourceKind.PLUGIN)
            fail("requireNonPluginFrameProbe must reject SourceKind.PLUGIN")
        } catch (_: UnsupportedOperationException) {
        }
        try {
            PluginHostGuards.requireNonPluginFrameProbe(null, artwork = poster)
            fail("requireNonPluginFrameProbe must reject plugin Artwork")
        } catch (_: UnsupportedOperationException) {
        }

        // 4. requireNonPluginCast rejects plugin Item/PlaybackInfo
        try {
            PluginHostGuards.requireNonPluginCast(SourceKind.PLUGIN, info = playback, item = item)
            fail("requireNonPluginCast must reject SourceKind.PLUGIN")
        } catch (_: UnsupportedOperationException) {
        }
        try {
            PluginHostGuards.requireNonPluginCast(null, info = null, item = item)
            fail("requireNonPluginCast must reject Item containing plugin-stamped Artwork")
        } catch (_: UnsupportedOperationException) {
        }

        // Non-plugin Local/WebDAV items pass all guards cleanly
        val localArt = Artwork(url = "content://media/external/video/1", isVideoFrame = true, sourceKind = SourceKind.LOCAL)
        val webdavPb = PlaybackInfo(url = "https://nas.example/movie.mp4", sourceKind = SourceKind.WEBDAV)
        val localItem = Item(id = "loc-1", kind = ItemKind.VIDEO, title = "Local", poster = localArt)
        assertFalse(PluginHostGuards.isPluginArtwork(localArt))
        assertFalse(PluginHostGuards.isPluginPlayback(webdavPb))
        PluginHostGuards.requireNonPluginArtwork(localArt)
        PluginHostGuards.requireNonPluginPlayback(webdavPb)
        PluginHostGuards.requireNonPluginFrameProbe(SourceKind.LOCAL, artwork = localArt)
        PluginHostGuards.requireNonPluginCast(SourceKind.WEBDAV, info = webdavPb, item = localItem)
        assertNotNull(localArt.url)
    }

    private fun stripComments(src: String): String {
        val noBlock = src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return noBlock.replace(Regex("""//[^\n]*"""), " ")
    }
}
