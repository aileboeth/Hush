package com.aileboeth.hush.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Cohesive custom rounded-stroke 24dp vector icon set tailored for Hush.
 * Zero dependency on `material-icons-extended`, keeping APK compact and visually distinctive.
 */
object HushIcons {

    private fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.9f,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.strokePath(
        strokeWidth: Float = 1.9f,
        builder: PathBuilder.() -> Unit,
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = builder,
        )
    }

    val Pulse: ImageVector by lazy {
        strokeIcon("Pulse") {
            strokePath {
                moveTo(3f, 12f)
                lineTo(7f, 12f)
                lineTo(9.8f, 5.5f)
                lineTo(14.2f, 18.5f)
                lineTo(17f, 12f)
                lineTo(21f, 12f)
            }
        }
    }

    val Layers: ImageVector by lazy {
        strokeIcon("Layers") {
            strokePath {
                moveTo(12f, 3.5f)
                lineTo(3.5f, 8f)
                lineTo(12f, 12.5f)
                lineTo(20.5f, 8f)
                close()
            }
            strokePath {
                moveTo(3.5f, 12.5f)
                lineTo(12f, 17f)
                lineTo(20.5f, 12.5f)
            }
            strokePath {
                moveTo(3.5f, 16.5f)
                lineTo(12f, 21f)
                lineTo(20.5f, 16.5f)
            }
        }
    }

    val Sliders: ImageVector by lazy {
        strokeIcon("Sliders") {
            strokePath {
                moveTo(4f, 7f); lineTo(10f, 7f)
                moveTo(14f, 7f); lineTo(20f, 7f)
                moveTo(4f, 12f); lineTo(14f, 12f)
                moveTo(18f, 12f); lineTo(20f, 12f)
                moveTo(4f, 17f); lineTo(7f, 17f)
                moveTo(11f, 17f); lineTo(20f, 17f)
            }
            strokePath {
                moveTo(12f, 5f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f)
                moveTo(16f, 10f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f)
                moveTo(9f, 15f); arcToRelative(2f, 2f, 0f, true, true, 0f, 4f); arcToRelative(2f, 2f, 0f, true, true, 0f, -4f)
            }
        }
    }

    val ClockHistory: ImageVector by lazy {
        strokeIcon("ClockHistory") {
            strokePath {
                moveTo(4f, 12f)
                arcToRelative(8f, 8f, 0f, true, true, 2.34f, 5.66f)
            }
            strokePath {
                moveTo(4f, 7.5f)
                lineTo(4f, 12f)
                lineTo(8.5f, 12f)
            }
            strokePath {
                moveTo(12f, 8f)
                lineTo(12f, 12.2f)
                lineTo(15f, 14f)
            }
        }
    }

    val Bolt: ImageVector by lazy {
        strokeIcon("Bolt") {
            strokePath {
                moveTo(13.2f, 2.8f)
                lineTo(5.2f, 13.2f)
                lineTo(11.6f, 13.2f)
                lineTo(10.8f, 21.2f)
                lineTo(18.8f, 10.8f)
                lineTo(12.4f, 10.8f)
                close()
            }
        }
    }

    val Feather: ImageVector by lazy {
        strokeIcon("Feather") {
            strokePath {
                moveTo(20f, 4f)
                curveTo(14.5f, 4f, 9f, 7.5f, 7f, 13.5f)
                lineTo(4f, 20f)
                lineTo(10.5f, 17f)
                curveTo(16.5f, 15f, 20f, 9.5f, 20f, 4f)
                close()
            }
            strokePath {
                moveTo(4f, 20f)
                lineTo(15f, 9f)
            }
        }
    }

    val ShieldCheck: ImageVector by lazy {
        strokeIcon("ShieldCheck") {
            strokePath {
                moveTo(12f, 3f)
                lineTo(19f, 6f)
                lineTo(19f, 11.5f)
                curveTo(19f, 16.2f, 15.9f, 19.6f, 12f, 21f)
                curveTo(8.1f, 19.6f, 5f, 16.2f, 5f, 11.5f)
                lineTo(5f, 6f)
                close()
            }
            strokePath {
                moveTo(9f, 12f)
                lineTo(11.2f, 14.2f)
                lineTo(15.5f, 9.8f)
            }
        }
    }

    val CircleDashed: ImageVector by lazy {
        strokeIcon("CircleDashed") {
            strokePath {
                moveTo(12f, 4f)
                arcToRelative(8f, 8f, 0f, false, true, 5.66f, 2.34f)
            }
            strokePath {
                moveTo(20f, 12f)
                arcToRelative(8f, 8f, 0f, false, true, -2.34f, 5.66f)
            }
            strokePath {
                moveTo(12f, 20f)
                arcToRelative(8f, 8f, 0f, false, true, -5.66f, -2.34f)
            }
            strokePath {
                moveTo(4f, 12f)
                arcToRelative(8f, 8f, 0f, false, true, 2.34f, -5.66f)
            }
        }
    }

    val Snowflake: ImageVector by lazy {
        strokeIcon("Snowflake") {
            strokePath {
                moveTo(12f, 3f); lineTo(12f, 21f)
                moveTo(4.2f, 7.5f); lineTo(19.8f, 16.5f)
                moveTo(19.8f, 7.5f); lineTo(4.2f, 16.5f)
                moveTo(9.5f, 5f); lineTo(12f, 7.2f); lineTo(14.5f, 5f)
                moveTo(9.5f, 19f); lineTo(12f, 16.8f); lineTo(14.5f, 19f)
            }
        }
    }

    val AudioWave: ImageVector by lazy {
        strokeIcon("AudioWave") {
            strokePath {
                moveTo(4f, 10f); lineTo(4f, 14f)
                moveTo(8f, 7f); lineTo(8f, 17f)
                moveTo(12f, 4.5f); lineTo(12f, 19.5f)
                moveTo(16f, 8f); lineTo(16f, 16f)
                moveTo(20f, 10.5f); lineTo(20f, 13.5f)
            }
        }
    }

    val Moon: ImageVector by lazy {
        strokeIcon("Moon") {
            strokePath {
                moveTo(19.5f, 14.5f)
                arcToRelative(7.5f, 7.5f, 0f, true, true, -10f, -10f)
                arcToRelative(6f, 6f, 0f, false, false, 10f, 10f)
                close()
            }
        }
    }

    val SunMoon: ImageVector by lazy {
        strokeIcon("SunMoon") {
            strokePath {
                moveTo(12f, 4f)
                arcToRelative(8f, 8f, 0f, true, false, 0f, 16f)
                arcToRelative(8f, 8f, 0f, true, false, 0f, -16f)
            }
            strokePath {
                moveTo(12f, 4f)
                lineTo(12f, 20f)
                moveTo(12f, 8f)
                lineTo(17.5f, 8f)
                moveTo(12f, 12f)
                lineTo(19.5f, 12f)
                moveTo(12f, 16f)
                lineTo(17.5f, 16f)
            }
        }
    }

    val Gauge: ImageVector by lazy {
        strokeIcon("Gauge") {
            strokePath {
                moveTo(4.5f, 17.5f)
                arcToRelative(8.5f, 8.5f, 0f, true, true, 15f, 0f)
            }
            strokePath {
                moveTo(12f, 14f)
                lineTo(16.2f, 9.8f)
            }
            strokePath {
                moveTo(12f, 13f)
                arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, 2.8f)
                arcToRelative(1.4f, 1.4f, 0f, true, true, 0f, -2.8f)
            }
        }
    }

    val WifiSignal: ImageVector by lazy {
        strokeIcon("WifiSignal") {
            strokePath {
                moveTo(3.5f, 9.5f)
                arcToRelative(12.5f, 12.5f, 0f, false, true, 17f, 0f)
            }
            strokePath {
                moveTo(6.8f, 13f)
                arcToRelative(7.8f, 7.8f, 0f, false, true, 10.4f, 0f)
            }
            strokePath {
                moveTo(10.1f, 16.5f)
                arcToRelative(3.2f, 3.2f, 0f, false, true, 3.8f, 0f)
            }
            strokePath(strokeWidth = 2.4f) {
                moveTo(12f, 19.3f)
                lineTo(12.01f, 19.3f)
            }
        }
    }

    val Terminal: ImageVector by lazy {
        strokeIcon("Terminal") {
            strokePath {
                moveTo(4f, 5.5f)
                lineTo(20f, 5.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
                lineTo(21.5f, 17f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
                lineTo(4f, 18.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
                lineTo(2.5f, 7f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
                close()
            }
            strokePath {
                moveTo(7f, 10f); lineTo(10f, 12.5f); lineTo(7f, 15f)
                moveTo(12.5f, 15f); lineTo(17f, 15f)
            }
        }
    }

    val Lock: ImageVector by lazy {
        strokeIcon("Lock") {
            strokePath {
                moveTo(6.5f, 11f)
                lineTo(17.5f, 11f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
                lineTo(19f, 19f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
                lineTo(6.5f, 20.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
                lineTo(5f, 12.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
                close()
            }
            strokePath {
                moveTo(8.5f, 11f)
                lineTo(8.5f, 8f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
                lineTo(15.5f, 11f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            strokePath {
                moveTo(19.5f, 12f)
                arcToRelative(7.5f, 7.5f, 0f, true, true, -2.2f, -5.3f)
                lineTo(19.5f, 8.8f)
            }
            strokePath {
                moveTo(19.5f, 4.5f)
                lineTo(19.5f, 8.8f)
                lineTo(15.2f, 8.8f)
            }
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") {
            strokePath {
                moveTo(11f, 4.5f)
                arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, 13f)
                arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, -13f)
            }
            strokePath {
                moveTo(16f, 16f)
                lineTo(20f, 20f)
            }
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            strokePath {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            strokePath {
                moveTo(9.5f, 6.5f)
                lineTo(15f, 12f)
                lineTo(9.5f, 17.5f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown") {
            strokePath {
                moveTo(6.5f, 9.5f)
                lineTo(12f, 15f)
                lineTo(17.5f, 9.5f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        strokeIcon("Copy") {
            strokePath {
                moveTo(9f, 9f)
                lineTo(19f, 9f)
                lineTo(19f, 19f)
                lineTo(9f, 19f)
                close()
            }
            strokePath {
                moveTo(15f, 9f)
                lineTo(15f, 5f)
                lineTo(5f, 5f)
                lineTo(5f, 15f)
                lineTo(9f, 15f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            strokePath {
                moveTo(4.5f, 7f); lineTo(19.5f, 7f)
                moveTo(9f, 7f); lineTo(9.8f, 4.5f); lineTo(14.2f, 4.5f); lineTo(15f, 7f)
                moveTo(6.5f, 7f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 7f)
                moveTo(10.2f, 11f); lineTo(10.2f, 16f)
                moveTo(13.8f, 11f); lineTo(13.8f, 16f)
            }
        }
    }

    val Restore: ImageVector by lazy {
        strokeIcon("Restore") {
            strokePath {
                moveTo(4.5f, 12f)
                arcToRelative(7.5f, 7.5f, 0f, true, false, 2.2f, -5.3f)
                lineTo(4.5f, 8.8f)
            }
            strokePath {
                moveTo(4.5f, 4.5f)
                lineTo(4.5f, 8.8f)
                lineTo(8.8f, 8.8f)
            }
        }
    }

    val Power: ImageVector by lazy {
        strokeIcon("Power") {
            strokePath {
                moveTo(12f, 3.8f); lineTo(12f, 11.2f)
            }
            strokePath {
                moveTo(7.3f, 6.8f)
                arcToRelative(7.5f, 7.5f, 0f, true, false, 9.4f, 0f)
            }
        }
    }

    val ExternalLink: ImageVector by lazy {
        strokeIcon("ExternalLink") {
            strokePath {
                moveTo(14.5f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 9.5f)
                moveTo(19.5f, 4.5f); lineTo(11f, 13f)
                moveTo(18f, 13.5f); lineTo(18f, 18.5f); lineTo(5.5f, 18.5f); lineTo(5.5f, 6f); lineTo(10.5f, 6f)
            }
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") {
            strokePath(strokeWidth = 2.2f) {
                moveTo(5.5f, 12.5f)
                lineTo(10f, 17f)
                lineTo(18.8f, 7.5f)
            }
        }
    }

    val Alert: ImageVector by lazy {
        strokeIcon("Alert") {
            strokePath {
                moveTo(12f, 3.8f)
                lineTo(20.5f, 19f)
                lineTo(3.5f, 19f)
                close()
            }
            strokePath {
                moveTo(12f, 9.5f); lineTo(12f, 13.5f)
            }
            strokePath(strokeWidth = 2.3f) {
                moveTo(12f, 16.4f); lineTo(12.01f, 16.4f)
            }
        }
    }

    val Bell: ImageVector by lazy {
        strokeIcon("Bell") {
            strokePath {
                moveTo(6.5f, 15.5f)
                lineTo(17.5f, 15.5f)
                lineTo(16.5f, 10.5f)
                arcToRelative(4.5f, 4.5f, 0f, false, false, -9f, 0f)
                close()
            }
            strokePath {
                moveTo(9.8f, 18.2f)
                arcToRelative(2.3f, 2.3f, 0f, false, false, 4.4f, 0f)
            }
        }
    }

    val DualSpace: ImageVector by lazy {
        strokeIcon("DualSpace") {
            strokePath {
                moveTo(4.5f, 8.5f)
                lineTo(14.5f, 8.5f)
                lineTo(14.5f, 18.5f)
                lineTo(4.5f, 18.5f)
                close()
            }
            strokePath {
                moveTo(9.5f, 8.5f)
                lineTo(9.5f, 5.5f)
                lineTo(19.5f, 5.5f)
                lineTo(19.5f, 15.5f)
                lineTo(14.5f, 15.5f)
            }
        }
    }

    val Sort: ImageVector by lazy {
        strokeIcon("Sort") {
            strokePath {
                moveTo(4.5f, 7f); lineTo(19.5f, 7f)
                moveTo(7f, 12f); lineTo(17f, 12f)
                moveTo(10f, 17f); lineTo(14f, 17f)
            }
        }
    }
}
