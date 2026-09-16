package com.talledofamily.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class OfflineMapInfo(
    val name: String, val attribution: String, val zooms: List<Int>,
    val centerX: Double, val centerY: Double, val startZoom: Int, val bytes: Long
)

/** Only local reads; no tile URL, downloader, account or network client. */
internal class OfflineMbtiles(file: File) : AutoCloseable {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    val info: OfflineMapInfo
    init {
        try {
            db.rawQuery("PRAGMA quick_check(1)", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "Archivo SQLite dañado" } }
            val metadata = mutableMapOf<String, String>()
            db.rawQuery("SELECT name, value FROM metadata LIMIT 100", null).use { c ->
                while (c.moveToNext()) metadata[c.getString(0)] = c.getString(1).take(4096)
            }
            require(metadata["scheme"].let { it == null || it == "tms" }) { "El archivo debe usar filas TMS" }
            require(metadata["format"]?.lowercase() in setOf("png", "jpg", "jpeg", "webp")) {
                "Usa MBTiles de imágenes PNG/JPG/WebP; los mapas vectoriales PBF no son compatibles."
            }
            val zooms = mutableListOf<Int>()
            db.rawQuery("SELECT DISTINCT zoom_level FROM tiles ORDER BY zoom_level LIMIT 22", null).use { c ->
                while (c.moveToNext()) { val z = c.getInt(0); require(z in 0..20); zooms += z }
            }
            require(zooms.isNotEmpty()) { "El mapa no contiene imágenes" }
            // Validate a real image and derive a guaranteed populated initial tile.
            var cx = 0.5; var cy = 0.5
            val z = zooms.first()
            db.rawQuery("SELECT tile_column, tile_row FROM tiles WHERE zoom_level=? LIMIT 1", arrayOf(z.toString())).use { c ->
                require(c.moveToFirst())
                val tx = c.getInt(0); val ty = c.getInt(1); val n = 1 shl z
                require(tx in 0 until n && ty in 0 until n) { "Coordenadas de mapa inválidas" }
                cx = (tx + 0.5) / n; cy = (OfflineMapMath.tmsRow(z, ty) + 0.5) / n
                val sample = tile(z, tx, OfflineMapMath.tmsRow(z, ty))
                require(sample != null) { "La imagen de muestra no es compatible" }
                sample.recycle()
            }
            val center = metadata["center"]?.split(',')?.map { it.trim().toDoubleOrNull() }
            val start = if (center != null && center.size >= 3 && center.all { it != null && it.isFinite() } &&
                center[0]!! in -180.0..180.0 && center[1]!! in -OfflineMapMath.MAX_LAT..OfflineMapMath.MAX_LAT) {
                cx = OfflineMapMath.x(center[0]!!); cy = OfflineMapMath.y(center[1]!!)
                zooms.minBy { kotlin.math.abs(it - center[2]!!) }
            } else z
            val attribution = Html.fromHtml(metadata["attribution"].orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().trim()
            info = OfflineMapInfo(metadata["name"]?.take(120) ?: "Mapa importado",
                attribution.ifBlank { "Fuente no indicada en el archivo: verifica su licencia." }, zooms, cx, cy, start, file.length())
        } catch (e: Exception) { db.close(); throw e }
    }
    @Synchronized fun tile(z: Int, x: Int, xyzY: Int): Bitmap? {
        val row = OfflineMapMath.tmsRow(z, xyzY)
        return db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? AND length(tile_data)<=2097152 LIMIT 1",
            arrayOf(z.toString(), x.toString(), row.toString())).use { c ->
            if (!c.moveToFirst()) return@use null
            val bytes = c.getBlob(0)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return@use null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }
    }
    @Synchronized override fun close() = db.close()
}

internal object OfflineMapStore {
    const val MAX_BYTES = 500L * 1024 * 1024
    fun file(context: Context) = File(context.noBackupFilesDir, "offline-map.mbtiles")
    fun load(context: Context): OfflineMapInfo? {
        AtomicFile(file(context)).openRead().use { /* Also recover interrupted AtomicFile writes. */ }
        return OfflineMbtiles(file(context)).use { it.info }
    }
    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long = MAX_BYTES) {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            total += n
            require(total <= maxBytes) { "El mapa supera el límite de 500 MB" }
            output.write(buffer, 0, n)
        }
        require(total > 0) { "Archivo vacío" }
    }
    fun import(context: Context, uri: Uri): OfflineMapInfo {
        val temporary = File.createTempFile("import-", ".mbtiles", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir el archivo seleccionado" }
                temporary.outputStream().use { copyBounded(input, it) }
            }
            val info = OfflineMbtiles(temporary).use { it.info }
            val atomic = AtomicFile(file(context)); val output = atomic.startWrite()
            try {
                temporary.inputStream().use { copyBounded(it, output) }
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
            return info
        } finally { temporary.delete() }
    }
    fun delete(context: Context) = AtomicFile(file(context)).delete()
}
