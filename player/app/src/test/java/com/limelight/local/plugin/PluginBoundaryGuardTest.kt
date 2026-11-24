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
 * Architecture boundary guard enforcing `docs/specs/plugin-host-integration.md`.
 *
 * Because the Android app currently lives in a single `:app` Gradle module,
 * Kotlin `internal` visibility is module-wide rather than package-private.
 * This test scans every `.kt` and `.java` source file outside `local/plugin/`
 * so any bypass of [PluginPlayback] or [PluginImages.loadBitmap] fails CI immediately.
 */
class PluginBoundaryGuardTest {

    @Test
    fun enforcesPluginPlaybackAndImageBoundaryOutsidePluginPackage() {
        val repoRoot = ManifestAndPermissionTest.locateRepoRoot()
        val mainJavaRoot = File(repoRoot, "player/app/src/main/java")
        assertTrue("Expected Android source root at $mainJavaRoot", mainJavaRoot.isDirectory)

        val pluginPkgPrefix = "com/limelight/local/plugin/"
        val localPkgPrefix = "com/limelight/local/"

        // Internal plugin runtime classes that must NEVER be referenced outside local/plugin/
        val forbiddenInternalSymbols = listOf(
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

        // Pre-existing non-plugin files in local/ audited as legacy Local/WebDAV-only paths.
        // None of these may mix SourceKind.PLUGIN / PluginMediaSource with raw HTTP/player calls;
        // any NEW file outside local/plugin/ using PlaybackInfo.url/headers or raw HTTP clients fails.
        val legacyLocalRawClientFiles = setOf(
            "com/limelight/local/LocalPlayerXR.java",
            "com/limelight/local/WebDav.java",
            "com/limelight/local/media/Artworks.kt",
            "com/limelight/local/media/Model.kt",
            "com/limelight/local/wall/WallActivity.kt",
            "com/limelight/local/wall/Components.kt",
        )

        val rawPlaybackOrHttpPatterns = listOf(
            Regex("""\bDefaultHttpDataSource\b""") to "DefaultHttpDataSource (must use PluginPlayback for plugins)",
            Regex("""\bOkHttpClient\b""") to "raw OkHttpClient (must use PluginImages.loadBitmap / PluginPlayback for plugins)",
            Regex("""\bHttpURLConnection\b""") to "HttpURLConnection (must use PluginImages.loadBitmap / PluginPlayback for plugins)",
            Regex("""\bMediaMetadataRetriever\b""") to "MediaMetadataRetriever (forbidden for plugin URLs; use PluginImages.loadBitmap / ExoPlayer Format)",
            Regex("""\bArtworks\.load\b""") to "Artworks.load (forbidden for plugin artwork; use PluginImages.loadBitmap)",
        )
        val rawPlaybackFieldAccess = Regex("""\b[a-zA-Z_][a-zA-Z0-9_]*\.(?:url|headers)\b""")
        val playbackTypeMention = Regex("""\bPlaybackInfo\b""")
        val pluginMarkerRegex = Regex("""\b(?:PluginMediaSource|SourceKind\.PLUGIN)\b""")

        val violations = ArrayList<String>()

        mainJavaRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val rel = file.relativeTo(mainJavaRoot).path.replace(File.separatorChar, '/')
                if (rel.startsWith(pluginPkgPrefix)) return@forEach

                val text = file.readText()
                val codeOnly = stripComments(text)

                // Rule 1: ZERO references to plugin-internal classes/helpers anywhere outside local/plugin/
                for (sym in forbiddenInternalSymbols) {
                    if (Regex("""\b${Regex.escape(sym)}\b""").containsMatchIn(codeOnly)) {
                        violations += "$rel references internal plugin symbol '$sym' (use PluginPlayback / PluginImages.loadBitmap instead)"
                    }
                }

                // Rule 2: Inside local/, audit raw HTTP/player clients and PlaybackInfo.url/headers access
                if (rel.startsWith(localPkgPrefix)) {
                    if (rel == "com/limelight/local/media/Artworks.kt") {
                        // Artworks.kt is explicitly allowed to check `SourceKind.PLUGIN` ONLY to reject it at runtime
                        if (!codeOnly.contains("art.sourceKind == SourceKind.PLUGIN") ||
                            !codeOnly.contains("throw UnsupportedOperationException")
                        ) {
                            violations += "$rel must retain runtime rejection guard for SourceKind.PLUGIN"
                        }
                        if (codeOnly.contains("PluginMediaSource")) {
                            violations += "$rel must not handle PluginMediaSource directly"
                        }
                        return@forEach
                    }

                    val mentionsPlugin = pluginMarkerRegex.containsMatchIn(codeOnly)
                    val isLegacyAllowlisted = rel in legacyLocalRawClientFiles

                    for ((pattern, desc) in rawPlaybackOrHttpPatterns) {
                        if (pattern.containsMatchIn(codeOnly)) {
                            if (!isLegacyAllowlisted) {
                                violations += "$rel uses $desc outside audited legacy allowlist (see docs/specs/plugin-host-integration.md)"
                            } else if (mentionsPlugin) {
                                violations += "$rel mixes plugin source handling with $desc; isolate plugin playback/artwork strictly through PluginPlayback / PluginImages.loadBitmap"
                            }
                        }
                    }

                    if (playbackTypeMention.containsMatchIn(codeOnly) && rawPlaybackFieldAccess.containsMatchIn(codeOnly)) {
                        if (!isLegacyAllowlisted) {
                            violations += "$rel directly accesses PlaybackInfo.url/headers outside audited legacy allowlist (use PluginPlayback instead)"
                        } else if (mentionsPlugin) {
                            violations += "$rel references plugin source AND directly accesses PlaybackInfo.url/headers (must route plugins through PluginPlayback)"
                        }
                    }
                } else {
                    // Rule 3: Outside local/ (e.g. DLNA/streaming/activities), NEVER reference plugin sources alongside raw HTTP/PlaybackInfo.url
                    if (pluginMarkerRegex.containsMatchIn(codeOnly)) {
                        for ((pattern, desc) in rawPlaybackOrHttpPatterns) {
                            if (pattern.containsMatchIn(codeOnly)) {
                                violations += "$rel outside local/ mixes plugin source with $desc"
                            }
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Plugin security boundary violations found (see docs/specs/plugin-host-integration.md):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    fun runtimeGuardsRejectPluginItemsAndPlaybackAtEveryLegacyHostEntryPoint() {
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

        // 1. Artworks.load / Components.kt guard rejects plugin artwork even without explicit sourceKind argument
        for (art in listOf(poster, thumb, backdrop, personImg)) {
            try {
                PluginHostGuards.requireNonPluginArtwork(art)
                fail("requireNonPluginArtwork must reject plugin artwork ${art.url}")
            } catch (_: UnsupportedOperationException) {
            }
        }

        // 2. WallActivity.launchPlayer / LocalPlayerXR DefaultHttpDataSource guard rejects plugin PlaybackInfo
        try {
            PluginHostGuards.requireNonPluginPlayback(playback)
            fail("requireNonPluginPlayback must reject plugin PlaybackInfo")
        } catch (_: UnsupportedOperationException) {
        }

        // 3. MediaMetadataRetriever / MediaExtractor frame probe guard rejects plugin source/artwork/playback
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

        // 4. DLNA (local/dlna/, local/wall/CastScreen.kt) & out-of-package cast guard rejects plugin Item/PlaybackInfo
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
