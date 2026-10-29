package com.kdgames.wake_on_lan

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kdgames.wake_on_lan.ui.components.ToastHostState
import com.kdgames.wake_on_lan.ui.components.ToastKind
import com.kdgames.wake_on_lan.ui.devices.DevicesScreen
import com.kdgames.wake_on_lan.ui.devices.DevicesViewModel
import com.kdgames.wake_on_lan.ui.devices.backgroundTint
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.nav.AppNav
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val devicesViewModel: DevicesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: şeffaf sistem çubukları, açık (beyaz) ikonlar (spec 10).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            WolTheme {
                val state by devicesViewModel.state.collectAsStateWithLifecycle()
                val toasts = remember { ToastHostState() }
                AppNav(
                    onAddDevice = { toasts.show("Cihaz ekleme bir sonraki güncellemede · şimdilik WoL Debug › Veri", ToastKind.Info, Sym.add) },
                    devicesTint = backgroundTint(state.selected),
                    devices = { openSettings ->
                        DevicesScreen(
                            state = state,
                            events = devicesViewModel.events,
                            toasts = toasts,
                            onSelect = devicesViewModel::select,
                            onWake = { devicesViewModel.wake(it) },
                            onPower = { id, action -> devicesViewModel.power(id, action) },
                            onAdd = { toasts.show("Cihaz ekleme bir sonraki güncellemede · şimdilik WoL Debug › Veri", ToastKind.Info, Sym.add) },
                            onOpenSettings = openSettings,
                        )
                    },
                )
            }
        }
    }
}
