package com.example.cellrebelauto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * App theme using Material3.
 * # 应用主题：使用 Material3 默认配色
 */

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun CellRebelAutoTheme(
    // # 默认使用亮色主题
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
