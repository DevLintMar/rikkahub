package me.rerere.rikkahub.ui.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本地补的图标 —— 绕开 `huge-icons 1.3` 那个「少画一段」的缺陷。
 *
 * `com.github.rikkahub:hugeicons-compose:1.3` 的生成器把**用 SVG 弧线画出来的那段 path
 * 整段丢掉了**（1.3 的 4688 个图标里含 `arcTo(` 的 0 个，1.4 是 499 个），于是这些图标在
 * 屏幕上只画出剩下的一半：`Globe02` 没有外圆（偏好设置的「网络」项只剩一条横线 + 一个竖梭形）、
 * `StopCircle` 只剩一个方块、`AlertCircle` 只剩一个感叹号。
 *
 * 这里用 **1.4 的几何 + 1.3 的描边风格**（2f、Round cap/join，与同屏其它 1.3 图标一致）补回来。
 * 对这 10 个图标来说 1.4 只是「1.3 + 被丢掉的那段」（如 `Globe02` 的竖子午线与两条纬线在 1.4 里
 * 逐字节相同），所以补出来的是原设计，不是换了另一套字形。
 *
 * **不要**为了「统一」把这些调用点改回 `HugeIcons.*`：1.3 一天不换，它们就一天画不全。
 * 门禁见 `docs/superpowers/scripts/hugeicons_glyph_audit.py`（在 nightly 里跑）；换到 1.4 时
 * 应当删掉本文件、把调用点改回 `HugeIcons.*`，并 `--refresh` 重算该脚本的清单。
 */
object ForkIcons {
    val AlertCircle: ImageVector by lazy { forkIcon("AlertCircle") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 12f)
            arcTo(10f, 10f, 0f, true, false, 2f, 12f)
            arcTo(10f, 10f, 0f, true, false, 22f, 12f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 8f)
            verticalLineTo(12f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12.125f, 15.75f)
            horizontalLineTo(12f)
            moveTo(12.25f, 15.75f)
            curveTo(12.25f, 15.8881f, 12.1381f, 16f, 12f, 16f)
            curveTo(11.8619f, 16f, 11.75f, 15.8881f, 11.75f, 15.75f)
            curveTo(11.75f, 15.6119f, 11.8619f, 15.5f, 12f, 15.5f)
            curveTo(12.1381f, 15.5f, 12.25f, 15.6119f, 12.25f, 15.75f)
            close()
        }
    } }

    val Database02: ImageVector by lazy { forkIcon("Database02") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 5f)
            arcTo(8f, 3f, 0f, true, false, 4f, 5f)
            arcTo(8f, 3f, 0f, true, false, 20f, 5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 12f)
            curveTo(20f, 13.6569f, 16.4183f, 15f, 12f, 15f)
            curveTo(7.58172f, 15f, 4f, 13.6569f, 4f, 12f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 5f)
            verticalLineTo(19f)
            curveTo(20f, 20.6569f, 16.4183f, 22f, 12f, 22f)
            curveTo(7.58172f, 22f, 4f, 20.6569f, 4f, 19f)
            verticalLineTo(5f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8f, 8f)
            verticalLineTo(10f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8f, 15f)
            verticalLineTo(17f)
        }
    } }

    val DatabaseRestore: ImageVector by lazy { forkIcon("DatabaseRestore") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 5f)
            arcTo(8f, 3f, 0f, true, false, 3f, 5f)
            arcTo(8f, 3f, 0f, true, false, 19f, 5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 10.8418f)
            curveTo(6.60158f, 11.0226f, 7.27434f, 11.1716f, 8f, 11.2817f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 17.8418f)
            curveTo(6.60158f, 18.0226f, 7.27434f, 18.1716f, 8f, 18.2817f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19.842f, 13f)
            lineTo(20.4127f, 15.3449f)
            lineTo(19.4647f, 14.7618f)
            curveTo(18.7894f, 14.2569f, 17.9501f, 13.9576f, 17.0404f, 13.9576f)
            curveTo(14.809f, 13.9576f, 13f, 15.7579f, 13f, 17.9788f)
            curveTo(13f, 20.1996f, 14.809f, 22f, 17.0404f, 22f)
            curveTo(18.9951f, 22f, 20.6256f, 20.6185f, 21f, 18.783f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19f, 5f)
            verticalLineTo(10f)
            moveTo(3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.6569f, 6.58172f, 22f, 11f, 22f)
            curveTo(11.0849f, 22f, 11.1694f, 21.9995f, 11.2537f, 21.9985f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(3f, 12f)
            curveTo(3f, 13.616f, 6.40729f, 14.9336f, 10.6748f, 14.9976f)
        }
    } }

    val Globe02: ImageVector by lazy { forkIcon("Globe02") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 12f)
            arcTo(10f, 10f, 0f, true, false, 2f, 12f)
            arcTo(10f, 10f, 0f, true, false, 22f, 12f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8f, 12f)
            curveTo(8f, 18f, 12f, 22f, 12f, 22f)
            curveTo(12f, 22f, 16f, 18f, 16f, 12f)
            curveTo(16f, 6f, 12f, 2f, 12f, 2f)
            curveTo(12f, 2f, 8f, 6f, 8f, 12f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 15f)
            horizontalLineTo(3f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 9f)
            horizontalLineTo(3f)
        }
    } }

    val Image03: ImageVector by lazy { forkIcon("Image03") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 3f)
            horizontalLineTo(10f)
            curveTo(6.22876f, 3f, 4.34315f, 3f, 3.17157f, 4.17157f)
            curveTo(2f, 5.34315f, 2f, 7.22876f, 2f, 11f)
            verticalLineTo(13f)
            curveTo(2f, 16.7712f, 2f, 18.6569f, 3.17157f, 19.8284f)
            curveTo(4.34315f, 21f, 6.22876f, 21f, 10f, 21f)
            horizontalLineTo(14f)
            curveTo(17.7712f, 21f, 19.6569f, 21f, 20.8284f, 19.8284f)
            curveTo(22f, 18.6569f, 22f, 16.7712f, 22f, 13f)
            verticalLineTo(11f)
            curveTo(22f, 7.22876f, 22f, 5.34315f, 20.8284f, 4.17157f)
            curveTo(19.6569f, 3f, 17.7712f, 3f, 14f, 3f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 8.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 7f, 8.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 10f, 8.5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21.5f, 17f)
            lineTo(16.348f, 11.3797f)
            curveTo(16.1263f, 11.1377f, 15.8131f, 11f, 15.485f, 11f)
            curveTo(15.1744f, 11f, 14.8766f, 11.1234f, 14.6571f, 11.3429f)
            lineTo(10f, 16f)
            lineTo(7.83928f, 13.8393f)
            curveTo(7.62204f, 13.622f, 7.32741f, 13.5f, 7.02019f, 13.5f)
            curveTo(6.68931f, 13.5f, 6.37423f, 13.6415f, 6.15441f, 13.8888f)
            lineTo(2.5f, 18f)
        }
    } }

    val LanguageCircle: ImageVector by lazy { forkIcon("LanguageCircle") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 12f)
            arcTo(10f, 10f, 0f, true, false, 2f, 12f)
            arcTo(10f, 10f, 0f, true, false, 22f, 12f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 8.37931f)
            horizontalLineTo(11.5f)
            moveTo(17f, 8.37931f)
            horizontalLineTo(14.5f)
            moveTo(11.5f, 8.37931f)
            horizontalLineTo(14.5f)
            moveTo(11.5f, 8.37931f)
            verticalLineTo(7f)
            moveTo(14.5f, 8.37931f)
            curveTo(13.9725f, 10.2656f, 12.8679f, 12.0487f, 11.6071f, 13.6158f)
            moveTo(8.39286f, 17f)
            curveTo(9.41205f, 16.0628f, 10.5631f, 14.9134f, 11.6071f, 13.6158f)
            moveTo(11.6071f, 13.6158f)
            curveTo(10.9643f, 12.8621f, 10.0643f, 11.6426f, 9.80714f, 11.0909f)
            moveTo(11.6071f, 13.6158f)
            lineTo(13.5357f, 15.6207f)
        }
    } }

    val MusicNote03: ImageVector by lazy { forkIcon("MusicNote03") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 18.5f)
            arcTo(3.5f, 3.5f, 0f, true, false, 3f, 18.5f)
            arcTo(3.5f, 3.5f, 0f, true, false, 10f, 18.5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 16f)
            arcTo(3f, 3f, 0f, true, false, 15f, 16f)
            arcTo(3f, 3f, 0f, true, false, 21f, 16f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 18.5f)
            lineTo(10f, 7f)
            curveTo(10f, 6.07655f, 10f, 5.61483f, 10.2635f, 5.32794f)
            curveTo(10.5269f, 5.04106f, 11.0175f, 4.9992f, 11.9986f, 4.91549f)
            curveTo(16.022f, 4.57222f, 18.909f, 3.26005f, 20.3553f, 2.40978f)
            curveTo(20.6508f, 2.236f, 20.7986f, 2.14912f, 20.8993f, 2.20672f)
            curveTo(21f, 2.26432f, 21f, 2.4315f, 21f, 2.76587f)
            verticalLineTo(16f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(10f, 10f)
            curveTo(15.8667f, 10f, 19.7778f, 7.66667f, 21f, 7f)
        }
    } }

    val PaintBoard: ImageVector by lazy { forkIcon("PaintBoard") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 12f)
            curveTo(22f, 6.47715f, 17.5228f, 2f, 12f, 2f)
            curveTo(6.47715f, 2f, 2f, 6.47715f, 2f, 12f)
            curveTo(2f, 17.5228f, 6.47715f, 22f, 12f, 22f)
            curveTo(12.8417f, 22f, 14f, 22.1163f, 14f, 21f)
            curveTo(14f, 20.391f, 13.6832f, 19.9212f, 13.3686f, 19.4544f)
            curveTo(12.9082f, 18.7715f, 12.4523f, 18.0953f, 13f, 17f)
            curveTo(13.6667f, 15.6667f, 14.7778f, 15.6667f, 16.4815f, 15.6667f)
            curveTo(17.3334f, 15.6667f, 18.3334f, 15.6667f, 19.5f, 15.5f)
            curveTo(21.601f, 15.1999f, 22f, 13.9084f, 22f, 12f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11f, 8.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 8f, 8.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 11f, 8.5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 9.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 15f, 9.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 18f, 9.5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7.125f, 15f)
            horizontalLineTo(7f)
            moveTo(7.25f, 15f)
            curveTo(7.25f, 15.1381f, 7.13807f, 15.25f, 7f, 15.25f)
            curveTo(6.86193f, 15.25f, 6.75f, 15.1381f, 6.75f, 15f)
            curveTo(6.75f, 14.8619f, 6.86193f, 14.75f, 7f, 14.75f)
            curveTo(7.13807f, 14.75f, 7.25f, 14.8619f, 7.25f, 15f)
            close()
        }
    } }

    val StopCircle: ImageVector by lazy { forkIcon("StopCircle") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(22f, 12f)
            arcTo(10f, 10f, 0f, true, false, 2f, 12f)
            arcTo(10f, 10f, 0f, true, false, 22f, 12f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9.38886f, 15.1629f)
            curveTo(9.89331f, 15.5f, 10.5955f, 15.5f, 12f, 15.5f)
            curveTo(13.4045f, 15.5f, 14.1067f, 15.5f, 14.6111f, 15.1629f)
            curveTo(14.8295f, 15.017f, 15.017f, 14.8295f, 15.1629f, 14.6111f)
            curveTo(15.5f, 14.1067f, 15.5f, 13.4045f, 15.5f, 12f)
            curveTo(15.5f, 10.5955f, 15.5f, 9.89331f, 15.1629f, 9.38886f)
            curveTo(15.017f, 9.17048f, 14.8295f, 8.98298f, 14.6111f, 8.83706f)
            curveTo(14.1067f, 8.5f, 13.4045f, 8.5f, 12f, 8.5f)
            curveTo(10.5955f, 8.5f, 9.89331f, 8.5f, 9.38886f, 8.83706f)
            curveTo(9.17048f, 8.98298f, 8.98298f, 9.17048f, 8.83706f, 9.38886f)
            curveTo(8.5f, 9.89331f, 8.5f, 10.5955f, 8.5f, 12f)
            curveTo(8.5f, 13.4045f, 8.5f, 14.1067f, 8.83706f, 14.6111f)
            curveTo(8.98298f, 14.8295f, 9.17048f, 15.017f, 9.38886f, 15.1629f)
            close()
        }
    } }

    val Video01: ImageVector by lazy { forkIcon("Video01") {
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 11f)
            curveTo(2f, 7.70017f, 2f, 6.05025f, 3.02513f, 5.02513f)
            curveTo(4.05025f, 4f, 5.70017f, 4f, 9f, 4f)
            horizontalLineTo(10f)
            curveTo(13.2998f, 4f, 14.9497f, 4f, 15.9749f, 5.02513f)
            curveTo(17f, 6.05025f, 17f, 7.70017f, 17f, 11f)
            verticalLineTo(13f)
            curveTo(17f, 16.2998f, 17f, 17.9497f, 15.9749f, 18.9749f)
            curveTo(14.9497f, 20f, 13.2998f, 20f, 10f, 20f)
            horizontalLineTo(9f)
            curveTo(5.70017f, 20f, 4.05025f, 20f, 3.02513f, 18.9749f)
            curveTo(2f, 17.9497f, 2f, 16.2998f, 2f, 13f)
            verticalLineTo(11f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(17f, 8.90585f)
            lineTo(17.1259f, 8.80196f)
            curveTo(19.2417f, 7.05623f, 20.2996f, 6.18336f, 21.1498f, 6.60482f)
            curveTo(22f, 7.02628f, 22f, 8.42355f, 22f, 11.2181f)
            verticalLineTo(12.7819f)
            curveTo(22f, 15.5765f, 22f, 16.9737f, 21.1498f, 17.3952f)
            curveTo(20.2996f, 17.8166f, 19.2417f, 16.9438f, 17.1259f, 15.198f)
            lineTo(17f, 15.0941f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color(0xFF000000)),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(13f, 9.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 10f, 9.5f)
            arcTo(1.5f, 1.5f, 0f, true, false, 13f, 9.5f)
            close()
        }
    } }

}

private fun forkIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "Fork$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()
