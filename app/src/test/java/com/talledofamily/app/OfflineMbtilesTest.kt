package com.talledofamily.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class OfflineMbtilesTest {
    private lateinit var context:Context
    @Before fun setup() { context=RuntimeEnvironment.getApplication(); OfflineMapStore.delete(context) }
    private fun fixture(format:String="png",zoom:Int=1):File {
        val f=File.createTempFile("fixture-",".mbtiles",context.cacheDir)
        SQLiteDatabase.openOrCreateDatabase(f,null).use { db ->
            db.execSQL("CREATE TABLE metadata(name TEXT,value TEXT)")
            db.execSQL("CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB)")
            db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles(zoom_level,tile_column,tile_row)")
            db.execSQL("INSERT INTO metadata VALUES ('name','Mapa de prueba sintético'),('format',?),('attribution','© Datos sintéticos')",arrayOf(format))
            val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val png=ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray()
            bitmap.recycle()
            // The north-west XYZ tile 1/0/0 is stored as TMS 1/0/1.
            db.execSQL("INSERT INTO tiles VALUES (?,0,?,?)",arrayOf(zoom,(1 shl zoom)-1,png))
        }
        return f
    }
    @Test fun readsRasterAndFlipsTms() {
        val f=fixture()
        OfflineMbtiles(f).use { map ->
            assertEquals("Mapa de prueba sintético",map.info.name)
            assertEquals(listOf(1),map.info.zooms)
            assertEquals(0.25,map.info.centerX,1e-9); assertEquals(0.25,map.info.centerY,1e-9)
            val image=map.tile(1,0,0)!!; assertEquals(Color.RED,image.getPixel(128,128)); image.recycle()
            assertNull(map.tile(1,0,1))
        }
    }
    @Test fun importSurvivesReopeningAndKeepsOriginal() {
        val f=fixture(); val before=f.readBytes()
        OfflineMapStore.import(context,Uri.fromFile(f))
        assertEquals("Mapa de prueba sintético",OfflineMapStore.load(context)!!.name)
        assertArrayEquals(before,f.readBytes())
    }
    @Test fun invalidImportPreservesExistingMap() {
        OfflineMapStore.import(context,Uri.fromFile(fixture()))
        val before=OfflineMapStore.file(context).readBytes()
        val invalid=File.createTempFile("invalid-",".mbtiles",context.cacheDir).apply { writeText("not sqlite") }
        assertTrue(runCatching { OfflineMapStore.import(context,Uri.fromFile(invalid)) }.isFailure)
        assertArrayEquals(before,OfflineMapStore.file(context).readBytes())
    }
    @Test fun vectorMapRejectedWithoutReplacingExisting() {
        OfflineMapStore.import(context,Uri.fromFile(fixture()))
        val result=runCatching { OfflineMapStore.import(context,Uri.fromFile(fixture("pbf"))) }
        assertTrue(result.isFailure); assertTrue(result.exceptionOrNull()!!.message!!.contains("PBF"))
        assertEquals("Mapa de prueba sintético",OfflineMapStore.load(context)!!.name)
    }
    @Test fun removingCopyDoesNotRemoveOriginal() {
        val f=fixture(); OfflineMapStore.import(context,Uri.fromFile(f)); OfflineMapStore.delete(context)
        assertFalse(OfflineMapStore.file(context).exists()); assertTrue(f.exists())
    }
    @Test fun boundedCopyRejectsLargeOrEmptyFile() {
        assertTrue(runCatching { OfflineMapStore.copyBounded(ByteArrayInputStream(ByteArray(11)),ByteArrayOutputStream(),10) }.isFailure)
        assertTrue(runCatching { OfflineMapStore.copyBounded(ByteArrayInputStream(ByteArray(0)),ByteArrayOutputStream()) }.isFailure)
        val out=ByteArrayOutputStream(); OfflineMapStore.copyBounded(ByteArrayInputStream(byteArrayOf(1,2,3)),out,3)
        assertArrayEquals(byteArrayOf(1,2,3),out.toByteArray())
    }
    @Test fun interruptedWriteRecoversPreviousMap() {
        OfflineMapStore.import(context,Uri.fromFile(fixture()))
        val file=OfflineMapStore.file(context)
        val atomic=android.util.AtomicFile(file); val output=atomic.startWrite()
        output.write(byteArrayOf(1,2,3)); output.close() // emulate an interrupted import
        assertEquals("Mapa de prueba sintético",OfflineMapStore.load(context)!!.name)
    }
    @Test fun sparseZoomsUseOnlyAvailableLevels() {
        val f=fixture(zoom=3)
        OfflineMbtiles(f).use { assertEquals(listOf(3),it.info.zooms); assertEquals(3,it.info.startZoom) }
    }
    @Test fun oversizedImageIsRejected() {
        val f=fixture()
        val bitmap=Bitmap.createBitmap(1025,1,Bitmap.Config.ARGB_8888)
        val png=ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray(); bitmap.recycle()
        SQLiteDatabase.openDatabase(f.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE tiles SET tile_data=?",arrayOf(png)) }
        assertTrue(runCatching { OfflineMbtiles(f).close() }.isFailure)
    }
    @Test fun xyzSchemeIsRejected() {
        val f=fixture()
        SQLiteDatabase.openDatabase(f.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("INSERT INTO metadata VALUES ('scheme','xyz')") }
        assertTrue(runCatching { OfflineMbtiles(f).close() }.isFailure)
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun viewerRendersLocalRaster() {
        val info=OfflineMapStore.import(context,Uri.fromFile(fixture()))
        val controller=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity=controller.get()
        val view=OfflineMapView(activity,info)
        activity.setContentView(view)
        view.layout(0,0,128,128)
        val image=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        var rendered=false
        try {
            val deadline=System.nanoTime()+10_000_000_000L
            while(!rendered && System.nanoTime()<deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                view.draw(android.graphics.Canvas(image))
                rendered=image.getPixel(64,64)==Color.RED
                if(!rendered) Thread.sleep(20)
            }
            assertTrue("The local raster should be drawn without any map SDK or tile server",rendered)
        } finally {
            activity.setContentView(android.view.View(activity))
            controller.pause().stop().destroy(); image.recycle()
        }
    }
}
