package com.talledofamily.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UserSession(val accessToken: String, val refreshToken: String, val userId: String)
data class FamilyMember(val id: String, val familyId: String, val authUserId: String?, val name: String, val relationship: String, val role: String, val avatarUrl: String?)
data class FamilyInfo(val id: String, val name: String, val joinCode: String, val photoUrl: String?)
class SupabaseException(message: String) : Exception(message)

class SupabaseService {
    private val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    suspend fun signUp(email: String, password: String): UserSession? = withContext(Dispatchers.IO) {
        sessionFrom(JSONObject(request("/auth/v1/signup", "POST", null, JSONObject().put("email", email.trim()).put("password", password))))
    }

    suspend fun signIn(email: String, password: String): UserSession = withContext(Dispatchers.IO) {
        sessionFrom(JSONObject(request("/auth/v1/token?grant_type=password", "POST", null, JSONObject().put("email", email.trim()).put("password", password))))
            ?: throw SupabaseException("No se recibió una sesión válida")
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
        val rows = JSONArray(request("/rest/v1/family_members?family_id=eq.$familyId&select=*&order=created_at.asc", token = session.accessToken))
        List(rows.length()) { memberFrom(rows.getJSONObject(it)) }
    }

    suspend fun createFamily(session: UserSession, familyName: String, personName: String): String = withContext(Dispatchers.IO) {
        val rows = JSONArray(request("/rest/v1/rpc/create_family", "POST", session.accessToken,
            JSONObject().put("family_name", familyName).put("creator_name", personName)))
        rows.getJSONObject(0).getString("family_id")
    }

    suspend fun joinFamily(session: UserSession, code: String, personName: String, relationship: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/rpc/join_family", "POST", session.accessToken, JSONObject()
            .put("code", code).put("member_name", personName).put("member_relationship", relationship))
    }

    suspend fun updateMember(session: UserSession, memberId: String, name: String, relationship: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/family_members?id=eq.$memberId", "PATCH", session.accessToken,
            JSONObject().put("display_name", name).put("relationship", relationship))
    }

    suspend fun addProfile(session: UserSession, familyId: String, name: String, relationship: String) = withContext(Dispatchers.IO) {
        request("/rest/v1/family_members", "POST", session.accessToken, JSONObject()
            .put("family_id", familyId).put("display_name", name).put("relationship", relationship)
            .put("role", if (relationship in listOf("padre", "madre", "tutor")) "adult" else "member"))
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

    private fun sessionFrom(json: JSONObject): UserSession? {
        val token = json.optString("access_token")
        if (token.isBlank()) return null
        val user = json.optJSONObject("user") ?: return null
        return UserSession(token, json.optString("refresh_token"), user.getString("id"))
    }

    private fun memberFrom(o: JSONObject) = FamilyMember(
        o.getString("id"), o.getString("family_id"), o.optString("auth_user_id").takeIf { it.isNotBlank() && it != "null" },
        o.getString("display_name"), o.getString("relationship"), o.getString("role"),
        o.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun familyFrom(o: JSONObject) = FamilyInfo(
        o.getString("id"), o.getString("name"), o.getString("join_code"),
        o.optString("photo_url").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun request(path: String, method: String = "GET", token: String? = null, body: JSONObject? = null, prefer: String? = null): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("apikey", key)
        connection.setRequestProperty("Authorization", "Bearer ${token ?: key}")
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
