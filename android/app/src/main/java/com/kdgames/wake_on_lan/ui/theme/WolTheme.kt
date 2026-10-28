package com.kdgames.wake_on_lan.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Uygulama teması: tipografi, hareket, haptik. Material 3 yalnız temel bileşenler için (spec 4). */
@Composable
fun WolTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val haptics = remember(context) { Haptics(context.applicationContext) }
    val reduceMotion = rememberReduceMotion()
    MaterialTheme(colorScheme = darkColorScheme(background = WolColors.Background, surface = WolColors.Background)) {
        CompositionLocalProvider(
            LocalHaptics provides haptics,
            LocalReduceMotion provides reduceMotion,
            LocalContentColor provides WolColors.TextPrimary,
            LocalTextStyle provides WolType.Body,
            content = content,
        )
    }
}
