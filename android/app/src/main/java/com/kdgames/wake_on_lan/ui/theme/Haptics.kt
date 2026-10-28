package com.kdgames.wake_on_lan.ui.theme

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Spec 12.7: VibrationEffect.Composition primitifleri. [enabled] Ayarlar'daki titreşim anahtarına bağlanır (S10).
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    @Volatile
    var enabled: Boolean = true

    /** Anahtar, sekme, seçim (hafif). */
    fun tick() = play(Composition.PRIMITIVE_TICK, 0.5f)

    /** Hold dolumu dilimleri; [intensity] 0.2 → 0.8 artar. */
    fun lowTick(intensity: Float) = play(Composition.PRIMITIVE_LOW_TICK, intensity.coerceIn(0f, 1f))

    /** Hold tamamlandı. */
    fun thud() = play(Composition.PRIMITIVE_THUD, 1f)

    /** Uyandırma gönderimi. */
    fun click() = play(Composition.PRIMITIVE_CLICK, 0.8f)

    /** Cihaz açıldı (bloom ile senkron). SPIN yoksa çift CLICK. */
    fun success() {
        if (supports(Composition.PRIMITIVE_SPIN)) play(Composition.PRIMITIVE_SPIN, 0.8f)
        else compose { addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f).addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f, 80) }
    }

    /** Hold iptali. */
    fun cancel() = play(Composition.PRIMITIVE_TICK, 0.3f)

    /** Hata / "Uyanmadı": iki kısa tick. */
    fun error() = compose {
        addPrimitive(Composition.PRIMITIVE_TICK, 0.8f).addPrimitive(Composition.PRIMITIVE_TICK, 0.8f, 90)
    }

    private fun play(primitive: Int, scale: Float) = compose { addPrimitive(primitive, scale) }

    private inline fun compose(build: Composition.() -> Composition) {
        if (!enabled || !vibrator.hasVibrator()) return
        val effect = VibrationEffect.startComposition().build().compose()
        // Haptik hiçbir zaman uygulamayı düşürmemeli (ör. izin/servis hatası).
        runCatching { vibrator.vibrate(effect) }.onFailure { Log.w(TAG, "Titreşim çalınamadı", it) }
    }

    private fun supports(primitive: Int) = vibrator.areAllPrimitivesSupported(primitive)
}

private const val TAG = "Haptics"

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics sağlanmadı") }
