package com.kdgames.wake_on_lan.net.agent

import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.MacAddress

/**
 * K16: WoL için MAC seçimi. Öncelik: kablolu **ve** telefonla aynı alt ağda → ilk kablolu → MAC'i olan ilk NIC.
 * Geçersiz MAC'li kartlar sayılmaz.
 */
object NicSelector {
    fun select(nics: List<AgentNic>, phoneIp: String?, prefixLength: Int?): AgentNic? {
        val valid = nics.filter { MacAddress.isValid(it.mac) }
        return valid.firstOrNull { it.wired && sameSubnet(it.ip, phoneIp, prefixLength) }
            ?: valid.firstOrNull { it.wired }
            ?: valid.firstOrNull()
    }

    fun sameSubnet(a: String?, b: String?, prefixLength: Int?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank() || prefixLength == null || prefixLength !in 0..32) return false
        val x = runCatching { BroadcastResolver.parse(a) }.getOrNull() ?: return false
        val y = runCatching { BroadcastResolver.parse(b) }.getOrNull() ?: return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (x and mask) == (y and mask)
    }
}
