package app.piggybank.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The app's only network code (checked by the build): Google Drive REST v3, limited to the hidden
 * `appDataFolder` that only PiggyBank can see. Nothing else in the user's Drive is reachable.
 */
class DriveClient(private val token: String) {
    data class File(val id: String, val name: String, val size: Long)

    fun email(): String? = runCatching {
        JSONObject(get("$API/about?fields=user(emailAddress)").decodeToString()).getJSONObject("user").getString("emailAddress")
    }.getOrNull()

    fun list(): List<File> {
        val q = URLEncoder.encode("files(id,name,size)", "UTF-8")
        val json = JSONObject(get("$API/files?spaces=appDataFolder&pageSize=200&fields=$q").decodeToString())
        val arr = json.getJSONArray("files")
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            File(f.getString("id"), f.getString("name"), f.optString("size", "0").toLongOrNull() ?: 0)
        }
    }

    /** Creates [name] in appDataFolder, or replaces its contents if it's already there (one file per day). */
    fun upload(name: String, bytes: ByteArray): String {
        list().firstOrNull { it.name == name }?.let { existing ->
            request("PATCH", "$UPLOAD/files/${existing.id}?uploadType=media", bytes, "application/octet-stream")
            return existing.id
        }
        val boundary = "pbk${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val res = request("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
        return JSONObject(res.decodeToString()).getString("id")
    }

    fun download(id: String): ByteArray = get("$API/files/$id?alt=media")
    fun delete(id: String) { request("DELETE", "$API/files/$id", null, null) }

    private fun get(url: String) = request("GET", url, null, null)

    private fun request(method: String, url: String, body: ByteArray?, type: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Drive accepts the override header.
            c.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                throw DriveException(code, Regex("\"message\":\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1) ?: "HTTP $code")
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    class DriveException(val code: Int, message: String) : Exception(message)

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        fun request(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()

        /** Off the main thread: a token without any UI, or null when the user has to sign in again. */
        fun silentToken(context: Context, email: String?): String? {
            val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(context).authorize(request(email)))
            return if (r.hasResolution()) null else r.accessToken
        }
    }
}
