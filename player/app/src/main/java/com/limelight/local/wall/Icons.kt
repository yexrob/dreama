package com.limelight.local.wall

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The mockups' icon set (design-opus/src/lib.py), redrawn as vectors: 24 unit
 * grid, 2.2 stroke, round ends. Thin icons break up at the headset's density,
 * so there is no lighter variant.
 */
object WallIcons {
    val Play = filled("play", "M8 5.6v12.8c0 .9 1 1.4 1.7.9l9.6-6.4a1.1 1.1 0 0 0 0-1.8L9.7 4.7C9 4.2 8 4.7 8 5.6z")
    val Gear = stroked("gear", gear(), circle(12f, 12f, 3.1f))
    val ChevL = stroked("chev-l", "M14.5 5.5 8 12l6.5 6.5")
    val ChevR = stroked("chev-r", "M9.5 5.5 16 12l-6.5 6.5")
    val Plus = stroked("plus", "M12 5v14M5 12h14")
    val Close = stroked("close", "M6.5 6.5l11 11M17.5 6.5l-11 11")
    val Restart = stroked("restart", "M5 12a7 7 0 1 0 2.1-5", "M5 4.8V9h4.2")
    val Cast = stroked("cast", "M9 4.5h10a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-5", rect(3f, 10f, 7f, 10.5f, 1.8f))
    val Computer = stroked("computer", rect(3f, 4.5f, 18f, 12f, 2f), "M9 20h6M12 16.5V20")
    val Tablet = stroked("tablet", rect(5f, 3f, 14f, 18f, 2.5f), "M10.8 18h2.4")
    val Phone = stroked("phone", rect(7f, 2.8f, 10f, 18.4f, 2.4f), "M10.8 18.2h2.4")
    val Server = stroked("server", rect(4f, 4f, 16f, 7f, 1.8f), rect(4f, 13f, 16f, 7f, 1.8f), "M8 7.5h.01M8 16.5h.01")
    val Cloud = stroked("cloud", "M7.6 18.5h9.6a3.9 3.9 0 0 0 .5-7.8 5.5 5.5 0 0 0-10.6 1.5 3.2 3.2 0 0 0 .5 6.3z")
    val Folder = stroked("folder",
        "M3.5 7.3a1.9 1.9 0 0 1 1.9-1.9h3.9l2 2.1h7.3a1.9 1.9 0 0 1 1.9 1.9v7.9a1.9 1.9 0 0 1-1.9 1.9H5.4a1.9 1.9 0 0 1-1.9-1.9z")
    val Headset = stroked("headset",
        "M3.5 9.6A2.6 2.6 0 0 1 6.1 7h11.8a2.6 2.6 0 0 1 2.6 2.6v4.8a2.6 2.6 0 0 1-2.6 2.6h-2.7l-1.9-2.2a1.7 1.7 0 0 0-2.6 0L8.8 17H6.1a2.6 2.6 0 0 1-2.6-2.6z")
    val Refresh = stroked("refresh", "M19 12a7 7 0 1 1-2.1-5", "M19 4.8V9h-4.2")
    val Alert = stroked("alert", circle(12f, 12f, 8.6f), "M12 7.6v5.2M12 16.3h.01")
    val Spinner = stroked("spinner", "M12 3.5a8.5 8.5 0 1 1-8.5 8.5")

    private fun stroked(name: String, vararg paths: String) = build(name, paths, filled = false)

    private fun filled(name: String, vararg paths: String) = build(name, paths, filled = true)

    private fun build(name: String, paths: Array<out String>, filled: Boolean): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.White) else null,
                    stroke = if (filled) null else SolidColor(Color.White),
                    strokeLineWidth = 2.2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}" +
            "h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

    // Eight teeth, the same outline lib.py computes for the mockups
    private fun gear(): String {
        val pts = ArrayList<String>()
        for (i in 0 until 8) {
            for ((r, d) in listOf(7.3f to -17f, 9.6f to -9f, 9.6f to 9f, 7.3f to 17f)) {
                val t = Math.toRadians((i * 45 + d).toDouble())
                pts += "%.2f %.2f".format(java.util.Locale.ROOT, 12 + r * cos(t), 12 + r * sin(t))
            }
        }
        return "M" + pts.joinToString(" L") + " Z"
    }
}
