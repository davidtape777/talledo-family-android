package com.talledofamily.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.time.Instant
import java.time.Duration

fun inQuietHours(hour:Int,start:Int,end:Int):Boolean = if(start==end) false else if(start<end) hour in start until end else hour>=start || hour<end
fun recentFamilyFix(captured:String,now:Instant=Instant.now()):Boolean = runCatching {Duration.between(Instant.parse(captured),now).seconds in 0..119}.getOrDefault(false)

// TTS stays foreground-only. No microphones, cloud voice service or background speech.
class FamilyVoice(private val context:Context) {
    private val preferences=context.getSharedPreferences("family_voice",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=preferences.getBoolean("enabled",false)
        set(value){preferences.edit().putBoolean("enabled",value).apply();if(!value) stop()}
    var readMessages:Boolean
        get()=preferences.getBoolean("messages",false)
        set(value){preferences.edit().putBoolean("messages",value).apply()}
    var quiet:Boolean
        get()=preferences.getBoolean("quiet",true)
        set(value){preferences.edit().putBoolean("quiet",value).apply()}
    var volume:Float
        get()=preferences.getFloat("volume",.7f)
        set(value){preferences.edit().putFloat("volume",value.coerceIn(.1f,1f)).apply()}
    var active=false
    var status="Preparando voz española instalada…"
        private set
    private var ready=false
    private var disposed=false
    private var tts:TextToSpeech?=null
    init {
        tts=TextToSpeech(context) { result ->
            if(!disposed && result==TextToSpeech.SUCCESS) {
                val engine=tts
                val voice=engine?.voices?.firstOrNull{it.locale.language=="es" && !it.isNetworkConnectionRequired}
                if(voice!=null){engine.voice=voice;ready=true;status="Voz española local lista"}
                else {status="Instala una voz española sin conexión en Ajustes de Android"}
                engine?.setSpeechRate(.95f)
                engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            } else if(!disposed) status="Voz no disponible en este dispositivo"
        }
    }
    fun speak(text:String,message:Boolean=false) {
        if(!ready || !active || !enabled || (message && !readMessages)) return
        if(quiet && inQuietHours(java.time.LocalTime.now().hour,22,7)) return
        if(context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        if(context.getSystemService(AudioManager::class.java).ringerMode!=AudioManager.RINGER_MODE_NORMAL) return
        if(context.getSystemService(NotificationManager::class.java).currentInterruptionFilter!=NotificationManager.INTERRUPTION_FILTER_ALL) return
        tts?.speak(text.take(1000),TextToSpeech.QUEUE_FLUSH,Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume)},java.util.UUID.randomUUID().toString())
    }
    fun stop(){tts?.stop()}
    fun close(){disposed=true;active=false;tts?.stop();tts?.shutdown();tts=null}
}
