package org.telnyx.webrtc.compose_app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = colorPrimary,
    secondary = colorSecondary,
    tertiary = colorPrimaryVariant,
    background = background_color,
    surface =surface,
    primaryContainer = primaryContainer
)

private val LightColorScheme = lightColorScheme(
    primary = colorPrimary,
    secondary = colorSecondary,
    tertiary = colorPrimaryVariant,
    background = background_color,
    surface =surface,
    primaryContainer = primaryContainer
    /* Other default colors to override

    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun TelnyxAndroidWebRTCSDKTheme(
    darkTheme: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {


        darkTheme -> LightColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
