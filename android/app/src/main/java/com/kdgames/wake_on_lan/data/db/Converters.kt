package com.kdgames.wake_on_lan.data.db

import androidx.room.TypeConverter

/** Enum'lar Room tarafından adlarıyla (TEXT) saklanır; burada yalnız liste dönüşümü var. */
class Converters {
    @TypeConverter
    fun portsToString(ports: List<Int>): String = ports.joinToString(",")

    @TypeConverter
    fun stringToPorts(value: String): List<Int> =
        if (value.isBlank()) emptyList() else value.split(',').map { it.trim().toInt() }
}
