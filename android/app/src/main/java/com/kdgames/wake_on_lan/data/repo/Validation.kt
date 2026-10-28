package com.kdgames.wake_on_lan.data.repo

import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.MacAddress
import java.util.Locale

/** Spec 15 doğrulama kuralları (saf). */
object Validation {
    const val NAME_MAX = 32
    private val TURKISH = Locale.forLanguageTag("tr")

    fun normalizeName(name: String): String = name.trim()

    /** Ad benzersizliği için karşılaştırma anahtarı (Türkçe büyük/küçük harf). */
    fun nameKey(name: String): String = normalizeName(name).lowercase(TURKISH)

    fun isValidName(name: String): Boolean = normalizeName(name).length in 1..NAME_MAX

    fun isValidIp(ip: String): Boolean = runCatching { BroadcastResolver.parse(ip.trim()) }.isSuccess

    /** IP telefonun alt ağında mı? Bilinmiyorsa (telefon ağı yok) true kabul edilir — yalnız uyarı. */
    fun isInSubnet(ip: String, phoneIp: String?, prefixLength: Int?): Boolean {
        if (phoneIp == null || prefixLength == null || prefixLength !in 0..32) return true
        val a = runCatching { BroadcastResolver.parse(ip.trim()) }.getOrNull() ?: return true
        val b = runCatching { BroadcastResolver.parse(phoneIp) }.getOrNull() ?: return true
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (a and mask) == (b and mask)
    }

    /** Boş MAC → null (geçerli); dolu ve geçersiz → [MacResult.Invalid]. */
    fun parseMac(input: String?): MacResult {
        if (input.isNullOrBlank()) return MacResult.Empty
        return MacAddress.normalize(input)?.let(MacResult::Valid) ?: MacResult.Invalid
    }

    sealed interface MacResult {
        data object Empty : MacResult
        data class Valid(val mac: String) : MacResult
        data object Invalid : MacResult
    }

    fun validateSchedule(type: ScheduleType, daysMask: Int, minuteOfDay: Int?, ssid: String?): List<ScheduleError> =
        buildList {
            when (type) {
                ScheduleType.TIME -> {
                    if (daysMask and 0x7F == 0) add(ScheduleError.NO_DAYS)
                    if (minuteOfDay == null || minuteOfDay !in 0..1439) add(ScheduleError.INVALID_TIME)
                }
                ScheduleType.WIFI_CONNECT -> if (ssid.isNullOrBlank()) add(ScheduleError.NO_SSID)
            }
        }
}

enum class DeviceError(val message: String) {
    NAME_INVALID("Ad 1–32 karakter olmalı"),
    NAME_DUPLICATE("Bu ad zaten kayıtlı"),
    IP_INVALID("Geçerli bir IPv4 gir"),
    MAC_INVALID("MAC biçimi geçersiz"),
    MAC_DUPLICATE("Bu MAC zaten kayıtlı"),
}

enum class DeviceWarning(val message: String) {
    IP_OUTSIDE_SUBNET("IP telefonun alt ağında değil"),
}

enum class ScheduleError(val message: String) {
    NO_DAYS("En az bir gün seç"),
    INVALID_TIME("Saat geçersiz"),
    NO_SSID("Wi-Fi adı gerekli"),
    DEVICE_MISSING("Cihaz bulunamadı"),
}

/** Retry ayarları (spec 6, 15): aralık 1–30, vazgeçme 2–120 ve ≥ aralık. */
object RetryPolicy {
    val INTERVAL_RANGE = 1..30
    val GIVE_UP_RANGE = 2..120
    const val DEFAULT_INTERVAL = 2
    const val DEFAULT_GIVE_UP = 10

    fun validate(intervalMin: Int, giveUpMin: Int): RetryError? = when {
        intervalMin !in INTERVAL_RANGE -> RetryError.INTERVAL_OUT_OF_RANGE
        giveUpMin !in GIVE_UP_RANGE -> RetryError.GIVE_UP_OUT_OF_RANGE
        giveUpMin < intervalMin -> RetryError.GIVE_UP_LESS_THAN_INTERVAL
        else -> null
    }
}

enum class RetryError(val message: String) {
    INTERVAL_OUT_OF_RANGE("Tekrar aralığı 1–30 dk olmalı"),
    GIVE_UP_OUT_OF_RANGE("Vazgeçme süresi 2–120 dk olmalı"),
    GIVE_UP_LESS_THAN_INTERVAL("Vazgeçme süresi tekrar aralığından kısa olamaz"),
}
