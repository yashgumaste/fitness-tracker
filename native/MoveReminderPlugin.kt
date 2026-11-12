package com.yashgumaste.fitnesstracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Move reminders: every ~15 minutes (even with the app closed) a background job reads the phone's
 * step counter. If you've taken fewer than [MoveCheck.MIN_STEPS] steps over the chosen sitting time,
 * inside your chosen hours, it sends one "time for a short walk" notification.
 */
@CapacitorPlugin(
    name = "MoveReminder",
    permissions = [Permission(alias = "activity", strings = [Manifest.permission.ACTIVITY_RECOGNITION])]
)
class MoveReminderPlugin : Plugin() {

    @PluginMethod
    fun configure(call: PluginCall) {
        val on = call.getBoolean("on", false) ?: false
        if (on && Build.VERSION.SDK_INT >= 29 && getPermissionState("activity") != PermissionState.GRANTED) {
            requestPermissionForAlias("activity", call, "afterPermission"); return
        }
        apply(call, on)
    }

    @PermissionCallback
    private fun afterPermission(call: PluginCall) {
        if (getPermissionState("activity") == PermissionState.GRANTED) apply(call, true)
        else { MoveCheck.save(context, false, call); MoveCheck.schedule(context, false); resolve(call, false, "denied") }
    }

    private fun apply(call: PluginCall, on: Boolean) {
        val sensor = (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (on && sensor == null) { MoveCheck.save(context, false, call); MoveCheck.schedule(context, false); resolve(call, false, "nosensor"); return }
        MoveCheck.save(context, on, call)
        MoveCheck.schedule(context, on)
        resolve(call, on, null)
    }

    private fun resolve(call: PluginCall, on: Boolean, reason: String?) {
        val r = JSObject(); r.put("on", on); if (reason != null) r.put("reason", reason); call.resolve(r)
    }

    @PluginMethod
    fun test(call: PluginCall) {
        val ok = MoveCheck.notify(context, 60, true)
        val r = JSObject(); r.put("ok", ok); call.resolve(r)
    }
}

object MoveCheck {
    const val MIN_STEPS = 60
    private const val PREFS = "move_reminder"
    private const val WORK = "move-check"
    private const val CHANNEL = "move"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(c: Context, on: Boolean, call: PluginCall) {
        prefs(c).edit()
            .putBoolean("on", on)
            .putInt("mins", call.getInt("mins", 60) ?: 60)
            .putInt("from", call.getInt("from", 9 * 60) ?: (9 * 60))
            .putInt("to", call.getInt("to", 21 * 60) ?: (21 * 60))
            .apply()
    }

    fun schedule(c: Context, on: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<MoveWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** One check: record the step count, and remind if there has been too little movement. */
    suspend fun check(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val steps = readCounter(c)
        val now = System.currentTimeMillis()
        val mins = p.getInt("mins", 60)
        // samples: [[time, count], ...] for the last few hours
        var samples = try { JSONArray(p.getString("samples", "[]")) } catch (e: Exception) { JSONArray() }
        val last = if (samples.length() > 0) samples.getJSONArray(samples.length() - 1) else null
        val count: Long = steps ?: last?.getLong(1) ?: return
        if (last != null && count < last.getLong(1)) samples = JSONArray()   // phone restarted: counter reset
        samples.put(JSONArray().put(now).put(count))
        val keep = JSONArray()
        for (i in 0 until samples.length()) { val s = samples.getJSONArray(i); if (now - s.getLong(0) <= 4 * 3600_000L) keep.put(s) }
        p.edit().putString("samples", keep.toString()).apply()

        // inside the chosen hours?
        val cal = Calendar.getInstance()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val from = p.getInt("from", 9 * 60); val to = p.getInt("to", 21 * 60)
        val inHours = if (from <= to) minuteOfDay in from until to else (minuteOfDay >= from || minuteOfDay < to)
        if (!inHours) return
        // steps over the sitting window, using the newest sample that is at least that old
        val windowStart = now - mins * 60_000L
        var base: JSONArray? = null
        for (i in 0 until keep.length()) { val s = keep.getJSONArray(i); if (s.getLong(0) <= windowStart + 5 * 60_000L) base = s }
        if (base == null) return                                   // not watched long enough yet
        if (now - p.getLong("lastAlert", 0) < mins * 60_000L) return  // at most one reminder per sitting
        if (count - base.getLong(1) < MIN_STEPS) {
            if (notify(c, mins, false)) p.edit().putLong("lastAlert", now).apply()
        }
    }

    private suspend fun readCounter(c: Context): Long? {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return null
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<Long> { cont ->
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0].toLong())
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    fun notify(c: Context, mins: Int, test: Boolean): Boolean {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Move reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A nudge to walk when you've been still for a while" })
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = if (open != null) PendingIntent.getActivity(c, 7100, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        val sat = if (mins >= 60 && mins % 60 == 0) "${mins / 60} hour${if (mins > 60) "s" else ""}" else "$mins minutes"
        val icon = c.resources.getIdentifier("ic_stat_walk", "drawable", c.packageName).takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(if (test) "Test: time to move 🚶" else "Time to move 🚶")
            .setContentText("You've hardly moved in $sat. A 5-minute walk helps.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        return try { NotificationManagerCompat.from(c).notify(7101, n); true } catch (e: SecurityException) { false }
    }
}

class MoveWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        try { MoveCheck.check(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}
