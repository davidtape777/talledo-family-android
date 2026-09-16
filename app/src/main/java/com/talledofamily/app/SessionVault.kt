package com.talledofamily.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.HttpURLConnection
import java.net.URL

/** Tokens cifrados con Android Keystore; nunca guarda contraseñas. */
object SessionVault {
    private lateinit var prefs: android.content.SharedPreferences
    private var session: UserSession? = null
    private val lock=Any()
    fun initialize(context:Context) = synchronized(lock) {
        prefs=context.applicationContext.getSharedPreferences("tf_secure_session",Context.MODE_PRIVATE)
        LocationSharing.pendingPause=prefs.getBoolean("pending_pause",false)
        session=runCatching {
            val encoded=prefs.getString("session",null) ?: return@runCatching null
            val parts=encoded.split(".")
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
            val o=JSONObject(String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
            UserSession(o.getString("access"),o.getString("refresh"),o.getString("user"),o.getLong("expires"))
        }.getOrNull()
    }
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey("tf_session_key",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("tf_session_key",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun markPausePending(value:Boolean) { prefs.edit().putBoolean("pending_pause",value).commit() }
    fun current():UserSession?=synchronized(lock){session}
    fun save(value:UserSession)=synchronized(lock) {
        val o=JSONObject().put("access",value.accessToken).put("refresh",value.refreshToken).put("user",value.userId).put("expires",value.expiresAt)
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key())
        prefs.edit().putString("session",Base64.encodeToString(c.iv,Base64.NO_WRAP)+"."+Base64.encodeToString(c.doFinal(o.toString().toByteArray()),Base64.NO_WRAP)).commit()
        session=value
    }
    fun clear()=synchronized(lock){ session=null; prefs.edit().remove("session").commit(); Unit }
    /** Refresh serializado para no reutilizar refresh tokens desde UI y servicio. Ejecutar en IO. */
    fun token(fallback:String):String=synchronized(lock) {
        val old=session ?: return@synchronized fallback
        if(old.expiresAt>System.currentTimeMillis()/1000+60) return@synchronized old.accessToken
        val c=URL(BuildConfig.SUPABASE_URL+"/auth/v1/token?grant_type=refresh_token").openConnection() as HttpURLConnection
        c.requestMethod="POST";c.connectTimeout=15000;c.readTimeout=20000;c.doOutput=true
        c.setRequestProperty("apikey",BuildConfig.SUPABASE_PUBLISHABLE_KEY);c.setRequestProperty("Content-Type","application/json")
        try {
            c.outputStream.use{it.write(JSONObject().put("refresh_token",old.refreshToken).toString().toByteArray())}
            if(c.responseCode !in 200..299) throw SupabaseException("No se pudo renovar la sesión. Reintenta o vuelve a iniciar sesión.")
            val o=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            val updated=UserSession(o.getString("access_token"),o.getString("refresh_token"),old.userId,o.optLong("expires_at",System.currentTimeMillis()/1000+o.optLong("expires_in",3600)))
            save(updated);updated.accessToken
        } finally {c.disconnect()}
    }
}
