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

/**
 * K19: yalnız debug build'de bulunan "WoL Debug" aracı.
 * Her sprint [DebugTab] listesine kendi sekmesini ekler.
 */
class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DebugRoot()
            }
        }
    }
}

private enum class DebugTab(val title: String) {
    Vectors("Vektörler"),
}

@Composable
private fun DebugRoot() {
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
            }
        }
    }
}
