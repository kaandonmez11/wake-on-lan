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

    /** Anahtar, sekme, seçim (hafif ama hissedilir). */
    fun tick() = play(Composition.PRIMITIVE_TICK, TICK_SCALE)

    /**
     * Hold dolumu dilimleri; [intensity] spec'teki 0.2 → 0.8 aralığında artar.
     * LOW_TICK düşük ölçeklerde S24'te hissedilmediği için 0.55 → 1.0 aralığına eşlenir.
     */
    fun lowTick(intensity: Float) {
        val t = ((intensity.coerceIn(0.2f, 0.8f) - 0.2f) / 0.6f)
        play(Composition.PRIMITIVE_LOW_TICK, LOW_TICK_MIN + (1f - LOW_TICK_MIN) * t)
    }

    /** Hold tamamlandı. */
    fun thud() = play(Composition.PRIMITIVE_THUD, 1f)

    /** Uyandırma gönderimi. */
    fun click() = play(Composition.PRIMITIVE_CLICK, 0.8f)

    /** Cihaz açıldı (bloom ile senkron). SPIN yoksa çift CLICK. */
    fun success() {
        if (supports(Composition.PRIMITIVE_SPIN)) play(Composition.PRIMITIVE_SPIN, 0.8f)
        else compose { addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f).addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f, 80) }
    }

    /** Hold iptali: tick'ten belirgin biçimde hafif ama hissedilir. */
    fun cancel() = play(Composition.PRIMITIVE_TICK, CANCEL_SCALE)

    /** Hata / "Uyanmadı": iki kısa, tam şiddet tick. */
    fun error() = compose {
        addPrimitive(Composition.PRIMITIVE_TICK, 1f).addPrimitive(Composition.PRIMITIVE_TICK, 1f, ERROR_GAP_MS)
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

// Kaan'ın S01 cihaz testi: 0.5 TICK, 0.2 LOW_TICK ve 0.3 iptal hissedilmiyordu.
private const val TICK_SCALE = 1f
private const val LOW_TICK_MIN = 0.55f
private const val CANCEL_SCALE = 0.7f
private const val ERROR_GAP_MS = 110

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics sağlanmadı") }
