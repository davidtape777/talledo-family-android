package com.talledofamily.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UserSession(val accessToken: String, val refreshToken: String, val userId: String, val expiresAt: Long = 0)
data class FamilyMember(val id: String, val familyId: String, val authUserId: String?, val name: String, val relationship: String, val role: String, val avatarUrl: String?, val birthDate: String? = null, val phone: String? = null, val privacyPermitted: Boolean = true)
data class FamilyInfo(val id: String, val name: String, val joinCode: String, val photoUrl: String?)
data class SharedLocation(val memberId:String,val sharing:Boolean,val latitude:Double,val longitude:Double,val accuracy:Double,val capturedAt:String)
data class RemoteFamilyMessage(val id:String,val senderId:String,val body:String,val createdAt:String)
class SupabaseException(message: String) : Exception(message)

class SupabaseService {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    suspend fun signUp(email: String, password: String): UserSession? = withContext(Dispatchers.IO) {
        sessionFrom(JSONObject(request("/auth/v1/signup", "POST", null, JSONObject().put("email", email.trim()).put("password", password))))?.also { SessionVault.save(it) }
    }

    suspend fun signIn(email: String, password: String): UserSession = withContext(Dispatchers.IO) {
        sessionFrom(JSONObject(request("/auth/v1/token?grant_type=password", "POST", null, JSONObject().put("email", email.trim()).put("password", password))))
            ?.also { SessionVault.save(it) } ?: throw SupabaseException("No se recibió una sesión válida")
    }

    suspend fun myMember(session: UserSession): FamilyMember? = withContext(Dispatchers.IO) {
        val rows = JSONArray(request("/rest/v1/family_members?auth_user_id=eq.${session.userId}&select=*", token = session.accessToken))
        if (rows.length() == 0) null else memberFrom(rows.getJSONObject(0))
    }

    suspend fun family(session: UserSession, id: String): FamilyInfo = withContext(Dispatchers.IO) {
        val rows = JSONArray(request("/rest/v1/families?id=eq.$id&select=id,name,join_code,photo_url", token = session.accessToken))
        if (rows.length() == 0) throw SupabaseException("No se encontró la familia")
        familyFrom(rows.getJSONObject(0))
    }

    suspend fun members(session: UserSession, familyId: String): List<FamilyMember> = withContext(Dispatchers.IO) {
        val rows = JSONArray(request("/rest/v1/rpc/list_family_members", "POST", session.accessToken, JSONObject().put("fid",familyId)))
        List(rows.length()) { memberFrom(rows.getJSONObject(it)) }
    }

    suspend fun createFamily(session: UserSession, familyName: String, personName: String): String = withContext(Dispatchers.IO) {
        val rows = JSONArray(request("/rest/v1/rpc/create_family", "POST", session.accessToken,
            JSONObject().put("family_name", familyName).put("creator_name", personName)))
        rows.getJSONObject(0).getString("family_id")
    }

    suspend fun joinFamily(session: UserSession, code: String, personName: String, relationship: String) = withContext(Dispatchers.IO) {
        val response = request("/rest/v1/rpc/join_family", "POST", session.accessToken, JSONObject()
            .put("code", code).put("member_name", personName).put("member_relationship", relationship))
        if (response.trim() == "null") throw SupabaseException("Código inválido o demasiados intentos. Verifica el código; espera 15 minutos si hiciste varios intentos.")
    }

    suspend fun updateMember(session: UserSession, memberId: String, name: String, relationship: String, birthDate: String? = null, phone: String? = null, avatar: String? = null) = withContext(Dispatchers.IO) {
        request("/rest/v1/family_members?id=eq.$memberId", "PATCH", session.accessToken,
            JSONObject().put("display_name", name).put("relationship", relationship)
                .put("birth_date", birthDate ?: JSONObject.NULL).put("phone", phone ?: JSONObject.NULL).put("avatar_url", avatar ?: JSONObject.NULL))
    }

    suspend fun addProfile(session: UserSession, familyId: String, name: String, relationship: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/family_members", "POST", session.accessToken, JSONObject()
            .put("family_id", familyId).put("display_name", name).put("relationship", relationship)
            .put("role", "member"))
    }

    suspend fun updateFamily(session: UserSession, familyId: String, name: String, photoUrl: String?) = withContext(Dispatchers.IO) {
        request("/rest/v1/families?id=eq.$familyId", "PATCH", session.accessToken,
            JSONObject().put("name", name).put("photo_url", photoUrl ?: JSONObject.NULL))
    }

    suspend fun setVisibility(session: UserSession, familyId: String, viewerId: String, targetId: String, mode: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/visibility_preferences?on_conflict=viewer_member_id,target_member_id", "POST", session.accessToken,
            JSONObject().put("family_id", familyId).put("viewer_member_id", viewerId).put("target_member_id", targetId).put("mode", mode),
            prefer = "resolution=merge-duplicates")
    }

    suspend fun sendEvent(session: UserSession, familyId: String, senderId: String, type: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/family_events", "POST", session.accessToken, JSONObject()
            .put("family_id", familyId).put("sender_member_id", senderId).put("event_type", type).put("demo", true)
            .put("message", if (type == "need_me") "Necesito acompañamiento (prueba)" else "Toque familiar"))
    }


    suspend fun approveGuardian(session: UserSession, targetId: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/approve_guardian", "POST", session.accessToken, JSONObject().put("target_id", targetId))
    }
    suspend fun visibility(session: UserSession, me: String): Map<String,String> = withContext(Dispatchers.IO) {
        val rows=JSONArray(request("/rest/v1/visibility_preferences?viewer_member_id=eq.$me&select=target_member_id,mode", token=session.accessToken))
        (0 until rows.length()).associate { rows.getJSONObject(it).getString("target_member_id") to rows.getJSONObject(it).getString("mode") }
    }
    suspend fun locations(session: UserSession, familyId: String): List<SharedLocation> = withContext(Dispatchers.IO) {
        val rows=JSONArray(request("/rest/v1/family_locations?family_id=eq.$familyId&select=*",token=session.accessToken))
        List(rows.length()) { val o=rows.getJSONObject(it); SharedLocation(o.getString("member_id"),o.optBoolean("sharing"),o.optDouble("latitude"),o.optDouble("longitude"),o.optDouble("accuracy_m"),o.optString("captured_at")) }
    }
    suspend fun publishLocation(session: UserSession, location: android.location.Location) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/publish_location","POST",session.accessToken,JSONObject()
            .put("lat",location.latitude).put("lng",location.longitude).put("accuracy",location.accuracy.toDouble())
            .put("captured",java.time.Instant.ofEpochMilli(location.time).toString()))
    }
    suspend fun pauseLocation(session: UserSession) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/pause_location","POST",session.accessToken,JSONObject())
    }
    suspend fun messages(session: UserSession, familyId: String, recipient: String?, me: String): List<RemoteFamilyMessage> = withContext(Dispatchers.IO) {
        val filter=if(recipient==null) "&recipient_member_id=is.null" else "&or=(and(sender_member_id.eq.$me,recipient_member_id.eq.$recipient),and(sender_member_id.eq.$recipient,recipient_member_id.eq.$me))"
        val rows=JSONArray(request("/rest/v1/family_messages?family_id=eq.$familyId$filter&select=*&order=created_at.desc&limit=100",token=session.accessToken))
        List(rows.length()){ val o=rows.getJSONObject(it); RemoteFamilyMessage(o.getString("id"),o.getString("sender_member_id"),o.getString("body"),o.getString("created_at")) }.reversed()
    }
    suspend fun sendMessage(session: UserSession, familyId: String, me: String, recipient: String?, body: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/family_messages","POST",session.accessToken,JSONObject().put("family_id",familyId).put("sender_member_id",me).put("recipient_member_id",recipient?:JSONObject.NULL).put("body",body.trim()))
    }
    suspend fun uploadPhoto(session: UserSession,familyId:String,bytes:ByteArray):String = withContext(Dispatchers.IO) {
        val path="$familyId/${session.userId}/${java.util.UUID.randomUUID()}.jpg"
        val c=URL(base+"/storage/v1/object/family-avatars/"+path).openConnection() as HttpURLConnection
        c.requestMethod="POST"; c.connectTimeout=15000; c.readTimeout=20000; c.doOutput=true
        c.setRequestProperty("apikey",key); c.setRequestProperty("Authorization","Bearer "+SessionVault.token(session.accessToken)); c.setRequestProperty("Content-Type","image/jpeg")
        try { c.outputStream.use{it.write(bytes)}; val status=c.responseCode
            if(status !in 200..299) throw SupabaseException("No se pudo subir la imagen ($status)")
        } finally { c.disconnect() }
        path
    }
    suspend fun photoUrl(session:UserSession,path:String):String = withContext(Dispatchers.IO) {
        require(!path.startsWith("http")) { "Usa fotos privadas de la aplicación" }
        val json=JSONObject(request("/storage/v1/object/sign/family-avatars/"+path,"POST",session.accessToken,JSONObject().put("expiresIn",300)))
        base+"/storage/v1"+json.getString("signedURL")
    }

    private fun sessionFrom(json: JSONObject): UserSession? {
        val token = json.optString("access_token")
        if (token.isBlank()) return null
        val user = json.optJSONObject("user") ?: return null
        return UserSession(token, json.optString("refresh_token"), user.getString("id"), json.optLong("expires_at", System.currentTimeMillis()/1000 + json.optLong("expires_in", 3600)))
    }

    private fun memberFrom(o: JSONObject) = FamilyMember(
        o.getString("id"), o.getString("family_id"), o.optString("auth_user_id").takeIf { it.isNotBlank() && it != "null" },
        o.getString("display_name"), o.getString("relationship"), o.getString("role"),
        o.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
        o.optString("birth_date").takeIf { it.isNotBlank() && it != "null" },
        o.optString("phone").takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("privacy_permitted",true)
    )

    private fun familyFrom(o: JSONObject) = FamilyInfo(
        o.getString("id"), o.getString("name"), o.getString("join_code"),
        o.optString("photo_url").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun request(path: String, method: String = "GET", token: String? = null, body: JSONObject? = null, prefer: String? = null): String {
        val actualToken = token?.let { SessionVault.token(it) }
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("apikey", key)
        connection.setRequestProperty("Authorization", "Bearer ${actualToken ?: key}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        prefer?.let { connection.setRequestProperty("Prefer", it) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val message = runCatching {
                val error = JSONObject(response)
                error.optString("msg").ifBlank { error.optString("message").ifBlank { error.optString("error_description") } }
            }.getOrDefault(response)
            throw SupabaseException(message.ifBlank { "Error de conexión ($status)" })
        }
        return response
    }
}
