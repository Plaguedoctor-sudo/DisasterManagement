package network.disaster.management.system

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import java.util.Locale

class GpsBeaconHelper(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    data class GeoCoordinates(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val timestamp: Long
    ) {
        fun toBeaconString(): String {
            return String.format(
                Locale.US,
                "LOC:%.5f,%.5f|ALT:%.1fm|ACC:%.1fm",
                latitude, longitude, altitude, accuracy
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun getFastestCoordinates(): GeoCoordinates? {
        val lm = locationManager ?: return null

        val gpsLoc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { null }

        val netLoc = try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { null }

        val best = when {
            gpsLoc != null && netLoc != null -> {
                if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
            }
            gpsLoc != null -> gpsLoc
            netLoc != null -> netLoc
            else -> null
        } ?: return null

        return GeoCoordinates(
            latitude = best.latitude,
            longitude = best.longitude,
            altitude = best.altitude,
            accuracy = best.accuracy,
            timestamp = best.time
        )
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0

        /**
         * Computes Great-Circle Haversine distance in meters between two WGS-84 coordinates.
         */
        fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return EARTH_RADIUS_METERS * c
        }

        /**
         * Extracts latitude and longitude from tactical beacon tags (e.g., "LOC:37.77490,-122.41940|...")
         */
        fun parseBeaconCoordinates(beaconStr: String): Pair<Double, Double>? {
            return try {
                val locTag = beaconStr.substringAfter("LOC:").substringBefore("|").substringBefore(" ")
                val parts = locTag.split(",")
                if (parts.size >= 2) {
                    val lat = parts[0].trim().toDouble()
                    val lon = parts[1].trim().toDouble()
                    Pair(lat, lon)
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
