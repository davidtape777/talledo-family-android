package com.talledofamily.app

import kotlin.math.*

/** MBTiles uses Web Mercator and TMS rows (south to north), not XYZ rows. */
internal object OfflineMapMath {
    const val MAX_LAT = 85.05112878
    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0).coerceIn(0.0, 1.0)
    }
    fun longitude(x: Double): Double = x * 360.0 - 180.0
    fun latitude(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
    fun tmsRow(zoom: Int, xyzRow: Int): Int {
        require(zoom in 0..20)
        require(xyzRow in 0 until (1 shl zoom))
        return (1 shl zoom) - 1 - xyzRow
    }
}
