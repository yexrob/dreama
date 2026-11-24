package com.limelight.local.plugin

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
        val rawPlaybackFieldAccess = Regex("""\b(?:info|playbackInfo|pb)\.(?:url|headers)\b""")
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

                    if (rawPlaybackFieldAccess.containsMatchIn(codeOnly)) {
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

    private fun stripComments(src: String): String {
        val noBlock = src.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return noBlock.replace(Regex("""//[^\n]*"""), " ")
    }
}
