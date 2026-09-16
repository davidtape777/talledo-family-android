package com.talledofamily.app

import android.content.Context
import android.graphics.*
import android.location.Location
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.*

internal data class OfflineTileKey(val z: Int, val x: Int, val y: Int)

/** A local-only raster viewer, independent of Google Maps and network services. */
internal class OfflineMapView(context: Context, private val info: OfflineMapInfo) : View(context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var database: OfflineMbtiles? = null // worker-thread ownership
    private val cache = object : LruCache<OfflineTileKey, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: OfflineTileKey, value: Bitmap) = value.byteCount
    }
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var tiles = emptyMap<OfflineTileKey, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tileSize = 256f * resources.displayMetrics.density
    private var cx = info.centerX; private var cy = info.centerY
    private var zoom = info.startZoom
    private var lastX = 0f; private var lastY = 0f; private var pinch = 1f
    private var local: Location? = null
    var onReadError: ((String) -> Unit)? = null
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinch = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinch *= detector.scaleFactor
            if (pinch > 1.5f) { zoomBy(1); pinch = 1f }
            if (pinch < 0.67f) { zoomBy(-1); pinch = 1f }
            return true
        }
    })
    init { contentDescription = "Mapa local: arrastra para mover y usa los botones para acercar o alejar" }
    private fun world() = tileSize * (1 shl zoom)
    private fun moveTo(x: Double, y: Double) {
        cx = x.coerceIn(0.0, 1.0); cy = y.coerceIn(0.0, 1.0)
        invalidate(); schedule()
    }
    fun zoomBy(direction: Int) {
        val index = info.zooms.indexOf(zoom)
        zoom = info.zooms[(index + direction).coerceIn(0, info.zooms.lastIndex)]
        invalidate(); schedule()
    }
    fun resetRegion() { zoom = info.startZoom; moveTo(info.centerX, info.centerY) }
    fun setLocalLocation(location: Location?) { local = location; invalidate() }
    fun centerLocal() { local?.let { moveTo(OfflineMapMath.x(it.longitude), OfflineMapMath.y(it.latitude)) } }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); schedule() }
    private val render = Runnable { loadVisible() }
    private fun schedule() {
        generation++
        main.removeCallbacks(render)
        if (!closed) main.postDelayed(render, 30)
    }
    private fun loadVisible() {
        if (closed || width == 0 || height == 0) return
        val token = generation; val z = zoom; val n = 1 shl z; val world = world()
        val left = cx * world - width / 2; val top = cy * world - height / 2
        val x0 = floor(left / tileSize).toInt().coerceIn(0, n-1)
        val x1 = floor((left+width) / tileSize).toInt().coerceIn(0, n-1)
        val y0 = floor(top / tileSize).toInt().coerceIn(0, n-1)
        val y1 = floor((top+height) / tileSize).toInt().coerceIn(0, n-1)
        val keys = (x0..x1).flatMap { x -> (y0..y1).map { y -> OfflineTileKey(z,x,y) } }.take(200)
        executor.execute {
            if (closed || token != generation) return@execute
            runCatching {
                val db = database ?: OfflineMbtiles(OfflineMapStore.file(context)).also { database = it }
                val result = mutableMapOf<OfflineTileKey, Bitmap>()
                for (key in keys) {
                    if (closed || token != generation) return@execute
                    val image = cache.get(key) ?: db.tile(key.z,key.x,key.y)?.also { cache.put(key,it) }
                    if (image != null) result[key] = image
                }
                main.post { if (!closed && token == generation) { tiles = result; invalidate() } }
            }.onFailure { main.post { if (!closed) onReadError?.invoke("No se pudo leer el mapa local. Importa un archivo compatible.") } }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(238,238,243))
        val world = world(); val left = cx * world - width/2; val top = cy * world - height/2
        paint.color = Color.DKGRAY; paint.textSize = 14 * resources.displayMetrics.scaledDensity
        canvas.drawText("Sin imagen aquí · mueve el mapa o vuelve a ZONA", 10f, height/2f, paint)
        tiles.filterKeys { it.z == zoom }.forEach { (key,bitmap) ->
            val x = (key.x * tileSize - left).toFloat(); val y = (key.y * tileSize - top).toFloat()
            canvas.drawBitmap(bitmap,null,RectF(x,y,x+tileSize+1,y+tileSize+1),paint)
        }
        local?.let {
            val x = (OfflineMapMath.x(it.longitude)*world-left).toFloat()
            val y = (OfflineMapMath.y(it.latitude)*world-top).toFloat()
            val r = 9 * resources.displayMetrics.density
            paint.color = Color.WHITE; canvas.drawCircle(x,y,r+3,paint)
            paint.color = Color.rgb(85,65,210); canvas.drawCircle(x,y,r,paint)
            paint.color = Color.WHITE; canvas.drawRect(x+r,y-r*2,x+r+paint.measureText("Este dispositivo")+12,y+r/2,paint)
            paint.color = Color.DKGRAY; canvas.drawText("Este dispositivo",x+r+6,y,paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); lastX=event.x; lastY=event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scale.isInProgress && event.pointerCount == 1) moveTo(cx-(event.x-lastX)/world(),cy-(event.y-lastY)/world())
                lastX=event.x; lastY=event.y
            }
            MotionEvent.ACTION_UP -> { performClick(); parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun close() {
        if(closed) return
        closed=true; generation++; main.removeCallbacks(render)
        executor.execute { database?.close(); database=null; cache.evictAll() }
        executor.shutdown(); tiles=emptyMap()
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}
