package com.yaz.sms.core.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Where the phone is, once, when the user taps Place in the composer:
 * Android's own location (no Google service needed), a fix of the last two
 * minutes when there is one, else a fresh one within 30 seconds. Never in
 * the background, never kept.
 */
object HereNow {

    fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun find(context: Context): Location? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60 * 1000L }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { done ->
                val cancel = CancellationSignal()
                done.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(providers.first(), cancel, ContextCompat.getMainExecutor(context)) { location ->
                    if (done.isActive) done.resume(location)
                }
            }
        }
    }

    /** The place as a link any phone opens, on OpenStreetMap. */
    fun link(location: Location): String {
        val lat = "%.5f".format(java.util.Locale.ROOT, location.latitude)
        val lon = "%.5f".format(java.util.Locale.ROOT, location.longitude)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
    }
}
