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
import android.os.PowerManager
import android.app.KeyguardManager
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
 * step counter and notes what the phone is doing:
 *  - screen on and unlocked: you're using it, so almost certainly sitting with it (weight 1.0)
 *  - screen off but held or in a pocket (small movements, not lying flat): probably sitting (0.6)
 *  - lying flat and perfectly still with the screen off: you may be away from it, asleep or at a desk (0.15)
 * Any check with real steps since the last one (walking, carrying the phone to another room) restarts
 * the sitting time. It reminds only after the chosen sitting time, with fewer than [MoveCheck.MIN_STEPS]
 * steps in it, when the average weight says you were most likely sitting with the phone.
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
    const val MOVED_STEPS = 15        // this many steps between two checks counts as getting up
    const val SITTING_SCORE = 0.5
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

    /** How sure we are, from this moment, that you're sitting with the phone (see the class comment). */
    private suspend fun sittingWeight(c: Context): Double {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (pm.isInteractive && !km.isKeyguardLocked) return 1.0
        val m = readMotion(c) ?: return 0.4                      // no accelerometer reading: in between
        return if (m.first && m.second) 0.15 else 0.6           // flat and still → maybe away; else held/pocket
    }

    /** ~2.5 s of accelerometer: (lying flat, perfectly still). */
    private suspend fun readMotion(c: Context): Pair<Boolean, Boolean>? {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        return withTimeoutOrNull(4000) {
            suspendCancellableCoroutine<Pair<Boolean, Boolean>> { cont ->
                val t0 = System.nanoTime(); var n = 0; var sx = 0.0; var sy = 0.0; var sz = 0.0; var sm1 = 0.0; var sm2 = 0.0
                val l = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        val x = e.values[0].toDouble(); val y = e.values[1].toDouble(); val z = e.values[2].toDouble()
                        val mag = Math.sqrt(x * x + y * y + z * z)
                        n++; sx += x; sy += y; sz += z; sm1 += mag; sm2 += mag * mag
                        if (System.nanoTime() - t0 > 2_500_000_000L && n >= 10) {
                            sm.unregisterListener(this)
                            val g = Math.sqrt((sx / n) * (sx / n) + (sy / n) * (sy / n) + (sz / n) * (sz / n))
                            val flat = g > 0 && Math.abs(sz / n) / g > 0.9
                            val sd = Math.sqrt(Math.max(0.0, sm2 / n - (sm1 / n) * (sm1 / n)))
                            if (cont.isActive) cont.resume(Pair(flat, sd < 0.06))
                        }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
                cont.invokeOnCancellation { sm.unregisterListener(l) }
            }
        }
    }

    /** One check: record the step count and what the phone is doing, and remind if you've most likely
     *  been sitting with it for the chosen time. */
    suspend fun check(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val steps = readCounter(c)
        val now = System.currentTimeMillis()
        val mins = p.getInt("mins", 60)
        // samples: [[time, count, sitting weight], ...] for the last few hours
        var samples = try { JSONArray(p.getString("samples", "[]")) } catch (e: Exception) { JSONArray() }
        val last = if (samples.length() > 0) samples.getJSONArray(samples.length() - 1) else null
        val count: Long = steps ?: last?.getLong(1) ?: return
        if (last != null && count < last.getLong(1)) samples = JSONArray()   // phone restarted: counter reset
        // real steps since the last check (walking, or carrying the phone to another room): sitting starts again now
        if (last != null && count - last.getLong(1) >= MOVED_STEPS) p.edit().putLong("movedAt", now).apply()
        samples.put(JSONArray().put(now).put(count).put(sittingWeight(c)))
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
        if (now - p.getLong("movedAt", 0) < mins * 60_000L) return    // got up recently: not sitting long enough
        if (now - p.getLong("lastAlert", 0) < mins * 60_000L) return  // at most one reminder per sitting
        if (count - base.getLong(1) >= MIN_STEPS) return
        // how sure are we that you were sitting with the phone, over the window?
        var sum = 0.0; var n = 0; var used = 0
        for (i in 0 until keep.length()) { val s = keep.getJSONArray(i)
            if (s.getLong(0) >= windowStart - 60_000L) { val w = s.optDouble(2, 0.6); sum += w; n++; if (w >= 1.0) used++ } }
        if (n < 2 || sum / n < SITTING_SCORE) return              // phone mostly lying still: you may well be away from it
        if (notify(c, mins, false, used * 2 >= n)) p.edit().putLong("lastAlert", now).apply()
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

    fun notify(c: Context, mins: Int, test: Boolean, onPhone: Boolean = false): Boolean {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Move reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A nudge to walk when you've been still for a while" })
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = if (open != null) PendingIntent.getActivity(c, 7100, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        val sat = if (mins >= 60 && mins % 60 == 0) "${mins / 60} hour${if (mins > 60) "s" else ""}" else "$mins minutes"
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(Water.smallIcon(c)).setColor(0xFF5CF2A6.toInt())
            .setLargeIcon(Water.largeIcon(c, "ic_large_walk"))
            .setContentTitle(if (test) "Test: time to move 🚶" else "Time to move 🚶")
            .setContentText(if (onPhone) "You've been on your phone for about $sat without moving. A 5-minute walk helps." else "You've hardly moved in $sat. A 5-minute walk helps.")
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
