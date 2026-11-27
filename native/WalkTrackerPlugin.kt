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
import android.location.GnssStatus
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Vibrator
import android.os.VibratorManager
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

    /** Walk training: the plan of blocks for this walk (or null to stop the cues). */
    @PluginMethod fun train(call: PluginCall) {
        val p = call.getObject("plan"); WalkState.setTrain(if (p == null || p.length() == 0) null else p)
        call.resolve()
    }
    /** One of the training buzzes, to feel what it means. */
    @PluginMethod fun buzz(call: PluginCall) { Cues.buzz(context, call.getString("kind", "block") ?: "block"); call.resolve() }

    // ── voices for the spoken cues: the English voices the phone's text-to-speech has (Google's has many accents) ──
    private var tts: android.speech.tts.TextToSpeech? = null; private var ttsOk = false
    private fun withTts(fn: (android.speech.tts.TextToSpeech?) -> Unit) {
        val t = tts; if (t != null && ttsOk) { fn(t); return }
        tts = android.speech.tts.TextToSpeech(context) { st -> ttsOk = st == android.speech.tts.TextToSpeech.SUCCESS; fn(if (ttsOk) tts else null) }
    }
    @PluginMethod fun voices(call: PluginCall) {
        withTts { t ->
            if (t == null) { call.reject("Text-to-speech isn't available on this phone"); return@withTts }
            val arr = com.getcapacitor.JSArray()
            try {
                for (v in (t.voices ?: emptySet()).sortedBy { it.name }) {
                    if (v.locale.language != "en") continue
                    val feats = v.features ?: emptySet()
                    arr.put(JSObject().put("name", v.name).put("lang", v.locale.toLanguageTag()).put("country", v.locale.getDisplayCountry(java.util.Locale.ENGLISH))
                        .put("quality", v.quality).put("network", v.isNetworkConnectionRequired)
                        .put("installed", !feats.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
                }
            } catch (e: Exception) { }
            call.resolve(JSObject().put("voices", arr).put("current", t.voice?.name ?: ""))
        }
    }
    /** Say a line in a voice, to hear it before choosing it. */
    @PluginMethod fun sayTest(call: PluginCall) {
        val text = call.getString("text", "Start jogging for 2 minutes.") ?: ""; val name = call.getString("voice", "") ?: ""
        withTts { t ->
            if (t == null) { call.reject("Text-to-speech isn't available on this phone"); return@withTts }
            try { val v = t.voices?.firstOrNull { it.name == name }; if (v != null) t.voice = v else t.language = java.util.Locale("en", "IN")
                t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "test") } catch (e: Exception) { }
            call.resolve()
        }
    }

    /** Ask for the Physical activity permission during a walk (steps), then start the step sensors. */
    @PluginMethod fun allowSteps(call: PluginCall) {
        if (Build.VERSION.SDK_INT < 29 || getPermissionState("activity") == PermissionState.GRANTED) { afterAllowSteps(call); return }
        requestPermissionForAlias("activity", call, "afterAllowSteps")
    }
    @PermissionCallback
    private fun afterAllowSteps(call: PluginCall) {
        val ok = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (ok && WalkState.state != "idle") context.startService(Intent(context, WalkService::class.java).putExtra("refresh", true).putExtra("steps", true))
        call.resolve(JSObject().put("granted", ok))
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
    val points = ArrayList<DoubleArray>()  // [time ms, lat, lon, altitude m (NaN if none), accuracy m, barometer altitude m (NaN if none), steps so far]
    @Volatile var baro = Double.NaN        // smoothed height from the air-pressure sensor (good for climb, not for absolute height)
    var gainM = 0.0                        // climb so far, for the live screen
    private var refAlt = Double.NaN
    var lastFix = 0L; var lastAcc = 0f
    @Volatile var satsUsed = 0; @Volatile var satsSeen = 0   // while finding GPS: satellites in the fix / in view
    private var last: Location? = null

    // Where steps come from: the phone's step counter, else its step detector, else our own step counting from the
    // motion sensor (accelerometer). Some phones' step sensors go quiet (no events until they're restarted, or no
    // permission): steps then sat at "—" for a whole walk. The service re-arms them, and counts from motion meanwhile.
    @Volatile var stepPerm = true; @Volatile var hasCounter = false; @Volatile var hasDetector = false
    @Volatile var lastHw = 0L                // elapsed ms of the last event from the phone's step sensors
    private var detTotal = 0L                // steps from the step detector while recording
    private var accTotal = 0L                // steps counted from motion while the step sensors were quiet
    @Volatile var accOn = false
    private var accGapStart = 0L; private var hwAtGap = 0L

    // walk training (set by the app when the walk starts): the plan and its blocks
    class Block(val from: Long, val kind: String, val label: String, val cadMin: Double, val kmhMin: Double, val strideMin: Double, val cadMax: Double, val jog: Boolean, val say: String)
    @Volatile var trainJson: String? = null
    @Volatile var blocks: List<Block>? = null
    @Volatile var trainVib = true
    @Volatile var trainVoice = true
    @Volatile var kmVoice = true
    @Volatile var voiceName = ""
    @Synchronized fun setTrain(p: JSONObject?) {
        if (p == null) { trainJson = null; blocks = null; return }
        val a = p.optJSONArray("blocks") ?: JSONArray(); val out = ArrayList<Block>()
        for (i in 0 until a.length()) { val b = a.optJSONObject(i) ?: continue
            out.add(Block(b.optLong("from"), b.optString("kind"), b.optString("label"), b.optDouble("cadMin", 0.0).let { if (it.isNaN()) 0.0 else it },
                b.optDouble("kmhMin", 0.0).let { if (it.isNaN()) 0.0 else it }, b.optDouble("strideMin", 0.0).let { if (it.isNaN()) 0.0 else it },
                b.optDouble("cadMax", 0.0).let { if (it.isNaN()) 0.0 else it }, b.optBoolean("jog", false), b.optString("say", ""))) }
        trainJson = p.toString(); blocks = out; trainVib = p.optBoolean("vib", true); trainVoice = p.optBoolean("voice", true); kmVoice = p.optBoolean("kmVoice", true); voiceName = p.optString("voiceName", "")
    }
    /** Speed over the last ~15 s of fixes (km/h); 0 if no fix for 6 s; null before there are two. */
    @Synchronized fun recentKmh(): Double? {
        if (points.size < 2) return null
        val L = points[points.size - 1]; if (System.currentTimeMillis() - L[0] > 6000) return 0.0
        var i = points.size - 1; while (i > 0 && L[0] - points[i - 1][0] <= 15000) i--
        val dt = (L[0] - points[i][0]) / 1000.0; if (dt < 5) return null
        var d = 0.0; val r = FloatArray(1)
        for (k in i + 1 until points.size) { Location.distanceBetween(points[k - 1][1], points[k - 1][2], points[k][1], points[k][2], r); d += r[0] }
        return d / dt * 3.6
    }

    @Synchronized fun reset(k: String) {
        kind = k; state = "recording"; start = System.currentTimeMillis()
        movingBase = SystemClock.elapsedRealtime(); movingAcc = 0
        distanceM = 0.0; stepsBase = -1; stepsNow = -1; stepsPaused = 0
        points.clear(); last = null; lastFix = 0; gainM = 0.0; refAlt = Double.NaN; det.clear(); cnt.clear(); cntRise = 0L; satsUsed = 0; satsSeen = 0
        trainJson = null; blocks = null; lastHw = SystemClock.elapsedRealtime(); detTotal = 0; accTotal = 0; accOn = false; accRhythm = false; accPend.clear(); accLast = 0L
    }

    fun onPressure(hPa: Float) {
        val a = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa).toDouble()
        baro = if (baro.isNaN()) a else baro * 0.85 + a * 0.15
    }
    fun movingMs(): Long = movingAcc + if (state == "recording") SystemClock.elapsedRealtime() - movingBase else 0
    @Synchronized fun pause() { if (state != "recording") return; movingAcc = movingMs(); state = "paused"; last = null }
    @Synchronized fun resume() { if (state != "paused") return; movingBase = SystemClock.elapsedRealtime(); lastHw = movingBase; state = "recording" }
    private fun hwSteps(): Long = maxOf(if (stepsBase < 0 || stepsNow < 0) 0 else maxOf(0, stepsNow - stepsBase - stepsPaused), detTotal)
    fun steps(): Long = hwSteps() + accTotal
    fun stepSrc(): String = if (accOn && accTotal > accGapStart) "motion" else if (stepsNow >= 0) "counter" else if (detTotal > 0) "detector" else if (accTotal > 0) "motion" else "none"

    /** The step sensors have gone quiet: count from motion until they're back. */
    @Synchronized fun beginMotion() { if (accOn) return; accOn = true; accGapStart = accTotal; hwAtGap = hwSteps(); accS = Double.NaN; accBase = Double.NaN; accUp = false; accRhythm = false; accPend.clear() }
    /** They're back: if they caught up on the steps in between (a late batch), drop ours so nothing counts twice. */
    @Synchronized fun endMotion() {
        if (!accOn) return; accOn = false
        val hw = hwSteps() - hwAtGap; val acc = accTotal - accGapStart
        if (acc > 0 && hw >= acc / 2) accTotal = accGapStart
    }
    // a simple pedometer: smoothed acceleration against its slow average; a step is a rise above an adaptive
    // threshold and back; counting starts once 4 steps keep a walking rhythm (0.25–2 s apart), so a jolt isn't one
    private var accS = Double.NaN; private var accBase = Double.NaN; private var accUp = false
    private var accPeak = 0.0; private var accAmp = 2.0; private var accLast = 0L; private var accRhythm = false
    private val accPend = ArrayList<Long>()
    @Synchronized fun onAccel(x: Float, y: Float, z: Float, tNs: Long) {
        if (state != "recording" || !accOn) return
        val m = Math.sqrt((x * x + y * y + z * z).toDouble()); val t = eventMs(tNs)
        accS = if (accS.isNaN()) m else accS + 0.25 * (m - accS)
        accBase = if (accBase.isNaN()) m else accBase + 0.02 * (m - accBase)
        val d = accS - accBase; val thr = maxOf(0.6, accAmp * 0.45)
        if (!accUp) { if (d > thr) { accUp = true; accPeak = d }; return }
        if (d > accPeak) accPeak = d
        if (d > thr * 0.3) return
        accUp = false; accAmp = accAmp * 0.8 + accPeak * 0.2
        val gap = t - accLast
        if (accLast != 0L && gap < 250) return
        accLast = t
        if (accRhythm && gap <= 2000) { accTotal++; onStepDetected(t, false); return }
        if (gap > 2000) { accPend.clear(); accRhythm = false }
        accPend.add(t)
        if (accPend.size >= 4) { accTotal += accPend.size; for (p in accPend) onStepDetected(p, false); accPend.clear(); accRhythm = true }
    }

    // live cadence: when the latest steps happened. The step detector fires once a step, straight away;
    // the step counter can arrive in batches a few seconds late, so it's only the fallback.
    // Some phones (Samsung among them) hold step-detector events back and hand them over in a batch, or stop
    // sending them while the counter keeps counting: so steps are timed by when they happened (the event's own
    // time), and when the detector goes quiet but the counter is still going up, the counter decides.
    private val det = ArrayDeque<Long>()          // elapsed ms of each detected step (last 10 s)
    private val cnt = ArrayDeque<LongArray>()     // [elapsed ms, counter value] (last 20 s)
    private var cntRise = 0L                      // when the counter last went up
    /** A sensor event's time in elapsed ms; its arrival time if the phone's sensor clock is on another base. */
    fun eventMs(tsNanos: Long): Long {
        val now = SystemClock.elapsedRealtime(); val t = tsNanos / 1_000_000
        return if (t in (now - 60_000)..(now + 1000)) minOf(t, now) else now
    }
    @Synchronized fun onStepDetected(t: Long = SystemClock.elapsedRealtime(), hw: Boolean = true) {
        if (state != "recording") return
        if (hw) { detTotal++; lastHw = SystemClock.elapsedRealtime() }
        if (det.isEmpty() || t >= det.last()) det.addLast(t)
        val now = SystemClock.elapsedRealtime()
        while (det.isNotEmpty() && now - det.first() > 10000) det.removeFirst()
    }
    /** Steps a minute over the last few seconds; 0 once steps stop; null if it can't tell yet. */
    @Synchronized fun liveSpm(): Double? {
        val now = SystemClock.elapsedRealtime()
        // 1. step detector, while it's keeping up
        if (det.isNotEmpty() && now - det.last() <= 2500) {
            val w = det.filter { det.last() - it <= 4000 }
            if (w.size >= 4 && w.last() > w.first()) return (w.size - 1) * 60000.0 / (w.last() - w.first())
        }
        // 2. step counter, while it's still going up (it may come in batches every few seconds)
        if (cnt.size >= 2 && now - cntRise <= 12000) {
            val last = cnt.last(); val first = cnt.firstOrNull { last[0] - it[0] <= 12000 && last[0] - it[0] >= 3000 }
            if (first != null && last[1] > first[1]) return (last[1] - first[1]) * 60000.0 / (last[0] - first[0])
            return null
        }
        // 3. neither has new steps: standing still (once we've seen steps at all)
        return if (det.isNotEmpty() || cntRise > 0) 0.0 else null
    }

    @Synchronized fun onSteps(v: Long) {
        val t = SystemClock.elapsedRealtime()
        if (state == "recording" && v != stepsNow) lastHw = t
        if (state == "recording") {
            if (cnt.isNotEmpty() && v > cnt.last()[1]) cntRise = t
            cnt.addLast(longArrayOf(t, v)); while (cnt.isNotEmpty() && t - cnt.first()[0] > 20000) cnt.removeFirst()
        }
        if (stepsBase < 0) stepsBase = v
        if (state == "paused" && stepsNow >= 0) stepsPaused += maxOf(0, v - stepsNow)
        stepsNow = v
    }

    @Synchronized fun onLocation(l: Location) {
        lastFix = System.currentTimeMillis(); lastAcc = l.accuracy
        if (state != "recording") return
        if (!l.hasAccuracy() || l.accuracy > 25f) return          // too rough to draw (Wi-Fi positions indoors are 20-50 m)
        val p = last
        if (p != null) {
            val d = p.distanceTo(l)
            if (d < maxOf(3f, l.accuracy * 0.5f)) return          // standing still: GPS jitter
            if (d / maxOf(1f, (l.time - p.time) / 1000f) > 12f) return   // > 43 km/h: a jump, not walking
            distanceM += d
        }
        last = l
        // height above sea level where Android can give it (14+), otherwise GPS height
        // skip a height the GPS itself says is rough (just after it starts it can be 100 m out)
        val roughHeight = Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy() && l.verticalAccuracyMeters > 25f
        val alt = if (roughHeight) Double.NaN
            else if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters
            else if (l.hasAltitude()) l.altitude else Double.NaN
        val b = baro
        points.add(doubleArrayOf(l.time.toDouble(), l.latitude, l.longitude, alt, l.accuracy.toDouble(), b, steps().toDouble()))
        // live climb: barometer if there is one (steady), else GPS height with a bigger dead band (it wobbles)
        val h = if (!b.isNaN()) b else alt
        if (!h.isNaN()) {
            if (refAlt.isNaN()) refAlt = h
            val dz = h - refAlt
            if (Math.abs(dz) >= (if (!b.isNaN()) 2.0 else 6.0)) { if (dz > 0) gainM += dz; refAlt = h }
        }
    }

    @Synchronized fun status(from: Int): JSObject {
        val o = JSObject()
        o.put("state", state); o.put("kind", kind); o.put("start", start)
        o.put("movingMs", movingMs()); o.put("distanceM", distanceM); o.put("steps", steps())
        o.put("gps", if (lastFix == 0L) "searching" else if (System.currentTimeMillis() - lastFix > 15000) "lost" else "ok")
        o.put("accuracy", lastAcc.toDouble())
        o.put("count", points.size); o.put("gainM", gainM)
        o.put("alt", if (points.isEmpty() || points[points.size - 1][3].isNaN()) JSONObject.NULL else points[points.size - 1][3])
        o.put("baro", !baro.isNaN())
        o.put("spm", liveSpm() ?: JSONObject.NULL)
        o.put("stepSrc", stepSrc())
        o.put("train", trainJson?.let { try { JSObject(it) } catch (e: Exception) { null } } ?: JSONObject.NULL)
        o.put("stepIssue", if (!stepPerm) "permission" else if (!hasCounter && !hasDetector) "nosensor" else JSONObject.NULL)
        o.put("sats", JSArray().put(satsUsed).put(satsSeen))
        val a = JSArray()
        for (i in maxOf(0, from) until points.size) {
            val p = points[i]; val q = JSONArray()
            q.put(p[0].toLong()); q.put(p[1]); q.put(p[2]); q.put(if (p[3].isNaN()) JSONObject.NULL else p[3]); q.put(p[4])
            q.put(if (p.size < 6 || p[5].isNaN()) JSONObject.NULL else p[5])
            q.put(if (p.size < 7) JSONObject.NULL else p[6].toLong())     // steps so far (for cadence and jogs)
            a.put(q)
        }
        o.put("points", a); o.put("from", maxOf(0, from))
        return o
    }

    fun line(): String {
        val km = String.format(Locale.US, "%.2f km", distanceM / 1000)
        val s = movingMs() / 1000; val t = if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        val st = steps(); val up = if (kind == "hike" && gainM >= 1) " · ↑ ${gainM.toInt()} m" else ""
        return "$km · $t$up" + (if (st > 0) " · $st steps" else "") + (if (state == "paused") " · paused" else "")
    }
}

class WalkService : Service(), LocationListener, SensorEventListener {
    private val fused = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { for (l in r.locations) WalkState.onLocation(l) }
    }
    // how many satellites the phone sees and uses, so "Finding GPS…" can show progress
    private val gnss: Any? = if (Build.VERSION.SDK_INT >= 24) object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(st: GnssStatus) {
            var used = 0; for (i in 0 until st.satelliteCount) if (st.usedInFix(i)) used++
            WalkState.satsUsed = used; WalkState.satsSeen = st.satelliteCount
        }
    } else null
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
                // Google's fused location (like Google Maps): GPS plus Wi-Fi, cell towers and the motion sensors, and it
                // keeps the satellite data warm, so it finds you far sooner than the GPS chip alone. Without Google Play
                // services, the GPS chip on its own.
                if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).setMinUpdateIntervalMillis(1000L)
                        .setWaitForAccurateLocation(false).build()
                    LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(req, fused, Looper.getMainLooper())
                } else lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
                if (Build.VERSION.SDK_INT >= 24 && gnss != null) lm.registerGnssStatusCallback(gnss as GnssStatus.Callback, handler)
            } catch (e: SecurityException) { } catch (e: IllegalArgumentException) { }
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            armSteps(sm)
            // air pressure gives a much steadier height than GPS, for climb on hikes (most phones have the sensor)
            sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, 1_000_000) }
            handler.removeCallbacks(tick); handler.post(tick)
        } else { if (i.getBooleanExtra("steps", false)) armSteps(getSystemService(Context.SENSOR_SERVICE) as SensorManager); update() }
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
            .setContentTitle(run { val w = if (WalkState.kind == "hike") "hike" else "walk"
                if (WalkState.state == "paused") w.replaceFirstChar { it.uppercase() } + " paused" else (trainTitle() ?: "Recording your $w") })
            .setContentText(WalkState.line())
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pi)
            .build()
    }

    /** (Re)start the phone's step sensors. Re-registering wakes up ones that have gone quiet on some phones. */
    private fun armSteps(sm: SensorManager) {
        WalkState.stepPerm = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val c = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER); val d = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        WalkState.hasCounter = c != null; WalkState.hasDetector = d != null
        if (!WalkState.stepPerm) return
        c?.let { sm.unregisterListener(this, it); sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // one event a step, with no delay: lets the screen show "Jogging" within a few seconds
        d?.let { sm.unregisterListener(this, it); sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }
    private var lastRearm = 0L
    /** No step events for 20 s while recording: re-arm the step sensors (once a minute at most) and count from
     *  motion meanwhile; once they deliver again, stop counting from motion. */
    private fun stepWatch() {
        if (WalkState.state != "recording") return
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val now = SystemClock.elapsedRealtime(); val quiet = now - WalkState.lastHw
        if (quiet > 20_000) {
            if (!WalkState.accOn) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { WalkState.beginMotion(); sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            if (now - lastRearm > 60_000) { lastRearm = now; armSteps(sm) }
        } else if (quiet < 6000 && WalkState.accOn) {
            WalkState.endMotion(); sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.unregisterListener(this, it) }
        }
    }

    // walk training: a buzz when a block starts, and when you're off its target for 15 s (once a minute at most)
    private var trainIdx = -1; private var offTicks = 0; private var lastCue = 0L; private var lastKind = ""
    private fun trainBlock(): Pair<Int, Long>? {
        val bl = WalkState.blocks ?: return null; if (bl.isEmpty()) return null
        val sec = WalkState.movingMs() / 1000; var i = 0
        for (k in bl.indices) if (bl[k].from <= sec) i = k
        return Pair(i, sec)
    }
    private fun trainWatch() {
        val bl = WalkState.blocks ?: run { trainIdx = -1; return }
        if (WalkState.state != "recording") return
        val (i, sec) = trainBlock() ?: return
        val b = bl[i]; val now = SystemClock.elapsedRealtime()
        if (i != trainIdx) {
            val prevJog = trainIdx >= 0 && trainIdx < bl.size && bl[trainIdx].jog
            val first = trainIdx < 0; trainIdx = i; offTicks = 0
            if (!first || sec > 5) cue(if (b.jog) "jog" else if (prevJog) "walk" else if (b.kind == "push" || b.kind == "race") "faster" else if (b.kind == "cool" || b.kind == "easy") "easy" else "block")
            say(b.say)
            return
        }
        val into = sec - b.from; val spm = WalkState.liveSpm(); val kmh = WalkState.recentKmh()
        val stride = if (spm != null && spm >= 40 && kmh != null && kmh > 0.5) kmh / 3.6 * 60 / spm else null
        val c: String? = when {
            b.jog -> if (into >= 15 && (spm == null || spm < 135)) "jog" else null
            i > 0 && bl[i - 1].jog && into in 15L..45L && spm != null && spm >= 140 -> "walk"
            b.cadMax > 0 && spm != null && spm > b.cadMax + 4 -> "easy"
            into < 20 -> null
            b.cadMin > 0 && spm != null && spm < b.cadMin - 3 -> "faster"
            b.strideMin > 0 && stride != null && stride < b.strideMin * 0.97 -> "longer"
            // steps quick enough but speed low: the steps are short, so it's "longer steps", not "faster"
            b.kmhMin > 0 && kmh != null && kmh > 0.5 && kmh < b.kmhMin * 0.97 -> if (b.cadMin > 0 && spm != null && spm >= b.cadMin - 3) "longer" else "faster"
            else -> null
        }
        if (c == null) { offTicks = 0; return }
        if (++offTicks >= 3 && now - lastCue > 60_000) { cue(c, b); offTicks = 0 }
    }
    private fun cue(kind: String, b: WalkState.Block? = null) {
        lastCue = SystemClock.elapsedRealtime(); lastKind = kind; if (WalkState.trainVib) Cues.buzz(this, kind)
        if (b == null) return
        say(when (kind) {
            "faster" -> if (b.cadMin > 0) "Pick up the pace. ${b.cadMin.toInt()} steps a minute." else "A bit faster. ${fmt1(b.kmhMin)} kilometres an hour."
            "longer" -> if (b.kmhMin > 0) "Good rhythm. Now longer steps, for ${fmt1(b.kmhMin)} kilometres an hour." else "Longer steps. Push off from your back foot."
            "jog" -> "Jog now. Light, quick steps."
            "walk" -> "Walk now. Back to brisk walking."
            "easy" -> "Ease off. Keep it easy today."
            else -> "" })
    }
    private fun fmt1(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

    // ── the spoken cues: Android's text-to-speech, lowering any music for a moment ──
    private var tts: TextToSpeech? = null; private var ttsReady = false; private var focus: AudioFocusRequest? = null
    private val speechAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private fun ensureTts() {
        if (tts != null || !WalkState.trainVoice) return
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) { val t = tts ?: return@TextToSpeech
                val loc = listOf(Locale("en", "IN"), Locale.UK, Locale.US).firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
                if (loc != null) t.language = loc
                // the voice chosen in Settings → Walk training (an accent), if the phone has it
                if (WalkState.voiceName.isNotEmpty()) try { t.voices?.firstOrNull { it.name == WalkState.voiceName && !it.isNetworkConnectionRequired }?.let { t.voice = it } } catch (e: Exception) { }
                t.setAudioAttributes(speechAttrs); t.setSpeechRate(1.02f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { unduck() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { unduck() } })
                ttsReady = true }
        }
    }
    @Suppress("DEPRECATION")
    private fun duck() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) { val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(speechAttrs).build(); focus = f; am.requestAudioFocus(f) }
        else am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }
    @Suppress("DEPRECATION")
    private fun unduck() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (tts?.isSpeaking == true) return
        if (Build.VERSION.SDK_INT >= 26) focus?.let { am.abandonAudioFocusRequest(it) } else am.abandonAudioFocus(null)
    }
    private fun say(text: String) {
        if (text.isBlank() || !WalkState.trainVoice) return
        ensureTts(); val t = tts ?: return
        if (!ttsReady) { handler.postDelayed({ if (ttsReady) say(text) }, 1500); return }
        duck(); t.speak(text, TextToSpeech.QUEUE_ADD, null, "lm" + SystemClock.elapsedRealtime())
    }
    // every kilometre: the time so far and the last kilometre's time
    private var lastKm = 0; private var lastKmMs = 0L
    private fun kmWatch() {
        if (WalkState.blocks == null || !WalkState.kmVoice || !WalkState.trainVoice || WalkState.state != "recording") return
        val k = (WalkState.distanceM / 1000).toInt(); val ms = WalkState.movingMs()
        if (k <= lastKm) { if (k < lastKm) { lastKm = k; lastKmMs = ms }; return }
        val split = ms - lastKmMs; lastKm = k; lastKmMs = ms
        fun words(t: Long): String { val s = t / 1000; val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
            return listOfNotNull(if (h > 0) "$h hour" + (if (h > 1) "s" else "") else null, if (m > 0) "$m minute" + (if (m != 1L) "s" else "") else null,
                if (x > 0 && h == 0L) "$x second" + (if (x != 1L) "s" else "") else null).joinToString(" ") }
        say("Kilometre $k. ${words(ms)}." + (if (k > 1) " Last kilometre ${words(split)}." else ""))
    }
    private fun trainTitle(): String? {
        val bl = WalkState.blocks ?: return null; val (i, sec) = trainBlock() ?: return null
        val nx = if (i + 1 < bl.size) bl[i + 1].from - sec else -1L
        if (nx <= 0) return bl[i].label
        val m = nx / 60; val sc = nx % 60
        val left = (if (m > 0) "$m min" + (if (m > 1) "s" else "") else "") + (if (sc > 0) (if (m > 0) " " else "") + "$sc sec" + (if (sc > 1) "s" else "") else "")
        return bl[i].label + " · " + left + " left"
    }

    private fun update() {
        if (WalkState.state == "idle") { stopSelf(); return }
        try { stepWatch() } catch (e: Exception) { }
        try { trainWatch() } catch (e: Exception) { }
        try { kmWatch() } catch (e: Exception) { }
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, note()) } catch (e: Exception) { }
    }

    override fun onLocationChanged(l: Location) { WalkState.onLocation(l) }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> WalkState.onPressure(e.values[0])
            Sensor.TYPE_STEP_DETECTOR -> WalkState.onStepDetected(WalkState.eventMs(e.timestamp))
            Sensor.TYPE_ACCELEROMETER -> WalkState.onAccel(e.values[0], e.values[1], e.values[2], e.timestamp)
            Sensor.TYPE_STEP_COUNTER -> WalkState.onSteps(e.values[0].toLong())
            else -> {}
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { tts?.stop(); tts?.shutdown(); tts = null; unduck() } catch (e: Exception) { }
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (e: Exception) { }
        try { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(fused) } catch (e: Exception) { }
        try { if (Build.VERSION.SDK_INT >= 24 && gnss != null) (getSystemService(Context.LOCATION_SERVICE) as LocationManager).unregisterGnssStatusCallback(gnss as GnssStatus.Callback) } catch (e: Exception) { }
        try { (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this) } catch (e: Exception) { }
        super.onDestroy()
    }
}

/** The walk-training buzzes: each one a different rhythm, so you can tell them apart in a pocket. */
object Cues {
    private val PAT = mapOf(
        "jog" to longArrayOf(0, 400, 150, 400, 150, 400),      // 3 long: start jogging
        "walk" to longArrayOf(0, 900),                         // 1 very long: walk now
        "faster" to longArrayOf(0, 120, 100, 120, 100, 120),   // 3 quick: pick up the pace
        "longer" to longArrayOf(0, 120, 150, 500),             // short + long: longer steps
        "easy" to longArrayOf(0, 500, 300, 500),               // 2 medium: ease off
        "block" to longArrayOf(0, 200))                        // a new block starts
    @Suppress("DEPRECATION")
    fun buzz(c: Context, kind: String) {
        val p = PAT[kind] ?: PAT["block"]!!
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                else c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (v == null || !v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(p, -1)) else v.vibrate(p, -1)
        } catch (e: Exception) { }
    }
}
