package com.yashgumaste.fitnesstracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Logging reminders: "Good morning, Yash! How did you sleep?" and "Lunch done? Log it".
 * The app writes each message ahead of time (it knows your usual times and yesterday's day) and
 * hands over a list of {at, kind, title, body}. When you log something, the app sends a new list,
 * so a reminder for a meal you already logged is dropped.
 * Tapping a reminder opens the app straight on the right log screen; "Later" asks again in 30 minutes.
 */
@CapacitorPlugin(name = "LogReminder")
class LogReminderPlugin : Plugin() {
    private var pending: String? = null

    override fun load() { check(activity?.intent) }

    override fun handleOnNewIntent(intent: Intent) { super.handleOnNewIntent(intent); check(intent) }

    private fun check(i: Intent?) {
        if (i == null) return
        val k = i.getStringExtra(Logs.EXTRA_KIND) ?: return
        i.removeExtra(Logs.EXTRA_KIND)
        pending = k
        val o = JSObject(); o.put("kind", k)
        notifyListeners("open", o, true)
    }

    /** items: [{at: epoch ms, kind: "sleep"|"Breakfast"|"Lunch"|"Snack"|"Dinner", title, body}] */
    @PluginMethod
    fun schedule(call: PluginCall) {
        val items = call.getArray("items") ?: JSArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until items.length()) list.add(items.getJSONObject(i))
        Logs.setAlarms(context, list)
        val r = JSObject(); r.put("count", list.size); call.resolve(r)
    }

    /** The kind of log screen to open if the app was opened from a reminder (once). */
    @PluginMethod
    fun takeOpen(call: PluginCall) {
        val r = JSObject(); r.put("kind", pending); pending = null; call.resolve(r)
    }

    /** Clears a reminder that is showing, once its thing has been logged. */
    @PluginMethod
    fun clear(call: PluginCall) {
        val k = call.getString("kind", "") ?: ""
        NotificationManagerCompat.from(context).cancel(Logs.noteId(k))
        call.resolve()
    }

    @PluginMethod
    fun test(call: PluginCall) {
        val o = JSONObject().put("kind", call.getString("kind", "sleep")).put("title", call.getString("title", "Good morning!"))
            .put("body", call.getString("body", "How did you sleep?"))
        val r = JSObject(); r.put("ok", Logs.post(context, o)); call.resolve(r)
    }
}

object Logs {
    const val EXTRA_KIND = "lm_log_kind"
    private const val CHANNEL = "logs"
    private const val ALARM_BASE = 9100
    private const val MAX = 24
    private val KINDS = listOf("sleep", "Breakfast", "Lunch", "Snack", "Dinner")

    fun prefs(c: Context) = c.getSharedPreferences("log_reminder", Context.MODE_PRIVATE)
    fun noteId(kind: String) = 7500 + maxOf(0, KINDS.indexOf(kind))

    private fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun setAlarms(c: Context, items: List<JSONObject>) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until MAX) {
            val pi = PendingIntent.getBroadcast(c, ALARM_BASE + i, Intent(c, LogAlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) { am.cancel(pi); pi.cancel() }
        }
        val now = System.currentTimeMillis()
        val future = items.filter { it.optLong("at") > now }.sortedBy { it.optLong("at") }.take(MAX)
        future.forEachIndexed { i, o ->
            val pi = PendingIntent.getBroadcast(c, ALARM_BASE + i, Intent(c, LogAlarmReceiver::class.java).putExtra("slot", i),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setExact(am, o.optLong("at"), pi)
        }
        prefs(c).edit().putString("items", JSONArray(future).toString()).apply()
    }

    private fun items(c: Context): JSONArray = try { JSONArray(prefs(c).getString("items", "[]")) } catch (e: Exception) { JSONArray() }

    /** After a restart or an app update, put the remaining alarms back. */
    fun reschedule(c: Context) {
        val a = items(c); val list = ArrayList<JSONObject>()
        for (i in 0 until a.length()) list.add(a.getJSONObject(i))
        setAlarms(c, list)
        val s = prefs(c).getString("snooze", null)
        if (s != null) try { snooze(c, JSONObject(s), JSONObject(s).optLong("at")) } catch (e: Exception) { }
    }

    /** An alarm went off: post the reminder that was due (the newest one not later than now). */
    fun fire(c: Context, slot: Int) {
        val a = items(c); val now = System.currentTimeMillis()
        var due: JSONObject? = if (slot in 0 until a.length()) a.getJSONObject(slot) else null
        if (due == null || due.optLong("at") > now + 60_000) {
            due = null
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optLong("at") <= now + 60_000) due = o }
        }
        due ?: return
        if (now - due.optLong("at") > 3 * 3600_000L) return     // phone was off for hours: too late to ask
        post(c, due)
    }

    private fun channel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Logging reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Morning sleep check-in and meal logging reminders" })
    }

    private fun openApp(c: Context, kind: String): PendingIntent? {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return null
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        i.putExtra(EXTRA_KIND, kind)
        return PendingIntent.getActivity(c, noteId(kind), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(c: Context, o: JSONObject, what: String): PendingIntent =
        PendingIntent.getBroadcast(c, noteId(o.optString("kind")) * 10 + (if (what == "later") 1 else 2),
            Intent(c, LogActionReceiver::class.java).putExtra("what", what).putExtra("item", o.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun post(c: Context, o: JSONObject): Boolean {
        channel(c)
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        val kind = o.optString("kind", "sleep"); val sleep = kind == "sleep"
        val body = o.optString("body")
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(Water.smallIcon(c)).setColor(if (sleep) 0xFFB39DDB.toInt() else 0xFFFFB74D.toInt())
            .setLargeIcon(Water.largeIcon(c, if (sleep) "ic_large_sleep" else "ic_large_meal"))
            .setContentTitle(o.optString("title"))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(c, kind))
            .addAction(0, if (sleep) "😴 Log sleep" else "🍽️ Log ${kind.lowercase()}", openApp(c, kind))
            .addAction(0, "⏰ Later", action(c, o, "later"))
            .apply { if (kind == "Snack") addAction(0, "No snack", action(c, o, "skip")) }
            .build()
        return try { NotificationManagerCompat.from(c).notify(noteId(kind), n); true } catch (e: SecurityException) { false }
    }

    /** "Later": ask again in 30 minutes. */
    fun snooze(c: Context, o: JSONObject, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val item = JSONObject(o.toString()).put("at", at)
        prefs(c).edit().putString("snooze", item.toString()).apply()
        val pi = PendingIntent.getBroadcast(c, ALARM_BASE + 90, Intent(c, LogAlarmReceiver::class.java).putExtra("snooze", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setExact(am, at, pi)
    }

    fun fireSnooze(c: Context) {
        val s = prefs(c).getString("snooze", null) ?: return
        prefs(c).edit().remove("snooze").apply()
        try { post(c, JSONObject(s)) } catch (e: Exception) { }
    }
}

class LogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getBooleanExtra("snooze", false)) Logs.fireSnooze(c) else Logs.fire(c, i.getIntExtra("slot", -1))
    }
}

class LogActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val o = try { JSONObject(i.getStringExtra("item") ?: "{}") } catch (e: Exception) { JSONObject() }
        NotificationManagerCompat.from(c).cancel(Logs.noteId(o.optString("kind")))
        if (i.getStringExtra("what") == "later") Logs.snooze(c, o, System.currentTimeMillis() + 30 * 60_000L)
    }
}

class LogBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Logs.reschedule(c) }
}
