package com.kdgames.wake_on_lan.net

import com.kdgames.wake_on_lan.net.probe.PingRunner
import com.kdgames.wake_on_lan.net.probe.SystemPingRunner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Bloklayan soket çağrıları için dispatcher; testlerde sanal zamanlı dispatcher verilir. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Uygulama ömrü boyunca yaşayan scope (NetworkMonitor gibi tekil akışlar için). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/** Milisaniye cinsinden monoton saat; testlerde sanal zaman. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val Monotonic = Clock { System.nanoTime() / 1_000_000 }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetModule {
    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun clock(): Clock = Clock.Monotonic
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetBindings {
    @Binds
    abstract fun socketFactory(impl: WifiSocketFactory): SocketFactory

    @Binds
    abstract fun pingRunner(impl: SystemPingRunner): PingRunner
}
