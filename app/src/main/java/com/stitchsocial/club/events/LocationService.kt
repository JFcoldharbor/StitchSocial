package com.stitchsocial.club.events

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Layer 3: Thin wrapper around FusedLocation (device fix) + Geocoder (venue
 * lookup) — the Android analog of iOS LocationService. WhenInUse / foreground
 * only (no background location). The polished POI place picker will move to the
 * cross-platform Places proxy later; this covers the geofence + a basic venue pin.
 */
class LocationService(private val context: Context) {

    data class Fix(val lat: Double, val lng: Double, val accuracyMeters: Float)
    data class GeoPlace(val name: String, val city: String, val lat: Double, val lng: Double, val address: String)

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** Current device fix (fresh, then last-known fallback). Null if denied/unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(): Fix? {
        if (!hasPermission()) return null
        return runCatching {
            val loc = fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token).await()
                ?: fused.lastLocation.await()
            loc?.let { Fix(it.latitude, it.longitude, it.accuracy) }
        }.getOrNull()
    }

    /** Forward-geocode a typed venue/address into candidate places. */
    @Suppress("DEPRECATION")
    suspend fun geocode(query: String): List<GeoPlace> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(q, 5)?.mapNotNull { a ->
                GeoPlace(
                    name = a.featureName ?: a.thoroughfare ?: a.locality ?: q,
                    city = a.locality ?: a.subAdminArea ?: "",
                    lat = a.latitude,
                    lng = a.longitude,
                    address = a.getAddressLine(0) ?: ""
                )
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Reverse-geocode a fix into a place (to prefill venue name/city from "use current location"). */
    @Suppress("DEPRECATION")
    suspend fun reverseGeocode(lat: Double, lng: Double): GeoPlace? = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
                GeoPlace(
                    name = a.featureName ?: a.thoroughfare ?: a.locality ?: "Current location",
                    city = a.locality ?: a.subAdminArea ?: "",
                    lat = lat, lng = lng,
                    address = a.getAddressLine(0) ?: ""
                )
            }
        }.getOrNull()
    }
}
