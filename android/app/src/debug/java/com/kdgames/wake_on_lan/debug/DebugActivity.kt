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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deps = DebugDeps(deviceRepository, settingsStore, keyVault)
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
)

private enum class DebugTab(val title: String) {
    Vectors("Vektörler"),
    Design("Tasarım"),
    Data("Veri"),
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
            }
        }
    }
}
