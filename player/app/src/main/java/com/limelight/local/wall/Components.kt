package com.limelight.local.wall

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.limelight.local.media.Artwork
import com.limelight.local.media.Artworks
import com.limelight.local.media.MediaSource
import com.limelight.local.plugin.PluginHostGuards
import com.limelight.local.plugin.PluginImages
import com.limelight.local.plugin.PluginMediaSource
import kotlinx.coroutines.delay

/**
 * Current [MediaSource] in scope for artwork loading so plugin items always route
 * through [PluginImages.loadBitmap] with the source's [com.limelight.local.plugin.NetworkPolicy].
 */
val LocalMediaSource = compositionLocalOf<MediaSource?> { null }

/**
 * Hover from the controller ray, held 80 ms after it leaves. The ray shakes
 * a few px at panel distance, and without the hold an edge flickers between
 * two targets (ux-spec §3.1).
 */
@Composable
fun rememberRayHover(source: MutableInteractionSource): State<Boolean> {
    val raw by source.collectIsHoveredAsState()
    val held = remember { mutableStateOf(false) }
    LaunchedEffect(raw) {
        if (raw) {
            held.value = true
        } else {
            delay(80)
            held.value = false
        }
    }
    return held
}

/**
 * Everything the ray can point at. A trigger pull arrives as a plain touch,
 * so this is an ordinary click; the ripple is off because the design answers
 * hover with scale and fill instead (ux-spec §7).
 */
fun Modifier.ray(source: MutableInteractionSource, onClick: () -> Unit): Modifier =
    hoverable(source).clickable(interactionSource = source, indication = null, onClick = onClick)

@Composable
private fun hoverScale(hovered: Boolean, to: Float, ms: Int): Float {
    val s by animateFloatAsState(if (hovered) to else 1f, tween(ms, easing = Wall.Ease), label = "hover")
    return s
}

/** Scale plus the lifted shadow posters and cards take on hover. */
private fun Modifier.lift(scale: Float, shape: Shape): Modifier = zIndex(if (scale > 1f) 1f else 0f)
    .graphicsLayer {
        scaleX = scale
        scaleY = scale
        // 0 18 44 rgba(0,0,0,.6) in the mockups: about 24 of elevation
        shadowElevation = (scale - 1f) / 0.06f * 24.dp.toPx()
        this.shape = shape
        clip = true
    }

enum class ButtonKind { Primary, Plain, Ghost }

@Composable
fun WallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: ButtonKind = ButtonKind.Plain,
    small: Boolean = false,
    trailing: ImageVector? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val effectiveHover = hovered && enabled
    val bg by animateColorAsState(
        when (kind) {
            ButtonKind.Primary -> if (effectiveHover) Color.White else Wall.Text
            ButtonKind.Plain -> if (effectiveHover) Wall.FillHi else Wall.Fill
            ButtonKind.Ghost -> if (effectiveHover) Wall.Fill else Color.Transparent
        }, tween(120), label = "btn")
    val fg = when (kind) {
        ButtonKind.Primary -> Wall.Bg
        ButtonKind.Plain -> Wall.Text
        ButtonKind.Ghost -> if (effectiveHover) Wall.Text else Wall.Text2
    }
    val scale = hoverScale(effectiveHover, 1.04f, 120)
    val iconSize = if (small) 24.dp else 28.dp
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .height(if (small) 60.dp else 64.dp)
            .clip(RoundedCornerShape(Wall.Radius))
            .background(bg)
            .then(if (enabled) Modifier.ray(source, onClick) else Modifier)
            .padding(horizontal = if (small) 24.dp else 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(iconSize), tint = fg)
        }
        Text(text, style = if (small) WallType.ButtonSmall else WallType.Button, color = fg, maxLines = 1)
        if (trailing != null) {
            Icon(trailing, null, Modifier.size(iconSize), tint = fg)
        }
    }
}

/** The 64 px round button of the nav bar and the back arrow. */
@Composable
fun RoundButton(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
                filled: Boolean = false, selected: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale = hoverScale(hovered, 1.06f, 120)
    val bg = when {
        hovered -> Wall.FillHi
        selected -> Wall.Sel
        filled -> Wall.Fill
        else -> Color.Transparent
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(64.dp)
            .clip(CircleShape)
            .background(bg)
            .ray(source, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(30.dp), tint = Wall.Text)
    }
}

enum class Tab(val labelRes: Int) {
    Home(com.limelight.R.string.nav_home),
    Library(com.limelight.R.string.nav_library),
    Cast(com.limelight.R.string.nav_cast),
}

/**
 * The bar across the top of every page. Transparent over the home hero so
 * the backdrop runs to the edge, solid everywhere else.
 */
@Composable
fun NavBar(
    selected: Tab?,
    onTab: (Tab) -> Unit,
    onSettings: () -> Unit,
    solid: Boolean,
    settingsSelected: Boolean = false,
) {
    val bg by animateColorAsState(if (solid) Wall.Bg else Color.Transparent, tween(240), label = "nav")
    Row(
        Modifier.fillMaxWidth().height(96.dp).background(bg).padding(horizontal = Wall.Margin),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (t in Tab.entries) {
            TabChip(androidx.compose.ui.res.stringResource(t.labelRes), t == selected) { onTab(t) }
        }
        Spacer(Modifier.weight(1f))
        RoundButton(WallIcons.Gear, onSettings, selected = settingsSelected)
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val bg = when {
        selected -> Wall.Sel
        hovered -> Wall.Fill
        else -> Color.Transparent
    }
    Box(
        Modifier.height(60.dp).clip(RoundedCornerShape(30.dp)).background(bg)
            .ray(source, onClick).padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = WallType.Label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected || hovered) Wall.Text else Wall.Text2)
    }
}

/** A row title on the home wall; with [onClick] it opens the category and grows a chevron. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    Row(
        modifier.then(if (onClick != null) Modifier.ray(source, onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = WallType.Head)
        if (onClick != null) {
            Icon(WallIcons.ChevR, null, Modifier.size(28.dp), tint = if (hovered) Wall.Text else Wall.Text2)
        }
    }
}

/** A line of the left list in 媒体库 and of settings-like lists: 88 tall, a lead icon, a sub line. */
@Composable
fun ListRow(label: String, modifier: Modifier = Modifier, sub: String? = null, subDanger: Boolean = false,
            lead: ImageVector? = null, selected: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val bg by animateColorAsState(
        when {
            selected -> Wall.Sel
            hovered -> Wall.SurfaceHi
            else -> Color.Transparent
        }, tween(120), label = "row")
    Row(
        modifier.fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(Wall.Radius)).background(bg)
            .ray(source, onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (lead != null) {
            Icon(lead, null, Modifier.size(36.dp), tint = if (selected) Wall.Text else Wall.Text2)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = WallType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(sub, style = WallType.Caption.copy(lineHeight = 32.sp),
                    color = if (subDanger) Wall.Danger else Wall.Text2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
fun Notice(text: String, icon: ImageVector = WallIcons.Alert, danger: Boolean = true) {
    val c = if (danger) Wall.Danger else Wall.Text2
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(26.dp), tint = c)
        Text(text, style = WallType.Caption, color = c)
    }
}

/**
 * Loads [art] for drawing. Null until the frame is ready, and stays null if
 * there is none, in which case the caller draws its placeholder.
 *
 * Plugin artwork is ALWAYS loaded via [PluginImages.loadBitmap] under the
 * active [PluginMediaSource]'s NetworkPolicy and cached in [Artworks]'s single
 * shared LRU cache — never passed to [Artworks.load].
 */
@Composable
fun rememberArt(
    art: Artwork?,
    size: Artworks.Size,
    source: MediaSource? = LocalMediaSource.current,
): State<Bitmap?> {
    val context = LocalContext.current
    val pluginSource = source as? PluginMediaSource
    val isPluginArt = art != null && (PluginHostGuards.isPluginArtwork(art) || pluginSource != null)
    val pluginCacheKey = if (art != null && isPluginArt && pluginSource != null) {
        "plugin:${pluginSource.instanceId}:${art.cacheKey}@${size.px}"
    } else {
        null
    }
    val cachedNow = when {
        art == null -> null
        isPluginArt -> pluginCacheKey?.let { Artworks.cachedByKey(it) }
        else -> Artworks.cached(art, size)
    }
    return produceState(cachedNow, art, size, pluginCacheKey, isPluginArt) {
        // The state outlives a change of art (the hero moving on), so start clean
        value = when {
            art == null -> null
            isPluginArt -> pluginCacheKey?.let { Artworks.cachedByKey(it) }
            else -> Artworks.cached(art, size)
        }
        if (art != null && value == null) {
            value = if (isPluginArt) {
                if (pluginSource != null && pluginCacheKey != null) {
                    runCatching {
                        PluginImages.loadBitmap(pluginSource, art, maxEdgePx = size.px)
                    }.getOrNull()?.also { bmp ->
                        Artworks.putCachedByKey(pluginCacheKey, bmp)
                    }
                } else {
                    null
                }
            } else {
                Artworks.load(context, art, size)
            }
        }
    }
}

/**
 * The art of an item, or a placeholder while it loads or when there is none.
 * The placeholder takes its colours from the title so a row of remote files
 * is not one grey block, and the image fades in over it (240 ms, ux-spec §5).
 */
@Composable
fun ArtBox(
    art: Artwork?,
    seed: String,
    size: Artworks.Size,
    modifier: Modifier = Modifier,
    source: MediaSource? = LocalMediaSource.current,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val bmp by rememberArt(art, size, source)
    Box(modifier.background(placeholder(seed))) {
        AnimatedVisibility(bmp != null, enter = fadeIn(tween(240)), exit = fadeOut(tween(0))) {
            bmp?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        content()
    }
}

// The base gradients of the mockups' film scenes (base.css .sc-*)
private val SCENES = listOf(
    listOf(0xFF4D5B63, 0xFF7C898D, 0xFF55626A, 0xFF3A454B),
    listOf(0xFF1E3245, 0xFF4C677D, 0xFF36566C, 0xFF182B39),
    listOf(0xFF0B1222, 0xFF1A2440, 0xFF090D15, 0xFF090D15),
    listOf(0xFFE8CC95, 0xFFDCAB69, 0xFF8A8240, 0xFF59592A),
    listOf(0xFF9CAB92, 0xFFCFD0B2, 0xFF66734D, 0xFF3E4732),
    listOf(0xFF252A4B, 0xFF7B4E67, 0xFFD6925F, 0xFFE7B27A),
    listOf(0xFF6A4470, 0xFF52325A, 0xFF36203E, 0xFF211327),
    listOf(0xFF13221E, 0xFF13221E, 0xFF0D1714, 0xFF0D1714),
    listOf(0xFF5A2E22, 0xFFA5573B, 0xFF6B3524, 0xFF3B1E16),
)

private fun placeholder(seed: String): Brush {
    val c = SCENES[Math.floorMod(seed.hashCode(), SCENES.size)].map { Color(it) }
    // Dimmed: these stand in for art, and full strength they outshout real frames
    return Brush.verticalGradient(c.map { lerpToBg(it) })
}

private fun lerpToBg(c: Color) = Color(
    red = c.red * 0.7f + Wall.Bg.red * 0.3f,
    green = c.green * 0.7f + Wall.Bg.green * 0.3f,
    blue = c.blue * 0.7f + Wall.Bg.blue * 0.3f,
)

private val PosterShape = RoundedCornerShape(Wall.RadiusPoster)

/**
 * 2:3 poster with the title on it. Local videos have no poster, so this is a
 * frame cropped to the middle, with a shade under the title so it reads.
 */
@Composable
fun Poster(title: String, art: Artwork?, onClick: () -> Unit, modifier: Modifier = Modifier,
           width: Dp = 168.dp, height: Dp = 252.dp, dim: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale = hoverScale(hovered, 1.06f, 140)
    ArtBox(art, title, Artworks.Size.CARD,
        modifier.width(width).height(height).lift(scale, PosterShape)
            .graphicsLayer { alpha = if (dim) 0.4f else 1f }.ray(source, onClick)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.45f to Color.Transparent, 1f to Wall.Bg.copy(alpha = 0.82f))))
        Text(title, style = WallType.Head.copy(fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = 0.5.sp),
            color = Wall.PosterText, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 18.dp))
    }
}

/**
 * A folder with no art of its own: up to four of its videos in a 2×2 grid,
 * the name over a shade. One frame alone would read as a single video.
 */
@Composable
fun FolderTile(title: String, caption: String?, previews: List<Artwork>, onClick: () -> Unit,
               modifier: Modifier = Modifier, selected: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale = hoverScale(hovered, 1.06f, 140)
    Box(modifier.lift(scale, RoundedCornerShape(Wall.Radius)).background(Wall.Surface).ray(source, onClick)) {
        if (previews.isEmpty()) {
            Box(Modifier.fillMaxSize().background(placeholder(title)))
        } else {
            val cells = if (previews.size >= 4) previews.take(4) else listOf(previews[0])
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                cells.chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        pair.forEach { ArtBox(it, title, Artworks.Size.CARD, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.35f to Color.Transparent, 1f to Wall.Bg.copy(alpha = 0.78f))))
        if (selected) {
            Box(Modifier.fillMaxSize().background(Wall.Sel))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
            Text(title, style = WallType.Head, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) {
                Text(caption, style = WallType.Caption, color = Color(0xFFC9CBD0))
            }
        }
    }
}

private val CardShape = RoundedCornerShape(Wall.RadiusPoster)

/**
 * 继续观看: a 16:9 frame with the progress bar on it and the title under it.
 * On hover a play glyph says that a click resumes rather than opens details.
 */
@Composable
fun ContinueCard(title: String, meta: String, art: Artwork?, progress: Float, onClick: () -> Unit,
                 modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val scale = hoverScale(hovered, 1.06f, 140)
    Column(modifier.width(264.dp).ray(source, onClick)) {
        ArtBox(art, title, Artworks.Size.CARD, Modifier.fillMaxWidth().aspectRatio(16 / 9f).lift(scale, CardShape)) {
            Box(Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                .fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x990E0F12))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(3.dp)).background(Wall.Accent))
            }
            // Spelled out: inside the card's Column the ColumnScope overload would win and is not allowed here
            androidx.compose.animation.AnimatedVisibility(hovered, Modifier.align(Alignment.Center),
                enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xEBECECEE)),
                    contentAlignment = Alignment.Center) {
                    Icon(WallIcons.Play, null, Modifier.size(28.dp).offset(x = 2.dp), tint = Wall.Bg)
                }
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = WallType.Caption.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(meta, style = WallType.Caption, color = Wall.Text2, maxLines = 1)
        }
    }
}

/**
 * A grey block that breathes, the same size as what it stands for. It only
 * shows after 300 ms: anything faster than that would just flash.
 */
@Composable
fun Skeleton(modifier: Modifier, radius: Dp = 12.dp) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        shown = true
    }
    if (!shown) {
        Box(modifier)
        return
    }
    val pulse = rememberInfiniteTransition(label = "sk")
    val a by pulse.animateFloat(1f, 0.55f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "sk")
    Box(modifier.graphicsLayer { alpha = a }.clip(RoundedCornerShape(radius)).background(Wall.Surface))
}

/** A big choice tile: the empty state's three ways in, the cast page's PCs. */
@Composable
fun OptionTile(title: String, sub: String?, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier,
               iconSize: Dp = 40.dp, horizontal: Boolean = false, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val active = hovered && enabled
    val scale = hoverScale(active, 1.03f, 140)
    val bg by animateColorAsState(if (active) Wall.SurfaceHi else Wall.Surface, tween(140), label = "opt")
    val body: @Composable () -> Unit = {
        Text(title, style = if (horizontal) WallType.Label else WallType.Head,
            color = if (enabled) Wall.Text else Wall.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(sub, style = WallType.Caption, color = if (active) Wall.Text else Wall.Text2,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val m = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(if (horizontal) Wall.Radius else 20.dp)).background(bg)
        .then(if (enabled) Modifier.ray(source, onClick) else Modifier.hoverable(source))
    if (horizontal) {
        Row(m.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(icon, null, Modifier.size(iconSize), tint = Wall.Text2)
            Column(Modifier.weight(1f)) { body() }
        }
    } else {
        Column(m.padding(32.dp)) {
            Icon(icon, null, Modifier.size(iconSize), tint = Wall.Text2)
            Spacer(Modifier.height(22.dp))
            body()
        }
    }
}

/** One turn a second linear spinner matching cast.html .spin. */
@Composable
fun WallSpinner(modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = Wall.Text) {
    val turn = rememberInfiniteTransition(label = "wall-spin")
    val angle by turn.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "wall-spin",
    )
    Icon(WallIcons.Spinner, null, modifier.size(size).graphicsLayer { rotationZ = angle }, tint = tint)
}

/** 14px pulsing waiting dot matching cast.html .breath. */
@Composable
fun BreathingDot(color: Color = Wall.Text2) {
    val pulse = rememberInfiniteTransition(label = "wall-breath")
    val a by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "wall-breath")
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
    }
}

/** Segmented pill group matching settings.html .seg / .seg-b. */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Wall.Surface)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, label) in options) {
            val isSel = value == selected
            val source = remember { MutableInteractionSource() }
            val hovered by rememberRayHover(source)
            val bg by animateColorAsState(
                when {
                    isSel -> Wall.Sel
                    hovered -> Wall.Fill
                    else -> Color.Transparent
                },
                tween(120),
                label = "seg",
            )
            Box(
                Modifier
                    .height(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .ray(source) { onSelect(value) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = WallType.ButtonSmall.copy(
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (isSel || hovered) Wall.Text else Wall.Text2,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Amber toggle switch matching settings.html .sw / .sw.on (68x40 with 30dp thumb). */
@Composable
fun WallSwitch(checked: Boolean, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by rememberRayHover(source)
    val track by animateColorAsState(
        when {
            checked -> Wall.Accent
            hovered -> Wall.FillHi
            else -> Wall.Fill
        },
        tween(160),
        label = "sw-track",
    )
    val thumbX by animateFloatAsState(if (checked) 28f else 0f, tween(160, easing = Wall.Ease), label = "sw-x")
    Box(
        modifier
            .size(68.dp, 40.dp)
            .clip(CircleShape)
            .background(track)
            .ray(source) { onToggle(!checked) }
            .padding(5.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbX.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(if (checked) Wall.Bg else Wall.Text),
        )
    }
}

/** Crisp black-on-white QR code rendered from [com.limelight.local.stream.QrCodeMatrix]. */
@Composable
fun QrCodeView(matrix: com.limelight.local.stream.QrCodeMatrix, size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White),
    ) {
        val quiet = 3
        val total = matrix.size + quiet * 2
        val cell = this.size.width / total
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset((x + quiet) * cell, (y + quiet) * cell),
                        size = androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
