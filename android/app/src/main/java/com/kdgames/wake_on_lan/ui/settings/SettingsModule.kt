package com.kdgames.wake_on_lan.ui.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds abstract fun permissions(impl: AndroidPermissionSource): PermissionSource
}
