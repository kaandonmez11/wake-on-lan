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
    @Binds abstract fun prober(impl: NetworkSignalProber): SignalProber
    @Binds abstract fun roundObserver(impl: AgentLinker): RoundObserver
    @Binds abstract fun unknownPhone(impl: AgentLinker): UnknownPhoneHandler
    @Binds abstract fun linkTrigger(impl: AgentLinker): com.kdgames.wake_on_lan.domain.agent.AgentLinkTrigger
    @Binds abstract fun foreground(impl: ProcessForeground): AppForeground
    @Binds abstract fun magicSender(impl: WifiMagicSender): MagicSender
    @Binds abstract fun recorder(impl: RepositoryAttemptRecorder): AttemptRecorder
    @Binds abstract fun commander(impl: ClientAgentCommander): AgentCommander
}
