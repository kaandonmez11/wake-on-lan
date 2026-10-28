package com.kdgames.wake_on_lan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/** protocol/vectors.json (C# testleriyle ortak). Yol Gradle'dan `wol.vectors` olarak gelir. */
object TestVectors {
    val root: JsonObject by lazy {
        val path = requireNotNull(System.getProperty("wol.vectors")) { "wol.vectors sistem özelliği yok" }
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    fun section(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }

    fun obj(name: String): JsonObject = root.getValue(name).jsonObject

    val sessionKey: ByteArray by lazy { obj("pairing").b64("sessionKey") }
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.b64(key: String): ByteArray = Base64.getDecoder().decode(str(key))

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.hexBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
