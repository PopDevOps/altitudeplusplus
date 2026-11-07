package com.altitudeplusplus.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * Supplies coordinates for Altitude+'s territory check. Without permission (or
 * a fix) stream requests omit latitude/longitude and the server falls back to
 * the request's IP address, as documented in docs/API.md.
 */
class LocationProvider(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var cached: Pair<Coordinate, Long>? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** A recent coordinate, or null when unavailable. Cached for 10 minutes. */
    suspend fun current(): Coordinate? {
        cached?.let { (coordinate, at) -> if (System.currentTimeMillis() - at < 10 * 60_000) return coordinate }
        if (!hasPermission()) return null
        val location = withTimeoutOrNull(8_000) { freshLocation() } ?: lastKnown()
        return location?.let { Coordinate(it.latitude, it.longitude) }?.also {
            cached = it to System.currentTimeMillis()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val signal = android.os.CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        manager.getCurrentLocation(provider, signal, Executors.newSingleThreadExecutor()) {
            if (continuation.isActive) continuation.resume(it)
        }
    }
}
