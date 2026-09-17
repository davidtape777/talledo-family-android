package com.talledofamily.app

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** One bounded playback, not an always-on listener. No text persisted or logged. */
class FamilyVoiceService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var voice:FamilyVoice?=null
    private val screenOff=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){voice?.stop();stopSelf()}}
    override fun onCreate(){super.onCreate();ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),ContextCompat.RECEIVER_NOT_EXPORTED)}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP"){voice?.stop();stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("family_voice_playback","Lectura de mensajes",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,7,Intent(this,FamilyVoiceService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"family_voice_playback").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TALLEDO FAMILY · Voz").setContentText("Lectura de un mensaje familiar")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setSilent(true)
            .addAction(0,"DETENER",stop).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(7007,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7007,notification)
        job?.cancel();voice?.close();voice=null
        val id=intent?.getStringExtra("notice_id")
        val user=intent?.getStringExtra("user_id")
        job=scope.launch {
            var localReader:FamilyVoice?=null
            try {
                withTimeout(30000) {
                    val session=SessionVault.current()?:return@withTimeout
                    if(session.userId!=user || id==null || !runCatching{java.util.UUID.fromString(id)}.isSuccess) return@withTimeout
                    val reader=FamilyVoice(applicationContext,session.userId,true).also{localReader=it;voice=it;it.active=true}
                    if(!reader.allowed(true)) return@withTimeout
                    // Fetch through recipient RLS with current session, not administrator credentials.
                    val notice=SupabaseService().notices(session).firstOrNull{it.id==id && it.kind=="message" && !it.read && recentFamilyFix(it.createdAt)}?:return@withTimeout
                    while(!reader.isReady){if(SessionVault.current()?.userId!=user || !reader.allowed(true))return@withTimeout;delay(100)}
                    if(SessionVault.current()?.userId!=user || !reader.speak(notice.text,true,notice.id))return@withTimeout
                    while(reader.isSpeaking){
                        if(SessionVault.current()?.userId!=user || !reader.allowed(true)){reader.stop();break}
                        delay(100)
                    }
                }
            } catch(_:Exception) {
                // Normal generic notification remains available; never log private message/session.
            } finally {localReader?.close();if(voice===localReader)voice=null;stopSelf(startId)}
        }
        return START_NOT_STICKY
    }
    override fun onDestroy(){job?.cancel();scope.cancel();voice?.close();voice=null;unregisterReceiver(screenOff);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
