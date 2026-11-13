package com.yashgumaste.fitnesstracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Water reminders, posted by the app itself (not the notifications plugin) so that:
 *  - the "250 ml" / "500 ml" buttons log the drink WITHOUT opening the app, and the notification
 *    changes to "✓ 250 ml logged · 1.75 L of 3 L today";
 *  - tapping the notification itself opens the app and asks how much you drank.
 * Drinks logged from a button wait in a small queue until the app next opens (takeLogged()).
 */
@CapacitorPlugin(name = "WaterReminder")
class WaterReminderPlugin : Plugin() {
    private var openPending = false

    override fun load() { check(activity?.intent) }

    override fun handleOnNewIntent(intent: Intent) { super.handleOnNewIntent(intent); check(intent) }

    private fun check(i: Intent?) {
        if (i?.getBooleanExtra(Water.EXTRA_PICK, false) == true) {
            i.removeExtra(Water.EXTRA_PICK)
            openPending = true
            notifyListeners("pick", JSObject(), true)
        }
    }

    /** times: epoch ms to remind at; goal/todayMl/date: for the notification text; gapMin: skip if you drank this recently */
    @PluginMethod
    fun schedule(call: PluginCall) {
        val times = call.getArray("times") ?: JSArray()
        val list = ArrayList<Long>()
        for (i in 0 until times.length()) list.add(times.getLong(i))
        val p = Water.prefs(context).edit()
            .putBoolean("on", true)
            .putInt("goal", call.getInt("goal", 3000) ?: 3000)
            .putInt("todayMl", call.getInt("todayMl", 0) ?: 0)
            .putString("date", call.getString("date", Water.today()))
            .putInt("gapMin", call.getInt("gapMin", 45) ?: 45)
        call.getDouble("lastDrink")?.toLong()?.let { if (it > 0) p.putLong("lastDrink", it) }
        p.apply()
        Water.setAlarms(context, list)
        val r = JSObject(); r.put("count", list.size); call.resolve(r)
    }

    @PluginMethod
    fun cancelAll(call: PluginCall) {
        Water.prefs(context).edit().putBoolean("on", false).apply()
        Water.setAlarms(context, emptyList())
        NotificationManagerCompat.from(context).cancel(Water.NOTE_ID)
        call.resolve()
    }

    /** Drinks logged from notification buttons since the app last asked; clears the queue. */
    @PluginMethod
    fun takeLogged(call: PluginCall) {
        val q = Water.takeQueue(context)
        val r = JSObject(); r.put("drinks", JSArray(q.toString())); call.resolve(r)
    }

    /** True once if the app was opened by tapping a reminder (ask how much). */
    @PluginMethod
    fun takePick(call: PluginCall) {
        val r = JSObject(); r.put("pick", openPending); openPending = false; call.resolve(r)
    }

    @PluginMethod
    fun test(call: PluginCall) {
        val ok = Water.post(context, true)
        val r = JSObject(); r.put("ok", ok); call.resolve(r)
    }
}

object Water {
    const val NOTE_ID = 7300
    const val EXTRA_PICK = "lm_water_pick"
    private const val CHANNEL = "water"
    private const val ALARM_BASE = 8000

    fun prefs(c: Context) = c.getSharedPreferences("water_reminder", Context.MODE_PRIVATE)
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun setAlarms(c: Context, times: List<Long>) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = prefs(c)
        val old = p.getInt("alarmCount", 0)
        for (i in 0 until maxOf(old, 70)) {
            val pi = PendingIntent.getBroadcast(c, ALARM_BASE + i, Intent(c, WaterAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        val now = System.currentTimeMillis()
        val future = times.filter { it > now }.sorted().take(70)
        future.forEachIndexed { i, at ->
            val pi = PendingIntent.getBroadcast(c, ALARM_BASE + i, Intent(c, WaterAlarmReceiver::class.java).putExtra("at", at),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        }
        p.edit().putInt("alarmCount", future.size).putString("times", JSONArray(future).toString()).apply()
    }

    /** After a restart or an app update, put the remaining alarms back. */
    fun reschedule(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val arr = try { JSONArray(p.getString("times", "[]")) } catch (e: Exception) { JSONArray() }
        val list = ArrayList<Long>(); for (i in 0 until arr.length()) list.add(arr.getLong(i))
        setAlarms(c, list)
    }

    /** Today's total: what the app last told us plus drinks logged from notifications since. */
    private fun todayTotal(c: Context): Int {
        val p = prefs(c); val d = today()
        var ml = if (p.getString("date", "") == d) p.getInt("todayMl", 0) else 0
        val q = try { JSONArray(p.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        for (i in 0 until q.length()) { val o = q.getJSONObject(i); if (f.format(Date(o.getLong("ts"))) == d) ml += o.getInt("ml") }
        return ml
    }

    private fun litres(ml: Int): String = if (ml >= 1000) String.format(Locale.US, "%.2f", ml / 1000.0).trimEnd('0').trimEnd('.') + " L" else "$ml ml"

    fun fire(c: Context) {
        val p = prefs(c)
        if (!p.getBoolean("on", false)) return
        val last = p.getLong("lastDrink", 0)
        if (System.currentTimeMillis() - last < p.getInt("gapMin", 45) * 60_000L) return   // you just drank
        post(c, false)
    }

    private fun channel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Water reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminders to drink water" })
    }

    fun largeIcon(c: Context, name: String): Bitmap? {
        val id = c.resources.getIdentifier(name, "drawable", c.packageName); if (id == 0) return null
        val d = ContextCompat.getDrawable(c, id) ?: return null
        val px = (64 * c.resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b)); return b
    }

    fun smallIcon(c: Context): Int =
        c.resources.getIdentifier("ic_stat_app", "drawable", c.packageName).takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    private fun openApp(c: Context, pick: Boolean, code: Int): PendingIntent? {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return null
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (pick) i.putExtra(EXTRA_PICK, true)
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(c: Context, ml: Int): PendingIntent =
        PendingIntent.getBroadcast(c, NOTE_ID + ml, Intent(c, WaterActionReceiver::class.java).putExtra("ml", ml),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private val LINES = arrayOf("Time for a glass of water", "Sip break: log it with one tap", "Your body will thank you: have some water", "Stay topped up: a glass now?")

    fun post(c: Context, test: Boolean): Boolean {
        channel(c)
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        val p = prefs(c); val goal = p.getInt("goal", 3000); val tot = todayTotal(c)
        val line = LINES[((System.currentTimeMillis() / 3_600_000L) % LINES.size).toInt()]
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(smallIcon(c)).setColor(0xFF4FC3F7.toInt())
            .setLargeIcon(largeIcon(c, "ic_large_water"))
            .setContentTitle(if (test) "Test: time for water 💧" else "Time for water 💧")
            .setContentText("$line · ${litres(tot)} of ${litres(goal)} today")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(c, true, NOTE_ID))
            .addAction(0, "🥛 250 ml", action(c, 250))
            .addAction(0, "🍶 500 ml", action(c, 500))
            .build()
        return try { NotificationManagerCompat.from(c).notify(NOTE_ID, n); true } catch (e: SecurityException) { false }
    }

    /** A notification button was pressed: log it and change the notification to say so. */
    fun log(c: Context, ml: Int) {
        val p = prefs(c); val now = System.currentTimeMillis()
        val q = try { JSONArray(p.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        q.put(JSONObject().put("ts", now).put("ml", ml))
        p.edit().putString("queue", q.toString()).putLong("lastDrink", now).apply()
        val goal = p.getInt("goal", 3000); val tot = todayTotal(c)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(smallIcon(c)).setColor(0xFF4FC3F7.toInt())
            .setLargeIcon(largeIcon(c, "ic_large_water"))
            .setContentTitle("✓ $ml ml logged")
            .setContentText(if (tot >= goal) "${litres(tot)} today · goal reached 🎉" else "${litres(tot)} of ${litres(goal)} today · ${litres(goal - tot)} to go")
            .setOnlyAlertOnce(true).setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openApp(c, false, NOTE_ID + 1))
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTE_ID, n) } catch (e: SecurityException) { }
    }

    fun takeQueue(c: Context): JSONArray {
        val p = prefs(c)
        val q = try { JSONArray(p.getString("queue", "[]")) } catch (e: Exception) { JSONArray() }
        // fold today's queued drinks into the baseline so the next notification still counts them
        p.edit().putString("queue", "[]").putInt("todayMl", todayTotalAfter(p, q)).putString("date", today()).apply()
        return q
    }

    private fun todayTotalAfter(p: android.content.SharedPreferences, q: JSONArray): Int {
        val d = today(); val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var ml = if (p.getString("date", "") == d) p.getInt("todayMl", 0) else 0
        for (i in 0 until q.length()) { val o = q.getJSONObject(i); if (f.format(Date(o.getLong("ts"))) == d) ml += o.getInt("ml") }
        return ml
    }
}

class WaterAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Water.fire(c) }
}

class WaterActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { val ml = i.getIntExtra("ml", 0); if (ml > 0) Water.log(c, ml) }
}

class WaterBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Water.reschedule(c) }
}
