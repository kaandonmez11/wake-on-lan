package com.kdgames.wake_on_lan.domain

import com.kdgames.wake_on_lan.domain.agent.AgentLinker
import com.kdgames.wake_on_lan.domain.power.AgentCommander
import com.kdgames.wake_on_lan.domain.power.ClientAgentCommander
import com.kdgames.wake_on_lan.domain.power.UnknownPhoneHandler
import com.kdgames.wake_on_lan.domain.status.AppForeground
import com.kdgames.wake_on_lan.domain.status.NetworkSignalProber
import com.kdgames.wake_on_lan.domain.status.ProcessForeground
import com.kdgames.wake_on_lan.domain.status.RepositoryStatusStore
import com.kdgames.wake_on_lan.domain.status.RoundObserver
import com.kdgames.wake_on_lan.domain.status.SignalProber
import com.kdgames.wake_on_lan.domain.status.StatusStore
import com.kdgames.wake_on_lan.domain.wake.AttemptRecorder
import com.kdgames.wake_on_lan.domain.wake.MagicSender
import com.kdgames.wake_on_lan.domain.wake.RepositoryAttemptRecorder
import com.kdgames.wake_on_lan.domain.wake.WifiMagicSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainModule {
    @Binds abstract fun statusStore(impl: RepositoryStatusStore): StatusStore
    @Binds abstract fun roundObserver(impl: AgentLinker): RoundObserver
    @Binds abstract fun unknownPhone(impl: AgentLinker): UnknownPhoneHandler
    @Binds abstract fun linkTrigger(impl: AgentLinker): com.kdgames.wake_on_lan.domain.agent.AgentLinkTrigger
    @Binds abstract fun foreground(impl: ProcessForeground): AppForeground
    @Binds abstract fun recorder(impl: RepositoryAttemptRecorder): AttemptRecorder
    @Binds abstract fun alarms(impl: com.kdgames.wake_on_lan.domain.schedule.AndroidAlarmScheduler): com.kdgames.wake_on_lan.domain.schedule.AlarmScheduler
    @Binds abstract fun runStore(impl: com.kdgames.wake_on_lan.domain.schedule.RepositoryRunStore): com.kdgames.wake_on_lan.domain.schedule.ScheduleRunStore
    @Binds abstract fun notifier(impl: com.kdgames.wake_on_lan.domain.schedule.AndroidScheduleNotifier): com.kdgames.wake_on_lan.domain.schedule.ScheduleNotifier
    @Binds abstract fun retrySettings(impl: com.kdgames.wake_on_lan.domain.schedule.SettingsRetry): com.kdgames.wake_on_lan.domain.schedule.RetrySettingsSource
    @Binds abstract fun retryAlarms(impl: com.kdgames.wake_on_lan.domain.schedule.AndroidRetryAlarms): com.kdgames.wake_on_lan.domain.schedule.RetryAlarms
    @Binds abstract fun wifiTrigger(impl: com.kdgames.wake_on_lan.domain.schedule.WifiTriggerRegistrar): com.kdgames.wake_on_lan.domain.schedule.WifiTriggerSync

    /** Debug build "Sahte PC" bağlar (src/debug); release'te yok. */
    @dagger.BindsOptionalOf abstract fun fakePc(): FakePc

    companion object {
        @dagger.Provides
        fun wallClock(): com.kdgames.wake_on_lan.domain.schedule.WallClock = com.kdgames.wake_on_lan.domain.schedule.WallClock.System

        @dagger.Provides
        fun prober(real: NetworkSignalProber, fake: java.util.Optional<FakePc>): SignalProber = FakePcRouting.prober(real, fake)

        @dagger.Provides
        fun magicSender(real: WifiMagicSender, fake: java.util.Optional<FakePc>): MagicSender = FakePcRouting.magic(real, fake)

        @dagger.Provides
        fun commander(real: ClientAgentCommander, fake: java.util.Optional<FakePc>): AgentCommander = FakePcRouting.commander(real, fake)
    }
}
