package com.talledofamily.app

import org.junit.Assert.*
import org.junit.Test

class OfflineMapMathTest {
    @Test fun equatorAndOrigin() { assertEquals(0.5,OfflineMapMath.x(0.0),1e-9); assertEquals(0.5,OfflineMapMath.y(0.0),1e-9) }
    @Test fun projectionRoundTrip() {
        listOf(-85.0,-5.2,0.0,40.0,85.0).forEach { assertEquals(it,OfflineMapMath.latitude(OfflineMapMath.y(it)),1e-7) }
        listOf(-180.0,-80.6,0.0,180.0).forEach { assertEquals(it,OfflineMapMath.longitude(OfflineMapMath.x(it)),1e-9) }
    }
    @Test fun polesAreClamped() { assertEquals(0.0,OfflineMapMath.y(90.0),1e-8); assertEquals(1.0,OfflineMapMath.y(-90.0),1e-8) }
    @Test fun officialTmsExample() { assertEquals(1256,OfflineMapMath.tmsRow(11,791)) }
    @Test fun tmsRoundTrip() { (0..20).forEach { z -> val row=(1 shl z)/2; assertEquals(row,OfflineMapMath.tmsRow(z,OfflineMapMath.tmsRow(z,row))) } }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidRow() { OfflineMapMath.tmsRow(2,4) }
}
