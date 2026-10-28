package com.kdgames.wake_on_lan.domain.model

/** Zamanlayıcı ve güç aksiyonları (spec 6). WAKE magic packet, diğerleri agent komutu. */
enum class PowerAction {
    WAKE, LOCK, SLEEP, HIBERNATE, SHUTDOWN, RESTART;

    val requiresAgent: Boolean get() = this != WAKE
}

/** Agent'a gönderilen SHUTDOWN/RESTART `force` bayrağı (spec 9.3, 13.4). */
enum class ShutdownMode { FORCE, GRACEFUL }

enum class AttemptSource { MANUAL, SCHEDULE, TILE }

enum class ScheduleType { TIME, WIFI_CONNECT }

enum class RunResult { RUNNING, SUCCESS, FAILED, SKIPPED_ALREADY, SKIPPED_BY_USER }

/** Açık cihaza tile dokunuşu davranışı (spec 10.8). */
enum class TileMode { A, B, C }

/** [com.kdgames.wake_on_lan.data.db.DeviceStateCache] için son kesin karar türü (K7). */
enum class CachedStateKind { ON, OFF }
