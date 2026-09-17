package com.talledofamily.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LocationSharing {
    val running=MutableStateFlow(false)
    val status=MutableStateFlow("Ubicación no compartida")
    val mutex=Mutex()
    @Volatile var enabled=false
    @Volatile var pendingPause=false
    fun start(context:Context) {
        check(SessionVault.current()!=null) { "Inicia sesión" }
        check(!pendingPause) { "Primero espera que se sincronice la pausa pendiente con Internet" }
        enabled=true
        try { ContextCompat.startForegroundService(context,Intent(context,LocationShareService::class.java)) }
        catch(e:Exception){enabled=false;throw e}
    }
    suspend fun pause(context:Context) {
        enabled=false
        context.stopService(Intent(context,LocationShareService::class.java))
        pendingPause=true
        SessionVault.markPausePending(true)
        mutex.withLock {
            val session=SessionVault.current()
            if(session!=null) SupabaseService().pauseLocation(session)
            pendingPause=false
            SessionVault.markPausePending(false)
            status.value="Ubicación pausada; coordenadas retiradas del servidor"
        }
    }
}

class LocationShareService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var fused:FusedLocationProviderClient
    private var lastSent=0L
    private val callback=object:LocationCallback() {
        override fun onLocationResult(result:LocationResult) {
            val location=result.lastLocation ?: return
            if(System.currentTimeMillis()-location.time>120000 || SystemClock.elapsedRealtime()-lastSent<25000) return
            lastSent=SystemClock.elapsedRealtime()
            scope.launch {
                LocationSharing.mutex.withLock {
                    if(!LocationSharing.enabled) return@withLock
                    val session=SessionVault.current() ?: return@withLock
                    try {
                        val battery=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
                        val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,-1)?:-1
                        val percent=if(level>=0 && scale>0) (100*level/scale).coerceIn(0,100) else null
                        val charging=(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)?:0)!=0
                        SupabaseService().publishLocation(session,location,percent,charging)
                        LocationSharing.status.value="Último envío: "+java.time.LocalTime.now().withNano(0)
                    } catch(e:Exception) {
                        LocationSharing.status.value="Sin sincronizar: "+(e.message?:"revisa la conexión")
                    }
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        fused=LocationServices.getFusedLocationProviderClient(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tf_location","Ubicación familiar",NotificationManager.IMPORTANCE_LOW))
    }
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!LocationSharing.enabled || SessionVault.current()==null ||
            (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)) {
            stopSelf(); return START_NOT_STICKY
        }
        val open=PendingIntent.getActivity(this,0,Intent(this,RealMainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,LocationShareService::class.java).setAction("PAUSE"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"tf_location")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("TALLEDO FAMILY · ubicación compartida")
            .setContentText("Compartiendo con familiares autorizados. Pulsa PAUSAR para detener.")
            .setContentIntent(open).setOngoing(true).addAction(0,"PAUSAR",stop).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(41,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(41,notification)
        if(intent?.action=="PAUSE") {
            LocationSharing.enabled=false
            CoroutineScope(Dispatchers.IO).launch {
                try { LocationSharing.pause(this@LocationShareService) }
                catch(e:Exception){ LocationSharing.status.value="Pausado en este teléfono. No se pudo retirar la última ubicación: revisa Internet."; stopSelf() }
            }
            return START_NOT_STICKY
        }
        fused.removeLocationUpdates(callback)
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,30000)
            .setMinUpdateIntervalMillis(15000).setMaxUpdateDelayMillis(60000).build()
        fused.requestLocationUpdates(request,callback,Looper.getMainLooper()).addOnFailureListener {
            LocationSharing.status.value="No se pudo iniciar GPS: "+it.message
            LocationSharing.enabled=false;stopSelf()
        }
        LocationSharing.running.value=true
        LocationSharing.status.value="Buscando ubicación; envío automático cada ~30 segundos"
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        LocationSharing.running.value=false
        LocationSharing.enabled=false
        fused.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
}
