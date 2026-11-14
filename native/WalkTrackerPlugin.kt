package com.yashgumaste.fitnesstracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Records a walk you start and stop yourself: GPS route, distance, moving time and steps.
 * Recording runs in a foreground service (with an ongoing notification), so it keeps going
 * with the screen off or the app in the background. The app polls status() for the live screen
 * and gets the whole walk from stop().
 */
@CapacitorPlugin(
    name = "WalkTracker",
    permissions = [
        Permission(alias = "location", strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]),
        Permission(alias = "activity", strings = [Manifest.permission.ACTIVITY_RECOGNITION])
    ]
)
class WalkTrackerPlugin : Plugin() {

    override fun load() { checkOpen(activity?.intent) }
    override fun handleOnNewIntent(intent: Intent) { super.handleOnNewIntent(intent); checkOpen(intent) }
    private fun checkOpen(i: Intent?) {
        if (i?.getBooleanExtra(WalkState.EXTRA_OPEN, false) == true) { i.removeExtra(WalkState.EXTRA_OPEN); notifyListeners("open", JSObject(), true) }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (getPermissionState("location") != PermissionState.GRANTED) { requestPermissionForAlias("location", call, "afterLocation"); return }
        afterLocation(call)
    }

    @PermissionCallback
    private fun afterLocation(call: PluginCall) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.reject("Allow precise location to record the route", "NO_LOCATION"); return }
        // steps are a bonus: ask once, record the walk either way
        if (Build.VERSION.SDK_INT >= 29 && getPermissionState("activity") != PermissionState.GRANTED && !call.getBoolean("askedActivity", false)!!) {
            call.data.put("askedActivity", true); requestPermissionForAlias("activity", call, "afterActivity"); return
        }
        begin(call)
    }

    @PermissionCallback
    private fun afterActivity(call: PluginCall) { begin(call) }

    private fun begin(call: PluginCall) {
        if (WalkState.state == "idle") WalkState.reset(call.getString("kind", "walk") ?: "walk")
        val i = Intent(context, WalkService::class.java)
        ContextCompat.startForegroundService(context, i)
        call.resolve(WalkState.status(0))
    }

    @PluginMethod fun pause(call: PluginCall) { WalkState.pause(); WalkService.refresh(context); call.resolve(WalkState.status(Int.MAX_VALUE)) }
    @PluginMethod fun resume(call: PluginCall) { WalkState.resume(); WalkService.refresh(context); call.resolve(WalkState.status(Int.MAX_VALUE)) }

    /** Live numbers plus route points from index `from` on (so polling stays small). */
    @PluginMethod fun status(call: PluginCall) { call.resolve(WalkState.status(call.getInt("from", 0) ?: 0)) }

    /** Ends the walk and returns all of it. */
    @PluginMethod
    fun stop(call: PluginCall) {
        val r = WalkState.status(0); r.put("end", System.currentTimeMillis())
        WalkState.state = "idle"
        context.stopService(Intent(context, WalkService::class.java))
        call.resolve(r)
    }

    @PluginMethod
    fun discard(call: PluginCall) {
        WalkState.state = "idle"; WalkState.points.clear()
        context.stopService(Intent(context, WalkService::class.java))
        call.resolve()
    }
}

/** The walk being recorded (one at a time), shared by the plugin and the service. */
object WalkState {
    const val EXTRA_OPEN = "lm_walk_open"
    @Volatile var state = "idle"          // idle | recording | paused
    var kind = "walk"
    var start = 0L                        // wall clock ms
    private var movingBase = 0L           // elapsedRealtime when (re)started
    private var movingAcc = 0L            // ms recorded before the last pause
    var distanceM = 0.0
    var stepsBase = -1L; var stepsNow = -1L; var stepsPaused = 0L
    val points = ArrayList<DoubleArray>()  // [time ms, lat, lon, altitude m (NaN if none), accuracy m]
    var lastFix = 0L; var lastAcc = 0f
    private var last: Location? = null

    @Synchronized fun reset(k: String) {
        kind = k; state = "recording"; start = System.currentTimeMillis()
        movingBase = SystemClock.elapsedRealtime(); movingAcc = 0
        distanceM = 0.0; stepsBase = -1; stepsNow = -1; stepsPaused = 0
        points.clear(); last = null; lastFix = 0
    }
    fun movingMs(): Long = movingAcc + if (state == "recording") SystemClock.elapsedRealtime() - movingBase else 0
    @Synchronized fun pause() { if (state != "recording") return; movingAcc = movingMs(); state = "paused"; last = null }
    @Synchronized fun resume() { if (state != "paused") return; movingBase = SystemClock.elapsedRealtime(); state = "recording" }
    fun steps(): Long = if (stepsBase < 0 || stepsNow < 0) 0 else maxOf(0, stepsNow - stepsBase - stepsPaused)

    @Synchronized fun onSteps(v: Long) {
        if (stepsBase < 0) stepsBase = v
        if (state == "paused" && stepsNow >= 0) stepsPaused += maxOf(0, v - stepsNow)
        stepsNow = v
    }

    @Synchronized fun onLocation(l: Location) {
        lastFix = System.currentTimeMillis(); lastAcc = l.accuracy
        if (state != "recording") return
        if (!l.hasAccuracy() || l.accuracy > 35f) return          // too rough to draw
        val p = last
        if (p != null) {
            val d = p.distanceTo(l)
            if (d < maxOf(3f, l.accuracy * 0.5f)) return          // standing still: GPS jitter
            if (d / maxOf(1f, (l.time - p.time) / 1000f) > 12f) return   // > 43 km/h: a jump, not walking
            distanceM += d
        }
        last = l
        points.add(doubleArrayOf(l.time.toDouble(), l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN, l.accuracy.toDouble()))
    }

    @Synchronized fun status(from: Int): JSObject {
        val o = JSObject()
        o.put("state", state); o.put("kind", kind); o.put("start", start)
        o.put("movingMs", movingMs()); o.put("distanceM", distanceM); o.put("steps", steps())
        o.put("gps", if (lastFix == 0L) "searching" else if (System.currentTimeMillis() - lastFix > 15000) "lost" else "ok")
        o.put("accuracy", lastAcc.toDouble())
        o.put("count", points.size)
        val a = JSArray()
        for (i in maxOf(0, from) until points.size) {
            val p = points[i]; val q = JSONArray()
            q.put(p[0].toLong()); q.put(p[1]); q.put(p[2]); q.put(if (p[3].isNaN()) JSONObject.NULL else p[3]); q.put(p[4])
            a.put(q)
        }
        o.put("points", a); o.put("from", maxOf(0, from))
        return o
    }

    fun line(): String {
        val km = String.format(Locale.US, "%.2f km", distanceM / 1000)
        val s = movingMs() / 1000; val t = if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        val st = steps(); return "$km · $t" + (if (st > 0) " · $st steps" else "") + (if (state == "paused") " · paused" else "")
    }
}

class WalkService : Service(), LocationListener, SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { update(); handler.postDelayed(this, 5000) } }

    companion object {
        const val NOTE_ID = 7400
        private const val CHANNEL = "walk"
        fun refresh(c: Context) { c.startService(Intent(c, WalkService::class.java).putExtra("refresh", true)) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (WalkState.state == "idle") { stopSelf(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTE_ID, note(), type)
        if (i?.getBooleanExtra("refresh", false) != true) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            } catch (e: SecurityException) { } catch (e: IllegalArgumentException) { }
            if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            }
            handler.removeCallbacks(tick); handler.post(tick)
        } else update()
        return START_STICKY
    }

    private fun note(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walk recording", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a walk is being recorded" })
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra(WalkState.EXTRA_OPEN, true) }
        val pi = if (open != null) PendingIntent.getActivity(this, NOTE_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(Water.smallIcon(this)).setColor(0xFF5CF2A6.toInt())
            .setLargeIcon(Water.largeIcon(this, "ic_large_walk"))
            .setContentTitle(if (WalkState.state == "paused") "Walk paused" else "Recording your walk")
            .setContentText(WalkState.line())
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pi)
            .build()
    }

    private fun update() {
        if (WalkState.state == "idle") { stopSelf(); return }
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, note()) } catch (e: Exception) { }
    }

    override fun onLocationChanged(l: Location) { WalkState.onLocation(l) }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onSensorChanged(e: SensorEvent) { WalkState.onSteps(e.values[0].toLong()) }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (e: Exception) { }
        try { (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this) } catch (e: Exception) { }
        super.onDestroy()
    }
}
