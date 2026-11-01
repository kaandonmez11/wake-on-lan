package com.kdgames.wake_on_lan.domain

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.power.AgentCommander
import com.kdgames.wake_on_lan.domain.status.SignalProber
import com.kdgames.wake_on_lan.domain.wake.MagicSender
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentResult
import java.util.Optional

/**
 * Debug "Sahte PC" (S09, Kaan: testler için PC'yi tekrar tekrar açıp kapatmamak). Yalnız debug build bağlar
 * (`src/debug`); release'te boş. Sahte PC'nin MAC'iyle gelen sinyal turu, magic packet ve agent komutu
 * gerçek ağ yerine buna gider; üstteki her şey (StatusEngine, koordinatörler, zamanlayıcılar) aynen çalışır.
 */
interface FakePc {
    fun handles(device: Device): Boolean
    fun handlesMac(mac: String): Boolean
    suspend fun probe(device: Device): RoundSignals
    suspend fun magic(mac: String, onFirstSent: () -> Unit)
    suspend fun command(device: Device, cmd: AgentCommand, force: Boolean): AgentResult<Unit>
}

/** Gerçek bileşeni sahte PC ile sarar (yoksa olduğu gibi döner). */
object FakePcRouting {
    fun prober(real: SignalProber, fake: Optional<FakePc>): SignalProber {
        val f = fake.orElse(null) ?: return real
        return SignalProber { d -> if (f.handles(d)) f.probe(d) else real.probe(d) }
    }

    fun magic(real: MagicSender, fake: Optional<FakePc>): MagicSender {
        val f = fake.orElse(null) ?: return real
        return MagicSender { mac, onFirst -> if (f.handlesMac(mac)) f.magic(mac, onFirst) else real.send(mac, onFirst) }
    }

    fun commander(real: AgentCommander, fake: Optional<FakePc>): AgentCommander {
        val f = fake.orElse(null) ?: return real
        return AgentCommander { d, cmd, force -> if (f.handles(d)) f.command(d, cmd, force) else real.command(d, cmd, force) }
    }
}
