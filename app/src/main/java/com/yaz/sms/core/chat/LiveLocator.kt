package com.yaz.sms.core.chat

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import androidx.core.content.ContextCompat
import com.yaz.sms.core.sms.HereNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reads the phone's position for the live location being shared, and only while an SMS screen is
 * shown (ChatLife calls [resume] and [pause]): nothing runs in the background, so the others get
 * points while the app is open, and again when it is opened within the sharing period, which the
 * engine keeps (RichChat.shareLocation). Ported from Delta Chat Android
 * (https://github.com/deltachat/deltachat-android): geolocation/PlatformLocationSource.java
 * (fused provider first, else GPS and network, high accuracy), v1.58.0
 * geolocation/LocationBackgroundService.java (one reading a second at 25 m, a last fix of the last
 * ten minutes used at once) and geolocation/LocationStreamingService.java (no permission: the
 * sharing is stopped; the engine saying it no longer shares: the readings stop). Without its
 * foreground service.
 */
class LiveLocator(private val context: Context, private val chat: RichChat, private val scope: CoroutineScope) : LocationListener {

    private val manager = context.getSystemService(LocationManager::class.java)

    @Volatile private var visible = false
    private var listening = false

    /** An SMS screen shows: the readings resume if a sharing is going on (LocationStreamingService.ensureRunning). */
    fun resume() {
        visible = true
        scope.launch {
            if (!chat.syncLive()) return@launch
            if (HereNow.allowed(context)) begin() else chat.stopAllLocations()
        }
    }

    /** No SMS screen shows: the phone is no longer read. */
    fun pause() {
        visible = false
        stop()
    }

    /** The user starts sharing with [phones]; false when the permission, the engine or the chat refuses. */
    suspend fun share(phones: List<String>, seconds: Int): Boolean {
        if (!HereNow.allowed(context) || !chat.shareLocation(phones, seconds)) return false
        begin()
        return true
    }

    /** The user stops sharing with [phones]; the readings stop with the last sharing (LocationStreamingService.stopSharing). */
    suspend fun stopSharing(phones: List<String>): Boolean {
        if (!chat.stopLocation(phones)) return false
        if (!chat.syncLive()) stop()
        return true
    }

    @Synchronized
    @android.annotation.SuppressLint("MissingPermission")
    private fun begin() {
        val manager = manager ?: return
        if (!visible || listening || !HereNow.allowed(context)) return
        val enabled = { provider: String -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        val providers = if (enabled(LocationManager.FUSED_PROVIDER)) listOf(LocationManager.FUSED_PROVIDER)
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter(enabled)
        if (providers.isEmpty()) return
        val request = LocationRequest.Builder(LiveLocation.INTERVAL_MS)
            .setMinUpdateDistanceMeters(LiveLocation.DISTANCE_M)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        val executor = ContextCompat.getMainExecutor(context)
        providers.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, request, executor, this) }
        }
        listening = true
        // A fix of the last ten minutes goes out at once.
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time <= LiveLocation.LAST_FIX_MS }
            .maxByOrNull { it.time }
            ?.let(::onLocationChanged)
    }

    @Synchronized
    private fun stop() {
        if (!listening) return
        listening = false
        runCatching { manager?.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        if (!visible) return
        scope.launch {
            if (!chat.pushLocation(location.latitude, location.longitude, location.accuracy.toDouble())) stop()
        }
    }
}
