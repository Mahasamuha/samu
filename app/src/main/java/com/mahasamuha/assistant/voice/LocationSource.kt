package com.mahasamuha.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * City-level place. Coordinates are rounded to 0.1° (about 10 km) so that nothing more precise is
 * ever sent off the phone.
 */
data class ApproximateLocation(
    val city: String?,
    val region: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
) {
    val description: String? get() = listOfNotNull(city, region).joinToString(", ").takeIf { it.isNotEmpty() }
}

interface LocationSource {
    /** The user's approximate location. Null when permission is off or nothing is available in time. */
    suspend fun current(): ApproximateLocation?

    /** A named place ("Denver", "Paris, France"). Null if it can't be found. */
    suspend fun find(name: String): ApproximateLocation?
}

/** Coarse fused location; geocoding in both directions uses the platform Geocoder. */
class PlatformLocationSource(
    context: Context,
) : LocationSource {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override suspend fun current(): ApproximateLocation? {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val location = recentLocation() ?: withTimeoutOrNull(3_000) { freshLocation() } ?: return null
        val address =
            withTimeoutOrNull(3_000) {
                geocode { listener ->
                    geocoder().getFromLocation(location.latitude, location.longitude, 1, listener)
                }
            }
        return approximate(address, location.latitude, location.longitude)
    }

    override suspend fun find(name: String): ApproximateLocation? {
        val address = withTimeoutOrNull(5_000) { geocode { listener -> geocoder().getFromLocationName(name, 1, listener) } } ?: return null
        return approximate(address, address.latitude, address.longitude)
    }

    private fun approximate(
        address: Address?,
        latitude: Double,
        longitude: Double,
    ) = ApproximateLocation(
        city = address?.locality ?: address?.subAdminArea,
        region = address?.adminArea,
        countryCode = address?.countryCode,
        latitude = (latitude * 10).roundToInt() / 10.0,
        longitude = (longitude * 10).roundToInt() / 10.0,
    )

    @Suppress("MissingPermission") // checked in current()
    private fun recentLocation(): Location? =
        locationManager
            .getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000 }

    @Suppress("MissingPermission") // checked in current()
    private suspend fun freshLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            locationManager.getCurrentLocation(LocationManager.FUSED_PROVIDER, signal, appContext.mainExecutor) { cont.resume(it) }
        }

    private fun geocoder() = Geocoder(appContext, Locale.getDefault())

    private suspend fun geocode(request: (Geocoder.GeocodeListener) -> Unit): Address? {
        if (!Geocoder.isPresent()) return null
        return suspendCancellableCoroutine { cont ->
            request(
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())

                    override fun onError(errorMessage: String?) {
                        Log.w(TAG, "Geocoding failed: $errorMessage")
                        cont.resume(null)
                    }
                },
            )
        }
    }
}
