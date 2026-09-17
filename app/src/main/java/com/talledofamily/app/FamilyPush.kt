package com.talledofamily.app

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

object FamilyPush {
    private val scope=CoroutineScope(Dispatchers.IO)
    val registrationStatus=MutableStateFlow("Registro de avisos pendiente")
    fun deviceId(context:Context):String {
        val prefs=context.getSharedPreferences("family_push",Context.MODE_PRIVATE)
        return prefs.getString("device",null)?:java.util.UUID.randomUUID().toString().also{prefs.edit().putString("device",it).apply()}
    }
    fun register(context:Context,session:UserSession) {
        registrationStatus.value="Registrando este dispositivo…"
        FirebaseMessaging.getInstance().token.addOnSuccessListener{token->registerToken(context,session,token)}
            .addOnFailureListener{registrationStatus.value="No se pudo obtener el registro Firebase. Revisa Internet y Google Play Services."}
    }
    fun registerToken(context:Context,session:UserSession,token:String) {
        scope.launch {LocationSharing.mutex.lock();try{
            if(SessionVault.current()?.userId==session.userId) runCatching{SupabaseService().registerPush(session,deviceId(context),token)}
                .onSuccess{registrationStatus.value="Dispositivo registrado. La entrega externa requiere función y webhook activos."}
                .onFailure{registrationStatus.value="No se pudo registrar: comprueba Internet y ejecuta SQL 004."}
        }finally{LocationSharing.mutex.unlock()}}
    }
    suspend fun unregister(context:Context,session:UserSession) = withContext(Dispatchers.IO) {SupabaseService().unregisterPush(session,deviceId(context));registrationStatus.value="Registro retirado al cerrar sesión"}
}
