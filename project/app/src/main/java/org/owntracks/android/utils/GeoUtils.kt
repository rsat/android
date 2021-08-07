package org.owntracks.android.utils

import com.google.android.gms.maps.model.LatLng


object GeoUtils {

    fun polylineDecoder(encoded: String, precision: Double = 6.0): List<LatLng>? {
        var precision = precision
        val array: MutableList<LatLng> = ArrayList()
        var b = 0
        var dlat = 0
        var dlng = 0
        var index = 0
        var lat = 0
        var lng = 0
        var result = 0
        var shift = 0
        var len = 0
        precision = Math.pow(10.0, -precision)
        len = encoded.length
        index = 0
        lat = 0
        lng = 0
        while (index < len) {
            b = 0
            shift = 0
            result = 0
            while (true) {
                b = encoded[index++].toInt() - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
                if (b < 0x20) {
                    break
                }
            }
            dlat = if (result and 1 > 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            while (true) {
                b = encoded[index++].toInt() - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
                if (b < 0x20) {
                    break
                }
            }
            dlng = if (result and 1 > 0) (result shr 1).inv() else result shr 1
            lng += dlng
            array.add(LatLng(lat * precision, lng * precision))
        }
        return array
    }
}