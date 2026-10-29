package com.kdgames.wake_on_lan.net.scan

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.net.WifiState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow

/** Cihaz panelinin kullandığı tarama girişi (testte sahte). */
fun interface DeviceScanner {
    fun scan(wifi: WifiState, existing: () -> List<Device>): Flow<ScanSnapshot>
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScanBindings {
    @Binds
    abstract fun sources(impl: AndroidScanSources): ScanSources

    @Binds
    abstract fun scanner(impl: NetworkScanner): DeviceScanner
}
