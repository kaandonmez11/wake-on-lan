package com.kdgames.wake_on_lan

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kdgames.wake_on_lan.ui.components.ToastHostState
import com.kdgames.wake_on_lan.ui.components.ToastKind
import com.kdgames.wake_on_lan.ui.devices.DevicesScreen
import com.kdgames.wake_on_lan.ui.devices.DevicesViewModel
import com.kdgames.wake_on_lan.ui.theme.Sym
import com.kdgames.wake_on_lan.ui.nav.AppNav
import com.kdgames.wake_on_lan.ui.sheet.DeviceSheet
import com.kdgames.wake_on_lan.ui.sheet.DeviceSheetViewModel
import com.kdgames.wake_on_lan.ui.sheet.SheetActions
import com.kdgames.wake_on_lan.ui.sheet.SheetMode
import com.kdgames.wake_on_lan.ui.sheet.SheetResult
import com.kdgames.wake_on_lan.ui.sheet.rememberSheetMotion
import com.kdgames.wake_on_lan.ui.sheet.sheetBackdrop
import com.kdgames.wake_on_lan.ui.theme.WolTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val devicesViewModel: DevicesViewModel by viewModels()
    private val sheetViewModel: DeviceSheetViewModel by viewModels()
    private val automationViewModel: com.kdgames.wake_on_lan.ui.automation.AutomationViewModel by viewModels()
    private val editorViewModel: com.kdgames.wake_on_lan.ui.automation.ScheduleEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: şeffaf sistem çubukları, açık (beyaz) ikonlar (spec 10).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            WolTheme {
                // Kök yalnız tonu okur; tam durum yalnız Cihazlar içeriğinde okunur (S06 FPS taraması).
                val tint by devicesViewModel.tint.collectAsStateWithLifecycle()
                val toasts = remember { ToastHostState() }
                val motion = rememberSheetMotion()
                val editorMotion = rememberSheetMotion()
                var editorOpen by remember { mutableStateOf(false) }
                val openEditor = { mode: com.kdgames.wake_on_lan.ui.automation.EditorMode ->
                    editorViewModel.open(mode, preferredDevice = devicesViewModel.state.value.selected?.id)
                    editorOpen = true
                }
                val closeEditor = { editorOpen = false }
                var sheetOpen by remember { mutableStateOf(false) }
                var showDevices by remember { mutableIntStateOf(0) }
                val openSheet = { mode: SheetMode ->
                    sheetViewModel.open(mode)
                    sheetOpen = true
                }
                val closeSheet = {
                    sheetOpen = false
                    sheetViewModel.close()
                }
                val onResult = { r: SheetResult ->
                    closeSheet()
                    when (r) {
                        is SheetResult.Saved -> {
                            // Kayıt: cihaz seçilir, Cihazlar sekmesine geçilir (spec 10.5).
                            devicesViewModel.select(r.id)
                            showDevices++
                        }
                        is SheetResult.Deleted -> toasts.show("Cihaz silindi", ToastKind.Info, Sym.delete)
                    }
                    Unit
                }
                Box(Modifier.fillMaxSize()) {
                    AppNav(
                        onAddDevice = { openSheet(SheetMode.Add) },
                        devicesTint = tint,
                        modifier = Modifier.sheetBackdrop(motion).sheetBackdrop(editorMotion),
                        showDevicesRequest = showDevices,
                        devices = { openSettings ->
                            val state by devicesViewModel.state.collectAsStateWithLifecycle()
                            DevicesScreen(
                                state = state,
                                events = devicesViewModel.events,
                                toasts = toasts,
                                onSelect = devicesViewModel::select,
                                onWake = { devicesViewModel.wake(it) },
                                onPower = { id, action -> devicesViewModel.power(id, action) },
                                onAdd = { openSheet(SheetMode.Add) },
                                onEdit = { openSheet(SheetMode.Edit(it)) },
                                onOpenSettings = openSettings,
                            )
                        },
                        automation = { openSettings ->
                            val state by automationViewModel.state.collectAsStateWithLifecycle()
                            com.kdgames.wake_on_lan.ui.automation.AutomationScreen(
                                state = state,
                                onAdd = { openEditor(com.kdgames.wake_on_lan.ui.automation.EditorMode.Add) },
                                onEdit = { openEditor(com.kdgames.wake_on_lan.ui.automation.EditorMode.Edit(it)) },
                                onToggle = automationViewModel::setEnabled,
                                onSkip = automationViewModel::toggleSkip,
                                onOpenSettings = openSettings,
                            )
                        },
                    )
                    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
                    com.kdgames.wake_on_lan.ui.automation.ScheduleEditorSheet(
                        open = editorOpen,
                        state = editorState,
                        motion = editorMotion,
                        actions = remember {
                            com.kdgames.wake_on_lan.ui.automation.EditorActions(
                                onEdit = editorViewModel::edit,
                                onToggleDay = editorViewModel::toggleDay,
                                onShiftMinutes = editorViewModel::shiftMinutes,
                                onSave = { editorViewModel.save(closeEditor) },
                                onDelete = { editorViewModel.delete(closeEditor) },
                                onClose = closeEditor,
                            )
                        },
                    )
                    val sheetState by sheetViewModel.state.collectAsStateWithLifecycle()
                    DeviceSheet(
                        open = sheetOpen,
                        state = sheetState,
                        motion = motion,
                        actions = remember {
                            SheetActions(
                                onEdit = sheetViewModel::edit,
                                onPick = sheetViewModel::pick,
                                onRescan = sheetViewModel::rescan,
                                onSave = { sheetViewModel.save(onResult) },
                                onDelete = { sheetViewModel.delete(onResult) },
                                onClose = closeSheet,
                            )
                        },
                    )
                }
            }
        }
    }
}
