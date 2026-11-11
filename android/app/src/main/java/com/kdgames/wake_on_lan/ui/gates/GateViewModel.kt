package com.kdgames.wake_on_lan.ui.gates

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.net.WifiStateSource
import com.kdgames.wake_on_lan.ui.settings.PermissionSnapshot
import com.kdgames.wake_on_lan.ui.settings.PermissionSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tam ekranı kaplayan durum (spec 10.6, 14). Öncelik: onboarding > izin > Wi-Fi. */
enum class Gate { NONE, ONBOARDING, NO_PERMISSION, NO_WIFI }

/** Wi-Fi yokken bilgi kartı: mobil veri ve operatör. */
data class MobileInfo(val onCellular: Boolean = false, val operator: String? = null) {
    val label: String
        get() = when {
            !onCellular -> "Bağlantı yok"
            operator.isNullOrBlank() -> "Şu an mobil veri"
            else -> "Şu an mobil veri · $operator"
        }
}

/** Saf karar (testli). [onboardingDone] null = henüz okunmadı (hiçbir şey gösterilmez). */
object Gates {
    fun decide(onboardingDone: Boolean?, localNetwork: Boolean, wifiMissing: Boolean): Gate = when {
        onboardingDone == null -> Gate.NONE
        !onboardingDone -> Gate.ONBOARDING
        !localNetwork -> Gate.NO_PERMISSION
        wifiMissing -> Gate.NO_WIFI
        else -> Gate.NONE
    }
}

/** İlk açılış bayrağı (testte bellek içi). */
interface OnboardingFlag {
    val done: Flow<Boolean>
    suspend fun complete()
}

class StoreOnboardingFlag @Inject constructor(private val store: SettingsStore) : OnboardingFlag {
    override val done: Flow<Boolean> = store.settings.map { it.onboardingDone }.distinctUntilChanged()
    override suspend fun complete() = store.setOnboardingDone()
}

fun interface MobileInfoSource {
    fun info(): Flow<MobileInfo>
}

/** Varsayılan ağ hücresel mi + operatör adı (izin gerektirmez). */
class AndroidMobileInfoSource @Inject constructor(@ApplicationContext private val context: Context) : MobileInfoSource {
    override fun info(): Flow<MobileInfo> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val tm = context.getSystemService(TelephonyManager::class.java)
        fun publish(caps: NetworkCapabilities?) {
            val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            trySend(MobileInfo(cellular, if (cellular) runCatching { tm?.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() } else null))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish(caps)
            override fun onLost(network: Network) = publish(null)
        }
        publish(runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull())
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GateModule {
    @Binds abstract fun flag(impl: StoreOnboardingFlag): OnboardingFlag
    @Binds abstract fun mobile(impl: AndroidMobileInfoSource): MobileInfoSource
}

data class GateUi(val gate: Gate = Gate.NONE, val mobile: MobileInfo = MobileInfo(), val permissions: PermissionSnapshot = PermissionSnapshot())

@HiltViewModel
class GateViewModel internal constructor(
    wifi: WifiStateSource,
    private val permissions: PermissionSource,
    private val flag: OnboardingFlag,
    mobile: MobileInfoSource,
    /** Wi-Fi kopukluğunun kapıyı açması için gereken süre (testte 0). */
    graceMs: Long,
) : ViewModel() {
    @Inject constructor(wifi: WifiStateSource, permissions: PermissionSource, flag: OnboardingFlag, mobile: MobileInfoSource) :
        this(wifi, permissions, flag, mobile, WIFI_GRACE_MS)

    private val snapshot = MutableStateFlow(permissions.snapshot())

    /**
     * Wi-Fi yok kapısı kopukluk [WIFI_GRACE_MS] sürerse açılır: açılışta callback gelene dek ve mesh node
     * geçişlerinde yanıp sönmez. Bağlantı gelince hemen kapanır.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val wifiMissing: Flow<Boolean> = wifi.state.map { it.connected }.distinctUntilChanged().transformLatest { connected ->
        if (connected) emit(false) else {
            delay(graceMs)
            emit(true)
        }
    }

    private val onboarding: Flow<Boolean?> = flow {
        emit(null)
        flag.done.collect { emit(it) }
    }

    val state: StateFlow<GateUi> = combine(onboarding, snapshot, wifiMissing, mobile.info()) { done, perms, missing, m ->
        GateUi(Gates.decide(done, perms.localNetwork, missing), m, perms)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GateUi())

    /** ON_RESUME ve izin sonucundan sonra. */
    fun refreshPermissions() {
        snapshot.value = permissions.snapshot()
    }

    fun completeOnboarding() {
        viewModelScope.launch { flag.complete() }
    }

    companion object {
        const val WIFI_GRACE_MS = 700L
    }
}
