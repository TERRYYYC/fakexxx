package name.caiyao.fakegps.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * T11b 视觉对齐：QWY 侧与 Auto 侧 RunDashboardScreen（T7v2）同一套 shadcn/ui 中性极简体系
 * 翻译成 M3 自定义 ColorScheme（UI-HIFI-BRIEF-V2 tokens / ui-hifi/v3 M1 三态稿）。抛弃 M3
 * 默认紫与 Monet 动态取色：主色是近黑（light）/近白（dark），tonal 容器一律压成中性灰，
 * 卡片 = 1px 边框 + 12dp 圆角 + 无阴影。
 *
 * 语义色（三灯/徽标/状态点）不在 M3 ColorScheme 里，走 [LocalShadcnSemantic]：
 * 绿=就绪/完成、蓝=进行中、灰=未知/待完成、amber=注意、红=失败。
 */

// ---- shadcn light tokens ----------------------------------------------------

private val LightColors = lightColorScheme(
    primary = Color(0xFF18181B),          // --primary
    onPrimary = Color(0xFFFAFAFA),        // --primary-fg
    primaryContainer = Color(0xFFF4F4F5), // 中性化：无紫 tonal
    onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF71717A),        // --muted-fg
    onSecondary = Color(0xFFFAFAFA),
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF18181B),
    background = Color(0xFFFFFFFF),       // --bg
    onBackground = Color(0xFF09090B),     // --fg
    surface = Color(0xFFFFFFFF),          // --card
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFF4F4F5),   // --muted
    onSurfaceVariant = Color(0xFF71717A), // --muted-fg
    outline = Color(0xFFE4E4E7),          // --border
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFDC2626),            // --red
    onError = Color(0xFFFAFAFA),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color(0xFFFAFAFA),
    surfaceTint = Color(0xFF18181B),      // 防 M3 默认紫 tint
)

// ---- shadcn dark tokens -----------------------------------------------------

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFAFAFA),
    onPrimary = Color(0xFF18181B),
    primaryContainer = Color(0xFF27272A),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF18181B),
    onSecondaryContainer = Color(0xFFA1A1AA),
    background = Color(0xFF09090B),
    onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF0C0C0E),
    onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF18181B),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF27272A),
    outlineVariant = Color(0xFF27272A),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFAFAFA),
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = Color(0xFFFAFAFA),
    inverseOnSurface = Color(0xFF18181B),
    surfaceTint = Color(0xFFFAFAFA),
)

/** 三灯/徽标/状态点的语义色（绿/蓝/灰/amber/红），亮暗各一组。 */
data class ShadcnSemantic(
    val green: Color,
    val blue: Color,
    val grayDot: Color,
    val amber: Color,
    val red: Color,
)

private val LightSemantic = ShadcnSemantic(
    green = Color(0xFF16A34A),
    blue = Color(0xFF2563EB),
    grayDot = Color(0xFFD4D4D8),
    amber = Color(0xFFD97706),
    red = Color(0xFFDC2626),
)

private val DarkSemantic = ShadcnSemantic(
    green = Color(0xFF22C55E),
    blue = Color(0xFF3B82F6),
    grayDot = Color(0xFF52525B),
    amber = Color(0xFFF59E0B),
    red = Color(0xFFEF4444),
)

val LocalShadcnSemantic = staticCompositionLocalOf { LightSemantic }

/** shadcn Card：12dp 圆角、1px 边框、无阴影/tonal。 */
val ShadcnCardShape = RoundedCornerShape(12.dp)

@Composable
fun ShadcnCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = ShadcnCardShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        content()
    }
}

@Composable
fun FakeGpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalShadcnSemantic provides if (darkTheme) DarkSemantic else LightSemantic,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
