package com.kdgames.wake_on_lan.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

/**
 * Ağ ile ilgili runtime izinleri (spec 14). Tam ekran kapılar S11'de; burada yalnız durum ve istek listeleri.
 *
 * - `ACCESS_LOCAL_NETWORK` (Android 17, NEARBY_DEVICES grubu): targetSdk 37'de LAN'a giden/gelen
 *   tüm TCP/UDP (broadcast, multicast/mDNS, NSD, `.local`) bu izne bağlı. Yoksa TCP zaman aşımına düşer,
 *   UDP `EPERM` verir.
 * - SSID: `FLAG_INCLUDE_LOCATION_INFO` + `ACCESS_FINE_LOCATION` + konum servisi açık. Biri eksikse
 *   Android SSID'yi `<unknown ssid>` olarak gizler; diğer Wi-Fi bilgileri gelmeye devam eder.
 */
object NetPermissions {
    const val LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK

    /** Android 12+ kesin konum için yaklaşığı da birlikte istemeyi şart koşar. */
    val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasLocalNetwork(context: Context) = granted(context, LOCAL_NETWORK)

    fun hasFineLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun isLocationServiceOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /** SSID okunabilir mi: izin + konum servisi. */
    fun canReadSsid(context: Context) = hasFineLocation(context) && isLocationServiceOn(context)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
