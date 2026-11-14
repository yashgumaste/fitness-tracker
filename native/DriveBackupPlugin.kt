package com.yashgumaste.fitnesstracker

import android.accounts.Account
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Backups to the person's own Google Drive, in its hidden app-data folder (drive.appdata):
 * sign in with the Google account picker, back up now, automatic daily/weekly/monthly backups
 * (WorkManager, optionally Wi-Fi only), list backups and restore one. Keeps the newest 30.
 * A backup holds every file of the app's on-phone store (files/store/*.json).
 */
@CapacitorPlugin(name = "DriveBackup")
class DriveBackupPlugin : Plugin() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: PluginCall? = null

    override fun load() {
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val call = pending ?: return@registerForActivityResult
            pending = null
            try { finishSignIn(call, Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data)) }
            catch (e: Exception) { call.reject("Sign-in was cancelled", "CANCELLED") }
        }
    }

    @PluginMethod
    fun signIn(call: PluginCall) {
        Identity.getAuthorizationClient(activity).authorize(Drive.request(null))
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) { pending = call; launcher?.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
                else finishSignIn(call, r)
            }
            .addOnFailureListener { e -> call.reject(e.message ?: "Couldn't sign in", "FAILED") }
    }

    private fun finishSignIn(call: PluginCall, r: AuthorizationResult) {
        val token = r.accessToken ?: run { call.reject("Google didn't allow Drive access", "NO_TOKEN"); return }
        scope.launch {
            try {
                val email = withContext(Dispatchers.IO) { Drive.email(token) }
                Drive.prefs(context).edit().putString("email", email).remove("error").apply()
                call.resolve(Drive.status(context))
            } catch (e: Exception) { call.reject(e.message ?: "Couldn't reach Google Drive", "FAILED") }
        }
    }

    @PluginMethod
    fun signOut(call: PluginCall) {
        Drive.prefs(context).edit().clear().apply()
        WorkManager.getInstance(context).cancelUniqueWork(Drive.WORK)
        call.resolve(Drive.status(context))
    }

    @PluginMethod fun status(call: PluginCall) { call.resolve(Drive.status(context)) }

    @PluginMethod
    fun backupNow(call: PluginCall) {
        scope.launch {
            try { withContext(Dispatchers.IO) { Drive.backup(context) }; call.resolve(Drive.status(context)) }
            catch (e: Exception) { call.reject(e.message ?: "Backup failed", if (e is Drive.NeedsSignIn) "SIGN_IN" else "FAILED") }
        }
    }

    /** freq: off | daily | weekly | monthly; wifiOnly: back up only on Wi-Fi */
    @PluginMethod
    fun schedule(call: PluginCall) {
        val freq = call.getString("freq", "daily") ?: "daily"
        val wifi = call.getBoolean("wifiOnly", true) ?: true
        Drive.prefs(context).edit().putString("freq", freq).putBoolean("wifiOnly", wifi).apply()
        Drive.schedule(context)
        call.resolve(Drive.status(context))
    }

    @PluginMethod
    fun list(call: PluginCall) {
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) { Drive.list(Drive.token(context)) }
                val a = JSArray(); files.forEach { a.put(it) }
                val r = JSObject(); r.put("files", a); call.resolve(r)
            } catch (e: Exception) { call.reject(e.message ?: "Couldn't list backups", if (e is Drive.NeedsSignIn) "SIGN_IN" else "FAILED") }
        }
    }

    /** Replaces the app's data with backup `id`. The current data is kept in files/store-before-restore. */
    @PluginMethod
    fun restore(call: PluginCall) {
        val id = call.getString("id") ?: run { call.reject("No backup chosen"); return }
        scope.launch {
            try { val n = withContext(Dispatchers.IO) { Drive.restore(context, id) }; val r = JSObject(); r.put("files", n); call.resolve(r) }
            catch (e: Exception) { call.reject(e.message ?: "Restore failed", if (e is Drive.NeedsSignIn) "SIGN_IN" else "FAILED") }
        }
    }
}

object Drive {
    const val WORK = "drive-backup"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val KEEP = 30
    class NeedsSignIn : Exception("Sign in to Google again to keep backing up")

    fun prefs(c: Context) = c.getSharedPreferences("drive_backup", Context.MODE_PRIVATE)

    fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (email != null) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    /** A fresh access token without any screen (works in the background once you've signed in). */
    fun token(c: Context): String {
        val email = prefs(c).getString("email", null) ?: throw NeedsSignIn()
        val r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request(email)), 60, TimeUnit.SECONDS)
        if (r.hasResolution()) throw NeedsSignIn()
        return r.accessToken ?: throw NeedsSignIn()
    }

    fun status(c: Context): JSObject {
        val p = prefs(c); val o = JSObject()
        o.put("email", p.getString("email", null)); o.put("freq", p.getString("freq", "daily")); o.put("wifiOnly", p.getBoolean("wifiOnly", true))
        o.put("last", p.getLong("last", 0)); o.put("lastSize", p.getLong("lastSize", 0)); o.put("error", p.getString("error", null))
        return o
    }

    fun schedule(c: Context) {
        val p = prefs(c); val wm = WorkManager.getInstance(c)
        val days = when (p.getString("freq", "daily")) { "daily" -> 1L; "weekly" -> 7L; "monthly" -> 30L; else -> 0L }
        if (days == 0L || p.getString("email", null) == null) { wm.cancelUniqueWork(WORK); return }
        val net = if (p.getBoolean("wifiOnly", true)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val req = PeriodicWorkRequestBuilder<DriveBackupWorker>(days, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresBatteryNotLow(true).build()).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun http(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null): Pair<Int, ByteArray> {
        val h = URL(url).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 20000; h.readTimeout = 60000
        h.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { h.doOutput = true; h.setRequestProperty("Content-Type", type); h.outputStream.use { it.write(body) } }
        val code = h.responseCode
        val bytes = (if (code in 200..299) h.inputStream else h.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        h.disconnect()
        if (code == 401 || code == 403) throw NeedsSignIn()
        if (code !in 200..299) throw Exception("Google Drive error $code")
        return code to bytes
    }

    fun email(token: String): String {
        val (_, b) = http("GET", "$API/about?fields=user(emailAddress)", token)
        return JSONObject(String(b)).getJSONObject("user").optString("emailAddress", "your Google account")
    }

    fun list(token: String): List<JSONObject> {
        val q = "spaces=appDataFolder&orderBy=createdTime%20desc&pageSize=100&fields=" + URLEncoder.encode("files(id,name,size,createdTime)", "UTF-8")
        val (_, b) = http("GET", "$API/files?$q", token)
        val arr = JSONObject(String(b)).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** Upload every store file as one JSON backup, then keep only the newest 30. */
    fun backup(c: Context) {
        val p = prefs(c)
        try {
            val token = token(c)
            val dir = File(c.filesDir, "store"); val files = JSONObject()
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { files.put(it.name, it.readText()) }
            if (files.length() == 0) throw Exception("Nothing to back up yet")
            val doc = JSONObject().put("format", "lm-store-1").put("app", c.packageName)
                .put("createdAt", System.currentTimeMillis()).put("files", files).toString().toByteArray()
            val name = "fitness-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"
            val boundary = "lmb" + System.nanoTime()
            val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).put("mimeType", "application/json").toString()
            val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray() +
                doc + "\r\n--$boundary--\r\n".toByteArray()
            http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token, body, "multipart/related; boundary=$boundary")
            list(token).drop(KEEP).forEach { try { http("DELETE", "$API/files/" + it.getString("id"), token) } catch (e: Exception) { } }
            p.edit().putLong("last", System.currentTimeMillis()).putLong("lastSize", doc.size.toLong()).remove("error").apply()
        } catch (e: Exception) {
            p.edit().putString("error", e.message ?: "Backup failed").apply(); throw e
        }
    }

    fun restore(c: Context, id: String): Int {
        val token = token(c)
        val (_, b) = http("GET", "$API/files/" + URLEncoder.encode(id, "UTF-8") + "?alt=media", token)
        val doc = JSONObject(String(b))
        if (doc.optString("format") != "lm-store-1") throw Exception("That file isn't a Fitness Tracker backup")
        val files = doc.getJSONObject("files")
        val dir = File(c.filesDir, "store"); dir.mkdirs()
        val keep = File(c.filesDir, "store-before-restore"); keep.deleteRecursively(); keep.mkdirs()
        dir.listFiles()?.forEach { it.copyTo(File(keep, it.name), true) }
        var n = 0
        files.keys().forEach { k -> if (Regex("^[A-Za-z0-9._-]+\\.json$").matches(k)) { File(dir, k).writeText(files.getString(k)); n++ } }
        return n
    }
}

class DriveBackupWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) { Drive.backup(applicationContext) }; Result.success()
    } catch (e: Drive.NeedsSignIn) { Result.success() }      // shown in the app; nothing to retry until you sign in
    catch (e: Exception) { if (runAttemptCount < 3) Result.retry() else Result.success() }
}
