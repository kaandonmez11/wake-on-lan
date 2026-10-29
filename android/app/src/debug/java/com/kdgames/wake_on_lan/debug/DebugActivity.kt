package com.kdgames.wake_on_lan.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kdgames.wake_on_lan.data.crypto.KeyVault
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.domain.DomainEvents
import com.kdgames.wake_on_lan.domain.agent.AgentLinker
import com.kdgames.wake_on_lan.domain.power.PowerCoordinator
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.wake.WakeCoordinator
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.WolSender
import com.kdgames.wake_on_lan.net.agent.AgentClient
import com.kdgames.wake_on_lan.net.probe.IcmpProbe
import com.kdgames.wake_on_lan.net.probe.NetBiosProbe
import com.kdgames.wake_on_lan.net.probe.TcpProbe
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * K19: yalnız debug build'de bulunan "WoL Debug" aracı.
 * Her sprint [DebugTab] listesine kendi sekmesini ekler.
 */
@AndroidEntryPoint
class DebugActivity : ComponentActivity() {
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var keyVault: KeyVault
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject lateinit var wolSender: WolSender
    @Inject lateinit var tcpProbe: TcpProbe
    @Inject lateinit var icmpProbe: IcmpProbe
    @Inject lateinit var agentClient: AgentClient
    @Inject lateinit var netBiosProbe: NetBiosProbe
    @Inject lateinit var statusEngine: StatusEngine
    @Inject lateinit var wakeCoordinator: WakeCoordinator
    @Inject lateinit var powerCoordinator: PowerCoordinator
    @Inject lateinit var agentLinker: AgentLinker
    @Inject lateinit var domainEvents: DomainEvents

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = DebugDeps(deviceRepository, settingsStore, keyVault, networkMonitor, wolSender, tcpProbe, icmpProbe, agentClient, netBiosProbe,
            statusEngine, wakeCoordinator, powerCoordinator, agentLinker, domainEvents)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DebugRoot(deps)
            }
        }
    }
}

/** Debug sekmelerinin ihtiyaç duyduğu uygulama bileşenleri. Sprintler buraya ekler. */
class DebugDeps(
    val devices: DeviceRepository,
    val settings: SettingsStore,
    val vault: KeyVault,
    val network: NetworkMonitor,
    val wol: WolSender,
    val tcp: TcpProbe,
    val icmp: IcmpProbe,
    val agent: AgentClient,
    val netBios: NetBiosProbe,
    val engine: StatusEngine,
    val wake: WakeCoordinator,
    val power: PowerCoordinator,
    val linker: AgentLinker,
    val events: DomainEvents,
)

private enum class DebugTab(val title: String) {
    Vectors("Vektörler"),
    Design("Tasarım"),
    Data("Veri"),
    Network("Ağ"),
    Agent("Agent"),
    Status("Durum"),
}

@Composable
private fun DebugRoot(deps: DebugDeps) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = selected) {
                DebugTab.entries.forEachIndexed { index, tab ->
                    Tab(selected = index == selected, onClick = { selected = index }, text = { Text(tab.title) })
                }
            }
            when (DebugTab.entries[selected]) {
                DebugTab.Vectors -> VectorsTab()
                DebugTab.Design -> DesignTab()
                DebugTab.Data -> DataTab(deps.devices, deps.settings, deps.vault)
                DebugTab.Network -> NetworkTab(deps.network, deps.wol, deps.tcp, deps.icmp)
                DebugTab.Agent -> AgentTab(deps.agent, deps.netBios, deps.network, deps.settings, deps.vault)
                DebugTab.Status -> StatusTab(deps.devices, deps.engine, deps.wake, deps.power, deps.linker, deps.events)
            }
        }
    }
}
