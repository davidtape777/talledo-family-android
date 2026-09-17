package com.talledofamily.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TalledoMessagingService:FirebaseMessagingService() {
    override fun onNewToken(token:String) {
        SessionVault.initialize(applicationContext)
        SessionVault.current()?.let{FamilyPush.registerToken(applicationContext,it,token)}
    }
    override fun onMessageReceived(message:RemoteMessage) {
        SessionVault.initialize(applicationContext)
        val session=SessionVault.current()?:return
        if(message.data["user_id"]!=session.userId) return
        val id=message.data["notification_id"]?:return
        if(!runCatching{java.util.UUID.fromString(id)}.isSuccess) return
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("family_alerts","Avisos familiares",NotificationManager.IMPORTANCE_HIGH))
        val open=PendingIntent.getActivity(this,0,Intent(this,RealMainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(this,"family_alerts").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TALLEDO FAMILY").setContentText("Tienes una novedad familiar. Abre la app para consultarla.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build()
        // Same outbox ID replaces duplicates; never speak or expose GPS/message text on lock screen.
        manager.notify(id.hashCode(),notification)
    }
}
