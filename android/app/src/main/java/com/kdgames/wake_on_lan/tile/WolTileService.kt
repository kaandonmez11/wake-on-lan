package com.kdgames.wake_on_lan.tile

import android.Manifest
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kdgames.wake_on_lan.MainActivity
import com.kdgames.wake_on_lan.R
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.prefs.Settings
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.power.PowerCoordinator
import com.kdgames.wake_on_lan.domain.schedule.ScheduleTexts
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.wake.WakeCoordinator
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.NetPermissions
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.ui.devices.iconSymbol
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TileEntryPoint {
    fun settings(): SettingsStore
    fun devices(): DeviceRepository
    fun engine(): StatusEngine
    fun wake(): WakeCoordinator
    fun power(): PowerCoordinator
    fun monitor(): NetworkMonitor

    @AppScope
    fun scope(): CoroutineScope
}

internal fun Context.tileDeps() = EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)

class WolTileService1 : WolTileService(0)
class WolTileService2 : WolTileService(1)
class WolTileService3 : WolTileService(2)

/**
 * Hızlı Ayarlar tile'ı (spec 10.8). 3 sabit slot; slot → cihaz Ayarlar'dan (DataStore `tileSlots`).
 * Panel açıkken (`onStartListening`) hemen bir tur atılır, 3 sn'de bir yinelenir ve tile durum akışını izler.
 * Kapalı cihaza dokunmak kilit açtırmadan uyandırır; açık cihazda seçili moda göre (önce `unlockAndRun`):
 * A tam ekran [TilePowerActivity], B [showDialog] ile cam dialog, C çift dokunuş. K12: komut yalnız `On` iken.
 * Doğrulama panel kapansa da [TileVerifyWorker]'da sürer; bildirim yalnız başarısızlıkta (K10).
 */
abstract class WolTileService(private val slot: Int) : TileService() {
    private var job: Job? = null
    private val doubleTap = DoubleTap()
    private var dialog: Dialog? = null

    @Volatile private var device: Device? = null
    @Volatile private var mode = com.kdgames.wake_on_lan.domain.model.TileMode.A

    override fun onStartListening() {
        val deps = tileDeps()
        job?.cancel()
        job = deps.scope().launch(Dispatchers.Main.immediate) {
            // 3 sn'de bir tur (uygulama ön planda değilken StatusEngine kendi döngüsünü çalıştırmaz).
            launch {
                while (isActive) {
                    device?.let { deps.engine().requestRound(it.id) }
                    delay(if (device == null) 300 else ROUND_MS)
                }
            }
            combine(deps.settings().settings, deps.devices().observeDevices(), deps.engine().states, deps.monitor().state) { s, devices, states, wifi ->
                val d = TileBinder.deviceFor(slot, s.tileSlots, devices)
                Snapshot(s, d, d?.let { states[it.id] }, wifi.connected)
            }.collect { snap ->
                device = snap.device
                mode = snap.settings.tileMode
                render(snap.device, snap.state, snap.wifi)
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        doubleTap.reset()
    }

    private data class Snapshot(val settings: Settings, val device: Device?, val state: DeviceState?, val wifi: Boolean)

    private fun render(device: Device?, state: DeviceState?, wifi: Boolean) {
        val tile = qsTile ?: return
        val c = TileLabels.of(slot, device, state, wifi, NetPermissions.hasLocalNetwork(this), doubleTap.pending(System.currentTimeMillis()))
        tile.label = c.title
        tile.subtitle = c.subtitle
        tile.contentDescription = "${c.title}, ${c.subtitle}"
        tile.state = when (c.visual) {
            TileVisual.ACTIVE -> Tile.STATE_ACTIVE
            TileVisual.INACTIVE -> Tile.STATE_INACTIVE
            TileVisual.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.icon = c.iconKey?.let { TileIcons.of(this, it) } ?: Icon.createWithResource(this, R.drawable.ic_stat_app)
        runCatching { tile.updateTile() }
    }

    private fun rerender() {
        val deps = tileDeps()
        val d = device
        render(d, d?.let { deps.engine().state(it.id) }, deps.monitor().state.value.connected)
    }

    override fun onClick() {
        val deps = tileDeps()
        val d = device
        val state = d?.let { deps.engine().state(it.id) }
        when (TileClicks.decide(d, state, deps.monitor().state.value.connected, NetPermissions.hasLocalNetwork(this), mode)) {
            TileClick.NOTHING -> Unit
            TileClick.OPEN_SLOT_SETTINGS -> launchAndCollapse(MainActivity.tileSettingsIntent(this), REQUEST_SETTINGS + slot)
            TileClick.WAKE -> {
                // Kilit ekranında da çalışır: uyandırma zararsız, kilit açtırılmaz.
                deps.wake().wake(d!!.id, AttemptSource.TILE)
                TileVerifyWorker.enqueue(this, d.id, null)
            }
            TileClick.POWER_OVERLAY -> unlockAndRun { launchAndCollapse(TilePowerActivity.intent(this, d!!.id), REQUEST_POWER + slot) }
            TileClick.POWER_DIALOG -> unlockAndRun { showPowerDialog(d!!.id) }
            TileClick.DOUBLE_TAP -> {
                if (doubleTap.tap(System.currentTimeMillis())) {
                    rerender()
                    unlockAndRun { TilePower.execute(this, d!!.id, PowerAction.SHUTDOWN) }
                } else {
                    rerender()
                    // 3 sn dolunca alt yazı normale döner.
                    deps.scope().launch(Dispatchers.Main.immediate) {
                        delay(DoubleTap.WINDOW_MS + 50)
                        rerender()
                    }
                }
            }
        }
    }

    /** Android 14+: tile'dan activity yalnız PendingIntent ile açılır (Intent sürümü istisna atar). */
    private fun launchAndCollapse(intent: Intent, requestCode: Int) {
        val pending = PendingIntent.getActivity(
            this, requestCode, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { startActivityAndCollapse(pending) }
    }

    private fun showPowerDialog(deviceId: Long) {
        dialog?.dismiss()
        val d = TilePowerDialog.create(this, deviceId) { dialog = null }
        dialog = d
        runCatching { showDialog(d) }
    }

    private companion object {
        const val ROUND_MS = 3_000L
        const val REQUEST_SETTINGS = 7_100
        const val REQUEST_POWER = 7_200
    }
}

/** Tile'dan / panelden gelen güç komutu: koordinatörde çalışır, sonucu worker izler. */
object TilePower {
    fun execute(context: Context, deviceId: Long, action: PowerAction): Boolean {
        val started = context.tileDeps().power().execute(deviceId, action)
        if (started) TileVerifyWorker.enqueue(context, deviceId, action)
        return started
    }
}

/** Tile ikonu: cihaz ikonunun Material Symbols glifi, tek renk bitmap (sistem boyar). Anahtar başına önbellek. */
internal object TileIcons {
    private val cache = HashMap<String, Icon>()

    @Synchronized
    fun of(context: Context, iconKey: String): Icon = cache.getOrPut(iconKey) {
        val px = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = px.toFloat()
            textAlign = Paint.Align.CENTER
            typeface = runCatching { context.resources.getFont(R.font.material_symbols_outlined) }.getOrNull()
        }
        val fm = paint.fontMetrics
        Canvas(bitmap).drawText(iconSymbol(iconKey), px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, paint)
        Icon.createWithBitmap(bitmap)
    }
}

/**
 * K10: tile'dan başlayan aksiyonun doğrulaması (expedited). Akış uygulama sürecindeki koordinatörde koşar;
 * worker süreç canlı kalsın diye bitmesini bekler ve yalnız başarısızlıkta bildirim gönderir.
 * Uyandırma süreç öldüğü için hiç başlamadıysa burada başlatılır (`WakeAttempt(TILE)`).
 */
class TileVerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_DEVICE, -1)
        if (id < 0) return Result.success()
        val action = inputData.getString(KEY_ACTION)?.let { name -> PowerAction.entries.firstOrNull { it.name == name } }
        val deps = applicationContext.tileDeps()
        val device = deps.engine().awaitDevice(id) ?: return Result.success()
        if (action == null) {
            if (!deps.wake().isWaking(id) && deps.engine().state(id) !is DeviceState.On) {
                withTimeoutOrNull(3_000) { deps.monitor().state.first { it.connected } }
                deps.wake().wake(id, AttemptSource.TILE)
            }
            deps.wake().join(id)
            if (deps.engine().state(id) !is DeviceState.On) notifyFailure(device, PowerAction.WAKE)
        } else {
            deps.power().join(id)
            val off = action == PowerAction.SHUTDOWN || action == PowerAction.SLEEP || action == PowerAction.HIBERNATE
            if (off && deps.engine().state(id) is DeviceState.On) notifyFailure(device, action)
        }
        return Result.success()
    }

    private fun notifyFailure(device: Device, action: PowerAction) {
        val context = applicationContext
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Hızlı ayarlar", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Tile'dan verilen komut başarısız olursa" },
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(ScheduleTexts.failureTitle(device.name, action))
            .setContentText(if (action == PowerAction.WAKE) "Paket gitti · cevap yok" else "Komut gitti · PC hâlâ açık")
            .setSubText("Hızlı ayarlar")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + device.id.toInt(), notification) }
    }

    companion object {
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_ACTION = "action"
        private const val CHANNEL = "tiles"
        private const val NOTIFICATION_BASE = 20_000

        /** [action] null = uyandırma. */
        fun enqueue(context: Context, deviceId: Long, action: PowerAction?) {
            val request = OneTimeWorkRequestBuilder<TileVerifyWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_DEVICE to deviceId, KEY_ACTION to action?.name))
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork("tile-$deviceId", ExistingWorkPolicy.REPLACE, request) }
        }
    }
}
