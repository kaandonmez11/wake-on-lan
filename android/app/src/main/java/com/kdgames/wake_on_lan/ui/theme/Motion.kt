package com.kdgames.wake_on_lan.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.pow

/**
 * Apple response/dampingFraction → Compose spring (spec 12.1):
 * stiffness = (2π / response)², kütle 1; dampingRatio = dampingFraction.
 */
enum class SpringToken(val response: Float, val damping: Float) {
    /** Basma geri bildirimi, küçük ikon değişimleri. */
    Quick(0.20f, 1.00f),

    /** Çipler, butonlar, metin değişimleri. */
    Snappy(0.30f, 0.86f),

    /** Kartlar, sheet, ekran geçişleri. */
    Smooth(0.50f, 1.00f),

    /** Anahtar düğmesi, seçim halkası, başarı vurgusu. */
    Bouncy(0.50f, 0.70f),

    /** Arka plan sahne geçişi, renk tonu kaymaları. */
    Gentle(0.80f, 1.00f);

    val stiffness: Float get() = stiffnessFor(response)

    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

    companion object {
        fun stiffnessFor(response: Float): Float = (2 * PI / response).pow(2).toFloat()
    }
}

/**
 * Sistemin "Animasyonları kaldır" ayarı (ANIMATOR_DURATION_SCALE == 0) açık mı (spec 12.1).
 * Açıkken sonsuz döngüler durur, geçişler kısa crossfade'e iner.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Hareket azaltıldığında geçişlerin indiği kısa crossfade süresi. */
const val REDUCED_CROSSFADE_MS = 120

/** Duruma göre spring ya da (hareket azaltılmışsa) kısa tween. */
fun <T> motionSpec(token: SpringToken, reduceMotion: Boolean): FiniteAnimationSpec<T> =
    if (reduceMotion) tween(REDUCED_CROSSFADE_MS) else token.spec()

@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember { mutableStateOf(readReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        resolver.registerContentObserver(Settings.System.getUriFor(SAMSUNG_REMOVE_ANIMATIONS), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

/**
 * One UI'daki Erişilebilirlik › "Animasyonları kaldır" anahtarı ölçeği değiştirmeden bu sistem
 * ayarını yazabiliyor (S01 cihaz testinde görüldü); ikisi de hareket azaltma sayılır.
 */
private const val SAMSUNG_REMOVE_ANIMATIONS = "remove_animations"

private fun readReduceMotion(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
        Settings.System.getInt(resolver, SAMSUNG_REMOVE_ANIMATIONS, 0) == 1
