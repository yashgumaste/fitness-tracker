package com.yashgumaste.fitnesstracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Home-screen widgets: Steps, Calories burned, Calories eaten and In & out.
 *
 * The app does the sums and hands over a snapshot (update) whenever something changes; the widgets draw from it.
 * While the app is closed, a worker reads today's steps from Health Connect every 15 minutes (only if you allowed
 * reading in the background) and the widgets recompute what depends on steps and the time of day: distance,
 * calories from steps and resting burn so far. Each widget is drawn as one picture at its exact size, so it can
 * change layout as you resize it (1×1 up to 4×4) and use the app's shapes and colours.
 */
@CapacitorPlugin(name = "Widgets")
class WidgetPlugin : Plugin() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** data: the snapshot built by the app (see Widgets.model for the fields). */
    @PluginMethod
    fun update(call: PluginCall) {
        val data = call.getObject("data")
        if (data == null) { call.reject("no data"); return }
        Widgets.save(context, data.toString())
        Widgets.refreshAll(context)
        if (Widgets.count(context).values.sum() > 0) Widgets.schedule(context)
        call.resolve()
    }

    /** Which widgets are on the home screen, and whether steps can be read in the background. */
    @PluginMethod
    fun status(call: PluginCall) {
        scope.launch {
            val r = JSObject()
            val n = JSObject(); for ((k, v) in Widgets.count(context)) n.put(k, v); r.put("count", n)
            val g = Widgets.granted(context)
            r.put("hc", g != null)
            r.put("steps", g?.contains(HealthPermission.getReadPermission(StepsRecord::class)) == true)
            r.put("background", g?.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) == true)
            call.resolve(r)
        }
    }

    /** Shows Health Connect's own screen for reading steps, also while the app is closed. */
    @PluginMethod
    fun requestBackground(call: PluginCall) {
        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            val intent = contract.createIntent(context, setOf(HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))
            startActivityForResult(call, intent, "backgroundResult")
        } catch (e: Exception) { call.reject(e.message ?: "Could not open Health Connect") }
    }

    @ActivityCallback
    private fun backgroundResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        scope.launch {
            val g = Widgets.granted(context)
            val r = JSObject()
            r.put("steps", g?.contains(HealthPermission.getReadPermission(StepsRecord::class)) == true)
            r.put("background", g?.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND) == true)
            call.resolve(r)
            Widgets.schedule(context, now = true)
        }
    }
}

open class LmWidget(private val kind: String) : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        for (id in ids) Widgets.draw(c, m, id, kind)
        Widgets.schedule(c)
    }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) { Widgets.draw(c, m, id, kind) }
    override fun onEnabled(c: Context) { Widgets.schedule(c, now = true) }
    override fun onDisabled(c: Context) { if (Widgets.count(c).values.sum() == 0) WorkManager.getInstance(c).cancelUniqueWork(Widgets.WORK) }
}
class StepsWidget : LmWidget("steps")
class BurnWidget : LmWidget("burn")
class FoodWidget : LmWidget("food")
class InOutWidget : LmWidget("io")
class InTodayWidget : LmWidget("today")
class EatWidget : LmWidget("eat")

/** Every 15 minutes: today's steps from Health Connect (if allowed in the background), then redraw. */
class WidgetWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        try { Widgets.readSteps(applicationContext) } catch (e: Exception) { }
        Widgets.refreshAll(applicationContext)
        return Result.success()
    }
}

object Widgets {
    const val WORK = "lm_widgets"
    private const val PREFS = "lm_widgets"
    private val KINDS = linkedMapOf("steps" to StepsWidget::class.java, "burn" to BurnWidget::class.java,
        "food" to FoodWidget::class.java, "io" to InOutWidget::class.java, "today" to InTodayWidget::class.java, "eat" to EatWidget::class.java)

    fun save(c: Context, s: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("snap", s).apply() }
    private fun snap(c: Context): JSONObject? = try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("snap", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
    private fun live(c: Context): JSONObject? = try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("live", null)?.let { JSONObject(it) } } catch (e: Exception) { null }

    fun count(c: Context): Map<String, Int> {
        val m = AppWidgetManager.getInstance(c)
        return KINDS.mapValues { (_, cls) -> try { m.getAppWidgetIds(ComponentName(c, cls)).size } catch (e: Exception) { 0 } }
    }

    suspend fun granted(c: Context): Set<String>? {
        if (HealthConnectClient.getSdkStatus(c) != HealthConnectClient.SDK_AVAILABLE) return null
        return try { HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions() } catch (e: Exception) { null }
    }

    fun schedule(c: Context, now: Boolean = false) {
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, if (now) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Today's steps, hour by hour, straight from Health Connect. */
    suspend fun readSteps(c: Context) {
        val g = granted(c) ?: return
        if (!g.contains(HealthPermission.getReadPermission(StepsRecord::class)) || !g.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)) return
        val z = ZoneId.systemDefault(); val d = LocalDate.now(z)
        val from = d.atStartOfDay(z).toInstant(); val to = d.plusDays(1).atStartOfDay(z).toInstant()
        val res = HealthConnectClient.getOrCreate(c).aggregateGroupByDuration(AggregateGroupByDurationRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = TimeRangeFilter.between(from, to), timeRangeSlicer = Duration.ofHours(1)))
        val hours = LongArray(24)
        for (r in res) { val i = Duration.between(from, r.startTime).toHours().toInt(); if (i in 0..23) hours[i] += r.result[StepsRecord.COUNT_TOTAL] ?: 0L }
        val o = JSONObject().put("date", d.toString()).put("at", System.currentTimeMillis()).put("steps", hours.sum())
            .put("hours", JSONArray().apply { hours.forEach { put(it) } })
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("live", o.toString()).apply()
    }

    fun refreshAll(c: Context) {
        val m = AppWidgetManager.getInstance(c)
        for ((k, cls) in KINDS) try { for (id in m.getAppWidgetIds(ComponentName(c, cls))) draw(c, m, id, k) } catch (e: Exception) { }
    }

    fun draw(c: Context, m: AppWidgetManager, id: Int, kind: String) {
        try {
            val o = m.getAppWidgetOptions(id)
            // on a phone held upright the widget is its narrowest width and tallest height
            var w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).toFloat()
            var h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).toFloat()
            if (w < 30 || h < 30) { w = if (kind == "io") 184f else 184f; h = 184f }
            val dm = c.resources.displayMetrics
            var k = dm.density
            val maxPx = 1_600_000f
            if (w * h * k * k > maxPx) k = kotlin.math.sqrt(maxPx / (w * h))
            val bmp = Bitmap.createBitmap((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp); cv.scale(k, k)
            val radius = if (Build.VERSION.SDK_INT >= 31) try { c.resources.getDimension(android.R.dimen.system_app_widget_background_radius) / dm.density } catch (e: Exception) { 24f } else 24f
            val md = model(c)
            val desc = Draw(cv, md, w, h, radius.coerceIn(12f, 32f)).widget(kind)
            val rv = RemoteViews(c.packageName, R.layout.lm_widget)
            rv.setImageViewBitmap(R.id.lm_img, bmp)
            rv.setContentDescription(R.id.lm_img, desc)
            rv.setOnClickPendingIntent(R.id.lm_root, open(c, kind))
            m.updateAppWidget(id, rv)
        } catch (e: Exception) { android.util.Log.w("Widgets", "draw $kind", e) }
    }

    /** Tapping a widget opens its page in the app (Steps, In & out, Food). */
    private fun open(c: Context, kind: String): PendingIntent? {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return null
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        i.putExtra("lm_log_kind", "w-$kind")
        return PendingIntent.getActivity(c, 7950 + KINDS.keys.indexOf(kind), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    class Day(val label: String, val date: String, val steps: Int, val eat: Int, val out: Int, val rest: Int, val restDay: Int, val today: Boolean, val future: Boolean) {
        val logged get() = eat > 0
    }
    class Pace(val key: String, val label: String, val color: Int)
    /** Rest of today: kcal to land mid-target (left), until the bottom of it (loLeft), over the top (over), protein to go,
     *  meals still ahead [meal, about when], and the coach's meals when up to date [meal, by, what, kcal, protein]. */
    class Rod(val left: Int, val loLeft: Int, val over: Int, val pro: Int, val done: Boolean, val closed: Boolean,
              val next: List<Pair<String, String>>, val head: String, val tip: String, val meals: List<RodMeal>)
    class RodMeal(val meal: String, val by: String, val eat: String, val kcal: Int, val pro: Int)

    class Model(
        val ok: Boolean, val accent: Int, val goal: String, val stepGoal: Int, val lo: Int, val hi: Int,
        val steps: Int, val hours: IntArray, val walkHours: Set<Int>, val km: Double, val walks: List<Pair<String, Double>>, val nowH: Int,
        val rest: Int, val restDay: Int, val walkKcal: Int, val walkN: Int, val everyday: Int, val workout: Int, val digest: Int,
        val eat: Int, val pro: Int, val carbs: Int, val fat: Int, val tPro: Int, val tCarbs: Int, val tFat: Int,
        val meals: List<Triple<String, String, Int>>, val week: List<Day>, val weekTitle: String, val rod: Rod?
    ) { val move get() = walkKcal + everyday + workout + digest; val out get() = rest + move }

    /**
     * snapshot: {at, accent, goal, stepGoal, lo, hi, kmStep, kps, restDay, tPro, tCarbs, tFat,
     *   days: {"YYYY-MM-DD": {s: steps, e: eaten, o: burned, r: resting burn for the whole day}},
     *   today: {date, steps, hours[24], walkHours[], walks[[name, km]], walkSteps, walkKcal, workout, pro, carbs, fat, meals[[meal, what, kcal or -1]]}}
     */
    fun model(c: Context): Model {
        val s = snap(c) ?: JSONObject()
        val z = ZoneId.systemDefault(); val now = LocalDateTime.now(z); val today = now.toLocalDate(); val ts = today.toString()
        val t = s.optJSONObject("today")?.takeIf { it.optString("date") == ts }
        val lv = live(c)?.takeIf { it.optString("date") == ts && it.optLong("at") > (if (t != null) s.optLong("at") else 0L) }
        val src = lv ?: t
        val hours = IntArray(24) { src?.optJSONArray("hours")?.optInt(it) ?: 0 }
        val steps = max(src?.optInt("steps") ?: 0, hours.sum())
        val walkSteps = t?.optInt("walkSteps") ?: 0
        val frac = (now.hour * 60 + now.minute) / 1440.0
        val restDay = s.optInt("restDay", 1600)
        val eat = t?.optInt("eat") ?: 0
        val walks = ArrayList<Pair<String, Double>>()
        t?.optJSONArray("walks")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { walks.add(it.optString(0) to it.optDouble(1)) } }
        val wh = HashSet<Int>(); t?.optJSONArray("walkHours")?.let { a -> for (i in 0 until a.length()) wh.add(a.optInt(i)) }
        val meals = ArrayList<Triple<String, String, Int>>()
        val ma = t?.optJSONArray("meals")
        if (ma != null) for (i in 0 until ma.length()) ma.optJSONArray(i)?.let { meals.add(Triple(it.optString(0), it.optString(1), it.optInt(2, -1))) }
        else listOf("Breakfast", "Lunch", "Snack", "Dinner").forEach { meals.add(Triple(it, "Not logged yet", -1)) }
        val kmStep = s.optDouble("kmStep", 0.00075)
        val km = max(steps * kmStep, walks.sumOf { it.second })
        val everyday = (max(0, steps - walkSteps) * s.optDouble("kps", 0.035)).roundToInt()
        val pro = t?.optInt("pro") ?: 0
        val digest = (eat * 0.1).roundToInt()
        val rest = (restDay * frac).roundToInt()
        val walkKcal = t?.optInt("walkKcal") ?: 0; val workout = t?.optInt("workout") ?: 0
        // this week, Monday to Sunday, like In & out
        val days = s.optJSONObject("days") ?: JSONObject()
        val mon = today.with(DayOfWeek.MONDAY)
        val week = (0..6).map { i ->
            val d = mon.plusDays(i.toLong()); val k = d.toString(); val dd = days.optJSONObject(k)
            val lab = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.ENGLISH)
            if (d == today) Day(lab, k, steps, eat, rest + walkKcal + everyday + workout + digest, rest, restDay, true, false)
            else Day(lab, k, dd?.optInt("s") ?: 0, dd?.optInt("e") ?: 0, dd?.optInt("o") ?: 0, dd?.optInt("r") ?: 0, dd?.optInt("r") ?: 0, false, d.isAfter(today))
        }
        val f = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val rod = t?.optJSONObject("rod")?.let { r ->
            val nx = ArrayList<Pair<String, String>>(); r.optJSONArray("next")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { nx.add(it.optString(0) to it.optString(1)) } }
            val ml = ArrayList<RodMeal>(); r.optJSONArray("meals")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { ml.add(RodMeal(it.optString(0), it.optString(1), it.optString(2), it.optInt(3), it.optInt(4))) } }
            Rod(r.optInt("left"), r.optInt("loLeft"), r.optInt("over"), r.optInt("pro"), r.optBoolean("done"), r.optBoolean("closed"), nx, r.optString("head"), r.optString("tip"), ml) }
        return Model(s.has("at"), parse(s.optString("accent"), 0xFF00FFFF.toInt()), s.optString("goal", "lose"), s.optInt("stepGoal", 10000),
            s.optInt("lo", 1700), s.optInt("hi", 1850), steps, hours, wh, km, walks, now.hour,
            rest, restDay, walkKcal, walks.size, everyday, workout, digest,
            eat, pro, t?.optInt("carbs") ?: 0, t?.optInt("fat") ?: 0, s.optInt("tPro", 100), s.optInt("tCarbs", 200), s.optInt("tFat", 70),
            meals, week, "${mon.format(f)} – ${mon.plusDays(6).format(f)}", rod)
    }

    private fun parse(h: String, d: Int) = try { if (Regex("^#[0-9a-fA-F]{6}$").matches(h)) android.graphics.Color.parseColor(h) else d } catch (e: Exception) { d }

    /** Is the gap between eaten and burned healthy? The same rules as the app's In & out page, by goal. */
    fun pace(inK: Double, outK: Double, restK: Double, goal: String, partial: Boolean): Pace {
        val net = inK - outK; val d = -net
        if (partial) return Pace("partial", "Day not over yet", C.FG3)
        if (restK > 0 && inK < restK * 0.95) return Pace("low", "Ate less than resting burn", C.ERR)
        if (goal == "gain") return if (net > 600) Pace("big", "Big surplus", C.AMBER) else if (net >= 200) Pace("ok", "Healthy surplus", C.MINT) else Pace("even", "Not enough to gain", C.SKY)
        if (goal == "maintain") return if (abs(net) <= 250) Pace("ok", "In balance", C.MINT) else if (d > 1000) Pace("toofast", "Too big a deficit", C.ERR)
            else Pace("big", if (net > 0) "Surplus" else "Deficit", C.AMBER)
        return when {
            d > 1000 -> Pace("toofast", "Too big a deficit", C.ERR)
            d > 750 -> Pace("big", "Big deficit", C.AMBER)
            d >= 250 -> Pace("ok", "Healthy deficit", C.MINT)
            d > -250 -> Pace("even", "About even", C.SKY)
            else -> Pace("surplus", "Surplus", C.PINK)
        }
    }
}

/** The app's colours (dark theme). */
object C {
    const val BG = 0xFF131B1D.toInt(); const val S3 = 0xFF1C2628.toInt(); const val S4 = 0xFF263235.toInt()
    const val FG = 0xFFEAFEFE.toInt(); const val FG2 = 0xFFA4BEC0.toInt(); const val FG3 = 0xFF6F888A.toInt(); const val LINE = 0xFF2B3B3E.toInt()
    const val MINT = 0xFF5CF2A6.toInt(); const val MINT_C = 0xFF00391F.toInt()
    const val AMBER = 0xFFFFC247.toInt(); const val AMBER_C = 0xFF463000.toInt(); const val ON_AMBER_C = 0xFFFFE5B0.toInt(); const val REST = 0xFF8A6A2C.toInt()
    const val VIOLET = 0xFFB9A2FF.toInt(); const val PINK = 0xFFFF6FD1.toInt(); const val ERR = 0xFFFF6B6B.toInt(); const val SKY = 0xFF7AB8FF.toInt()
    const val BAR = 0xFF3A5154.toInt()
    /** a of colour x over black (the app's color-mix(x, #000)) */
    fun dim(x: Int, a: Double): Int {
        val r = ((x shr 16 and 255) * a).roundToInt(); val g = ((x shr 8 and 255) * a).roundToInt(); val b = ((x and 255) * a).roundToInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Draws one widget at its size (in dp). Layouts follow the mockups; sizes in between stretch the nearest one. */
class Draw(private val cv: Canvas, private val m: Widgets.Model, private val W0: Float, private val H0: Float, private val radius: Float) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var W = W0; private var H = H0
    private val acc = m.accent; private val accC = C.dim(m.accent, 0.3)

    private fun face(w: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, w, false)
        else Typeface.create(Typeface.SANS_SERIF, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
    private val faces = HashMap<Int, Typeface>()
    private fun tf(w: Int) = faces.getOrPut(w) { face(w) }

    private fun fmt(n: Int) = String.format(Locale.US, "%,d", n)
    /** 6,240 → 6.2k (rounded down, so it never shows more than you've done) */
    private fun short(n: Int) = if (n < 1000) n.toString() else { val t = floor(n / 100.0) / 10; if (t >= 100) "${t.toInt()}k" else String.format(Locale.US, "%.1fk", t) }

    // ── primitives ──
    private fun fill(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; p.pathEffect = null; cv.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p) }
    private fun width(s: String, size: Float, wt: Int): Float { p.typeface = tf(wt); p.textSize = size; return p.measureText(s) }
    private fun text(s: String, x: Float, y: Float, size: Float, color: Int, wt: Int = 720, align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f): Float {
        var sz = size
        if (maxW > 0) while (sz > 7 && width(s, sz, wt) > maxW) sz -= 0.5f
        var str = s
        if (maxW > 0 && width(str, sz, wt) > maxW) { while (str.length > 1 && width("$str…", sz, wt) > maxW) str = str.dropLast(1); str = "$str…" }
        p.style = Paint.Style.FILL; p.color = color; p.typeface = tf(wt); p.textSize = sz; p.textAlign = align; p.pathEffect = null
        cv.drawText(str, x, y, p); p.textAlign = Paint.Align.LEFT
        return p.measureText(str)
    }
    private fun num(s: String, x: Float, y: Float, size: Float, color: Int = C.FG, align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f) = text(s, x, y, size, color, 850, align, maxW)
    private fun arc(cx: Float, cy: Float, r: Float, f0: Double, f1: Double, color: Int, sw: Float) {
        if (f1 - f0 <= 0.002) return
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.ROUND; p.color = color; p.pathEffect = null
        cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), (-90 + f0 * 360).toFloat(), (min(f1 - f0, 0.999) * 360).toFloat(), false, p)
    }
    /** Material 3 Expressive wavy progress ring, with a gap before the flat track */
    private fun ring(cx: Float, cy: Float, r: Float, f: Double, color: Int, sw: Float, wavy: Boolean) {
        val gap = if (f >= 0.999 || f <= 0.001) 0.0 else 0.045
        arc(cx, cy, r, if (f <= 0.001) 0.0 else f + gap, if (f <= 0.001) 1.0 else 1 - gap, C.S4, sw)
        if (f <= 0.001) return
        if (!wavy) { arc(cx, cy, r, 0.0, f, color, sw); return }
        val path = Path(); val n = max(2, (f * 260).toInt()); val amp = r * 0.045f; val waves = 11
        for (i in 0..n) { val u = i.toDouble() / n * f; val t = -PI / 2 + u * 2 * PI; val rr = r + amp * sin(waves * u * 2 * PI)
            val x = (cx + rr * cos(t)).toFloat(); val y = (cy + rr * sin(t)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.color = color; p.pathEffect = null
        cv.drawPath(path, p)
    }
    /** The app's expressive shapes: polar "cookie" (n bumps) and "flower" outlines */
    private fun shape(cx: Float, cy: Float, R: Float, n: Int, a: Double, color: Int) {
        val path = Path()
        for (i in 0..120) { val t = i * 2 * PI / 120; val r = R * (1 + a * cos(n * t)) / (1 + a)
            val x = (cx + r * cos(t)).toFloat(); val y = (cy + r * sin(t)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close(); p.style = Paint.Style.FILL; p.color = color; p.pathEffect = null; cv.drawPath(path, p)
    }
    private fun dot(x: Float, y: Float, r: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; cv.drawCircle(x, y, r, p) }
    private fun dash(x0: Float, x1: Float, y: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = color; p.strokeCap = Paint.Cap.BUTT; p.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        cv.drawLine(x0, y, x1, y, p); p.pathEffect = null
    }
    private fun card() = fill(0f, 0f, W, H, radius, C.BG)
    /** Lays the content out in a box of about ref size: scales it down a little or up, keeping proportions. */
    private fun fit(refW: Float, refH: Float) {
        val s = min(W0 / refW, H0 / refH).coerceIn(0.8f, 1.3f)
        cv.scale(s, s); W = W0 / s; H = H0 / s
    }

    fun widget(kind: String): String {
        if (!m.ok) { card(); text("Open Fitness Tracker", W / 2, H / 2 - 2, 13f, C.FG, 820, Paint.Align.CENTER, W - 20)
            text("once to set up", W / 2, H / 2 + 16, 12f, C.FG2, 720, Paint.Align.CENTER, W - 20); return "Open Fitness Tracker once to set up this widget" }
        val size = when { W0 < 150 && H0 < 150 -> "1"; H0 < 130 -> "w"; W0 < 260 -> "s"; H0 < 270 -> "m"; else -> "l" }
        return when (kind) { "steps" -> steps(size); "burn" -> burn(size); "food" -> food(size); "today" -> today(size); "eat" -> eat(size); else -> io(if (size == "1" || size == "w") "s" else size) }
    }

    // ═══ STEPS ═══
    private fun steps(size: String): String {
        val f = min(1.0, m.steps.toDouble() / m.stepGoal); val met = m.steps >= m.stepGoal; val col = if (met) C.MINT else acc
        val left = max(0, m.stepGoal - m.steps)
        val sub = if (met) "Goal reached" else "${(f * 100).toInt()}% · ${fmt(left)} to go"
        val walksTxt = "${String.format(Locale.US, "%.1f", m.km)} km · ${m.walkN} walk${if (m.walkN == 1) "" else "s"}"
        when (size) {
            "1" -> { val R = min(W, H) / 2 - 1; val cx = W / 2; val cy = H / 2
                shape(cx, cy, R, 12, 0.05, C.BG); ring(cx, cy, R * 0.68f, f, col, R * 0.11f, false)
                num(short(m.steps), cx, cy + R * 0.12f, R * 0.46f, C.FG, Paint.Align.CENTER, R * 1.1f)
                text("steps", cx, cy + R * 0.38f, R * 0.22f, C.FG2, 720, Paint.Align.CENTER) }
            "w" -> { fit(184f, 88f); card(); val d = min(H - 28, 64f); val cx = 12 + d / 2; val cy = H / 2
                shape(cx, cy, d * 0.25f, 9, 0.08, accC); ring(cx, cy, d / 2 - 3, f, col, 5f, false)
                val x = 12 + d + 10; num(fmt(m.steps), x, cy + 2, 26f, C.FG, maxW = W - x - 12); text(sub, x, cy + 20, 11.5f, C.FG2, maxW = W - x - 12) }
            "s" -> { fit(184f, 184f); card(); val d = min(W - 40, H - 52); val cx = W / 2; val cy = 10 + d / 2 + (H - 52 - d) / 2
                ring(cx, cy, d / 2 - 4, f, col, 7f * d / 128, true)
                num(fmt(m.steps), cx, cy + 6, 30f * d / 128, C.FG, Paint.Align.CENTER, d - 30); text("of ${fmt(m.stepGoal)}", cx, cy + 6 + 18 * d / 128, 11f, C.FG2, align = Paint.Align.CENTER)
                text(walksTxt, cx, H - 16, 12f, C.FG2, align = Paint.Align.CENTER, maxW = W - 20) }
            "m" -> { fit(376f, 184f); card(); val d = min(H - 40, 150f); val cx = 14 + d / 2; val cy = H / 2
                ring(cx, cy, d / 2 - 4, f, col, 7f, true)
                num(fmt(m.steps), cx, cy + 6, 30f * d / 128, C.FG, Paint.Align.CENTER, d - 30); text("of ${fmt(m.stepGoal)}", cx, cy + 6 + 18 * d / 128, 11f, C.FG2, align = Paint.Align.CENTER)
                val x = 14 + d + 14; val cw = W - x - 16
                text("Today", x, 30f, 13f, C.FG, 820); text(walksTxt, W - 16, 30f, 11.5f, C.FG2, align = Paint.Align.RIGHT)
                stepBars(x, 44f, cw, H - 44 - 34); hourLabels(x, H - 18, cw, false) }
            else -> { fit(376f, 376f); card(); val pad = 18f; val d = 120f; val cx = pad + d / 2; val cy = pad + d / 2
                ring(cx, cy, d / 2 - 4, f, col, 7f, true)
                num(fmt(m.steps), cx, cy + 6, 28f, C.FG, Paint.Align.CENTER, d - 26); text("of ${fmt(m.stepGoal)}", cx, cy + 22, 11f, C.FG2, align = Paint.Align.CENTER)
                val x = pad + d + 16; var y = pad + 26
                text(if (met) "Goal reached" else "${fmt(left)} to go", x, y, 15f, if (met) C.MINT else C.FG, 850, maxW = W - x - pad)
                for (w in m.walks.take(2)) { y += 22; dot(x + 4, y - 4, 4f, C.MINT); text("${w.first} · ${String.format(Locale.US, "%.1f", w.second)} km", x + 14, y, 12.5f, C.FG, maxW = W - x - pad - 14) }
                y += 22; text("${String.format(Locale.US, "%.1f", m.km)} km in all today", x, y, 12f, C.FG2, maxW = W - x - pad)
                var top = pad + d + 16
                val weekH = 70f; val room = H - pad - top
                val chartH = if (room > 58 + 16 + 14 + weekH) 58f else min(58f, room - 18)
                stepBars(pad, top, W - 2 * pad, chartH); hourLabels(pad, top + chartH + 14, W - 2 * pad, true); top += chartH + 26
                if (H - pad - top >= weekH) { fill(pad, top, W - 2 * pad, 1f, 0f, C.LINE); weekRings(pad, H - pad - 62, W - 2 * pad) }
            }
        }
        return "Steps: ${fmt(m.steps)} of ${fmt(m.stepGoal)}"
    }
    private fun stepBars(x: Float, y: Float, w: Float, h: Float) {
        val mx = max(1, m.hours.maxOrNull() ?: 1); val step = w / 24; val bw = step * 0.62f
        for (i in 0..23) { val fut = i > m.nowH; val bh = if (fut) 3f else max(3f, m.hours[i].toFloat() / mx * h)
            fill(x + i * step, y + h - bh, bw, bh, min(bw / 2, 3f), when { i == m.nowH -> acc; i in m.walkHours -> C.MINT; fut -> C.S3; else -> C.BAR }) }
    }
    private fun hourLabels(x: Float, y: Float, w: Float, end: Boolean) {
        val l = if (end) listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM") else listOf("12 AM", "6 AM", "12 PM", "6 PM")
        val step = w / 24
        l.forEachIndexed { i, s -> val px = x + i * 6 * step; text(s, if (end && i == 4) x + w else px, y, 10f, C.FG2, align = if (end && i == 4) Paint.Align.RIGHT else Paint.Align.LEFT) }
    }
    private fun weekRings(x: Float, y: Float, w: Float) {
        val slot = w / 7
        m.week.forEachIndexed { i, d -> val cx = x + slot * i + slot / 2; val met = d.steps >= m.stepGoal
            val col = if (d.today) acc else if (met) C.MINT else C.FG3
            arc(cx, y + 17, 13f, 0.0, 1.0, C.S4, 4f); if (!d.future && d.steps > 0) arc(cx, y + 17, 13f, 0.0, min(1.0, d.steps.toDouble() / m.stepGoal), col, 4f)
            text(d.label, cx, y + 47, 10.5f, C.FG2, align = Paint.Align.CENTER)
            text(if (d.future) "" else short(d.steps), cx, y + 61, 11f, if (d.today) acc else if (met) C.MINT else C.FG2, 800, Paint.Align.CENTER) }
    }

    // ═══ CALORIES BURNED ═══
    private fun burn(size: String): String {
        val total = m.out; val more = max(0, m.restDay - m.rest)
        val moreTxt = "+ about ${fmt((more / 10.0).roundToInt() * 10)} more at rest by midnight"
        when (size) {
            "1" -> { val R = min(W, H) / 2 - 1; val cx = W / 2; val cy = H / 2
                shape(cx, cy, R, 8, 0.12, C.AMBER_C)
                num(short(total), cx, cy + R * 0.1f, R * 0.46f, C.ON_AMBER_C, Paint.Align.CENTER, R * 1.3f)
                text("kcal out", cx, cy + R * 0.38f, R * 0.22f, C.AMBER, 720, Paint.Align.CENTER) }
            "w" -> { fit(184f, 88f); card(); val d = min(H - 36, 56f); val cx = 12 + d / 2; val cy = H / 2
                shape(cx, cy, d / 2, 8, 0.12, C.AMBER_C); text("🔥", cx, cy + d * 0.17f, d * 0.42f, C.AMBER, align = Paint.Align.CENTER)
                val x = 12 + d + 10; num(fmt(total), x, cy, 26f, maxW = W - x - 14); split(x, cy + 10, W - x - 14, 6f) }
            "s" -> { fit(184f, 184f); card(); val pad = 16f
                shape(pad + 15, pad + 15, 15f, 8, 0.12, C.AMBER_C); text("🔥", pad + 15, pad + 20, 13f, C.AMBER, align = Paint.Align.CENTER)
                text("Burned", pad + 38, pad + 20, 13f, C.FG, 820)
                val y = pad + 30 + (H - 184) / 2 + 40
                val w = num(fmt(total), pad, y, 36f, maxW = W - 2 * pad - 30); text("kcal", pad + w + 5, y, 12f, C.FG2)
                split(pad, y + 12, W - 2 * pad, 12f)
                legend(pad, y + 42, C.REST, "${fmt(m.rest)} resting"); legend(pad, y + 60, C.AMBER, "${fmt(m.move)} moving") }
            "m" -> { fit(376f, 184f); card(); val pad = 16f; val lw = 132f
                text("Burned today", pad, pad + 13, 13f, C.FG, 820)
                val w = num(fmt(total), pad, pad + 52, 34f, maxW = lw - 30); text("kcal", pad + w + 4, pad + 52, 12f, C.FG2)
                legend(pad, pad + 76, C.REST, "${fmt(m.rest)} resting"); legend(pad, pad + 94, C.AMBER, "${fmt(m.move)} moving")
                wrap(moreTxt, pad, pad + 118, lw, 11f, C.FG2, 2)
                val x = pad + lw + 16; burnBars(x, pad, W - x - pad, H - 2 * pad - 18); hourLabels(x, H - pad, W - x - pad, false) }
            else -> { fit(376f, 376f); card(); val pad = 18f
                shape(pad + 22, pad + 22, 22f, 8, 0.12, C.AMBER_C); text("🔥", pad + 22, pad + 29, 19f, C.AMBER, align = Paint.Align.CENTER)
                val nw = width(fmt(total), 32f, 850); val kw = width("kcal", 12f, 720)
                num(fmt(total), W - pad - kw - 4, pad + 34, 32f, align = Paint.Align.RIGHT); text("kcal", W - pad, pad + 34, 12f, C.FG2, align = Paint.Align.RIGHT)
                text("Burned today", pad + 56, pad + 18, 14f, C.FG, 820, maxW = W - pad * 2 - 56 - nw - kw - 8)
                text(moreTxt, pad + 56, pad + 36, 11.5f, C.FG2, maxW = W - pad * 2 - 56 - nw - kw - 8)
                var top = pad + 60
                burnBars(pad, top, W - 2 * pad, 64f); hourLabels(pad, top + 78, W - 2 * pad, true); top += 92
                val parts = listOf(Triple("Resting", m.rest, C.REST), Triple("Walks · ${m.walkN}", m.walkKcal, C.AMBER), Triple("Everyday steps", m.everyday, C.AMBER),
                    Triple("Workout", m.workout, if (m.workout > 0) C.AMBER else C.FG3), Triple("Digesting", m.digest, C.AMBER))
                val cw = (W - 2 * pad - 6) / 2; val rh = 28f
                parts.forEachIndexed { i, (l, v, c) -> val cx = pad + (i % 2) * (cw + 6); val cy = top + (i / 2) * (rh + 6)
                    fill(cx, cy, cw, rh, 14f, C.S3); fill(cx + 10, cy + rh / 2 - 4.5f, 9f, 9f, 3f, c)
                    text(l, cx + 26, cy + rh / 2 + 4, 11.5f, C.FG2, maxW = cw - 26 - 46); num(if (v > 0 || l == "Resting") fmt(v) else "—", cx + cw - 10, cy + rh / 2 + 5, 14f, align = Paint.Align.RIGHT) }
                top += 3 * (rh + 6) + 4
                if (H - pad - top >= 72) { fill(pad, top, W - 2 * pad, 1f, 0f, C.LINE); weekBars(pad, H - pad - 70, W - 2 * pad) }
            }
        }
        return "Calories burned today: ${fmt(total)}, ${fmt(m.rest)} resting and ${fmt(m.move)} moving"
    }
    private fun split(x: Float, y: Float, w: Float, h: Float) {
        val tot = max(1, m.out); val rw = (w - 2) * m.rest / tot
        fill(x, y, w, h, h / 2, C.S4); fill(x, y, max(h, rw), h, h / 2, C.REST)
        if (m.move > 0) fill(x + rw + 2, y, max(h, w - rw - 2), h, h / 2, C.AMBER)
    }
    private fun legend(x: Float, y: Float, c: Int, s: String) { fill(x, y - 8.5f, 9f, 9f, 3f, c); text(s, x + 15, y, 11.5f, C.FG, 720) }
    private fun wrap(s: String, x: Float, y: Float, w: Float, size: Float, color: Int, lines: Int) {
        val words = s.split(" "); var line = ""; var yy = y; var n = 0
        for (wd in words) { val t = if (line.isEmpty()) wd else "$line $wd"
            if (width(t, size, 720) > w && line.isNotEmpty()) { text(line, x, yy, size, color); yy += size * 1.35f; line = wd; n++; if (n >= lines) return } else line = t }
        if (line.isNotEmpty()) text(line, x, yy, size, color, maxW = w)
    }
    private fun burnBars(x: Float, y: Float, w: Float, h: Float) {
        val restH = m.restDay / 24.0; val perStep = if (m.steps > 0) (m.walkKcal + m.everyday).toDouble() / m.steps else 0.0
        val moves = DoubleArray(24) { m.hours[it] * perStep }; val top = restH + max(1.0, moves.maxOrNull() ?: 1.0)
        val step = w / 24; val bw = step * 0.62f
        for (i in 0..23) { val fut = i > m.nowH; val rh = (restH / top * h).toFloat(); fill(x + i * step, y + h - rh, bw, rh, 2f, if (fut) 0xFF2A2416.toInt() else C.REST)
            if (!fut && moves[i] > 0.5) { val mh = (moves[i] / top * h).toFloat(); fill(x + i * step, y + h - rh - mh - 1.5f, bw, mh, 2f, C.AMBER) } }
    }
    private fun weekBars(x: Float, y: Float, w: Float) {
        val slot = w / 7; val mx = max(1, m.week.maxOf { it.out })
        m.week.forEachIndexed { i, d -> val cx = x + slot * i + slot / 2
            if (!d.future && d.out > 0) { val bh = 40f * d.out / mx; text(short(d.out), cx, y + 10, 10f, C.FG2, 780, Paint.Align.CENTER); fill(cx - 9, y + 54 - bh, 18f, bh, 6f, if (d.today) C.AMBER else C.REST) }
            text(d.label, cx, y + 68, 10.5f, if (d.today) C.AMBER else C.FG2, 780, Paint.Align.CENTER) }
    }

    // ═══ CALORIES EATEN ═══
    private fun food(size: String): String {
        val f = min(1.0, m.eat.toDouble() / m.lo); val over = m.eat > m.hi; val inRange = m.eat >= m.lo && !over
        val col = if (over) C.ERR else if (inRange) C.MINT else acc
        val status = when { m.eat == 0 -> "Nothing logged yet"; m.eat < m.lo -> "${fmt(m.lo - m.eat)} to go"; inRange -> "In range"; else -> "${fmt(m.eat - m.hi)} over" }
        val range = "${fmt(m.lo)}–${fmt(m.hi)}"
        when (size) {
            "1" -> { val R = min(W, H) / 2 - 1; val cx = W / 2; val cy = H / 2
                shape(cx, cy, R, 9, 0.07, C.BG); ring(cx, cy, R * 0.68f, f, col, R * 0.11f, false)
                num(short(m.eat), cx, cy + R * 0.12f, R * 0.46f, C.FG, Paint.Align.CENTER, R * 1.1f)
                text("of ${short(m.lo)}", cx, cy + R * 0.38f, R * 0.22f, C.FG2, 720, Paint.Align.CENTER) }
            "w" -> { fit(184f, 88f); card(); val d = min(H - 28, 64f); val cx = 12 + d / 2; val cy = H / 2
                shape(cx, cy, d * 0.25f, 9, 0.08, accC); ring(cx, cy, d / 2 - 3, f, col, 5f, false)
                val x = 12 + d + 10; num(fmt(m.eat), x, cy + 2, 26f, maxW = W - x - 12); text("kcal · $status", x, cy + 20, 11.5f, C.FG2, maxW = W - x - 12) }
            "s" -> { fit(184f, 184f); card(); val d = min(W - 40, H - 52); val cx = W / 2; val cy = 10 + d / 2 + (H - 52 - d) / 2
                ring(cx, cy, d / 2 - 5, f, col, 9f * d / 128, false)
                num(fmt(m.eat), cx, cy + 6, 30f * d / 128, C.FG, Paint.Align.CENTER, d - 30); text("of $range", cx, cy + 6 + 18 * d / 128, 11f, C.FG2, align = Paint.Align.CENTER, maxW = d - 30)
                text(status, cx, H - 16, 12f, if (inRange) C.MINT else if (over) C.ERR else C.FG2, align = Paint.Align.CENTER, maxW = W - 20) }
            "m" -> { fit(376f, 184f); card(); val d = min(H - 40, 150f); val cx = 14 + d / 2; val cy = H / 2
                ring(cx, cy, d / 2 - 5, f, col, 9f, false)
                num(fmt(m.eat), cx, cy + 6, 30f * d / 128, C.FG, Paint.Align.CENTER, d - 30); text(status, cx, cy + 6 + 18 * d / 128, 11f, C.FG2, align = Paint.Align.CENTER, maxW = d - 24)
                val x = 14 + d + 16; val cw = W - x - 18; val gap = (H - 3 * 26) / 4
                macros().forEachIndexed { i, mc -> macroBar(mc, x, gap + i * (26 + gap), cw, 8f) } }
            else -> { fit(376f, 376f); card(); val pad = 18f; val d = 96f; val cx = pad + d / 2; val cy = pad + d / 2
                ring(cx, cy, d / 2 - 6, f, col, 8f, false); num("${(m.eat * 100.0 / m.lo).toInt()}%", cx, cy + 5, 15f, C.FG2, Paint.Align.CENTER)
                val x = pad + d + 16
                text("Eaten today", x, pad + 22, 14f, C.FG, 820)
                val w = num(fmt(m.eat), x, pad + 62, 36f, maxW = W - x - pad - 80); text("/ $range kcal", x + w + 5, pad + 62, 12f, C.FG2, maxW = W - x - w - 5 - pad)
                text(status, x, pad + 84, 12f, if (inRange) C.MINT else if (over) C.ERR else C.FG2)
                var top = pad + d + 16; val cw = (W - 2 * pad - 20) / 3
                macros().forEachIndexed { i, mc -> val mx = pad + i * (cw + 10)
                    text(mc.l, mx, top + 12, 11.5f, mc.c, 800)
                    val vw = num(fmt(mc.v), mx, top + 32, 15f); text(" / ${mc.t} g", mx + vw, top + 32, 11.5f, C.FG2, maxW = cw - vw)
                    fill(mx, top + 40, cw, 6f, 3f, C.S4); fill(mx, top + 40, max(6f, cw * min(1f, mc.v.toFloat() / max(1, mc.t))), 6f, 3f, mc.c) }
                top += 62; fill(pad, top, W - 2 * pad, 1f, 0f, C.LINE); top += 8
                val rh = min(26f, (H - pad - top) / max(1, m.meals.size))
                m.meals.forEachIndexed { i, (l, what, k) -> val y = top + i * rh + rh / 2 + 5; val none = k < 0
                    text(l, pad, y, 12.5f, if (none) C.FG3 else C.FG, 820, maxW = 70f)
                    text(what, pad + 78, y, 12f, C.FG2, 650, maxW = W - 2 * pad - 78 - 50)
                    num(if (none) "—" else fmt(k), W - pad, y + 1, 14f, if (none) C.FG3 else C.FG, Paint.Align.RIGHT) }
            }
        }
        return "Calories eaten today: ${fmt(m.eat)} of $range, $status"
    }
    private class Mac(val l: String, val v: Int, val t: Int, val c: Int)
    private fun macros() = listOf(Mac("Protein", m.pro, m.tPro, C.VIOLET), Mac("Carbs", m.carbs, m.tCarbs, C.AMBER), Mac("Fat", m.fat, m.tFat, C.PINK))
    private fun macroBar(mc: Mac, x: Float, y: Float, w: Float, h: Float) {
        text(mc.l, x, y + 12, 12.5f, mc.c, 800)
        val tw = width(" / ${mc.t} g", 12f, 720); text(" / ${mc.t} g", x + w, y + 12, 12f, C.FG2, align = Paint.Align.RIGHT); num(fmt(mc.v), x + w - tw, y + 12, 13f, align = Paint.Align.RIGHT)
        fill(x, y + 18, w, h, h / 2, C.S4); fill(x, y + 18, max(h, w * min(1f, mc.v.toFloat() / max(1, mc.t))), h, h / 2, mc.c)
    }

    // ═══ IN & OUT (this week, like the In & out page) ═══
    private fun io(size: String): String {
        val goal = m.goal
        val logged = m.week.filter { it.logged && !it.future }
        val full = logged.filter { !it.today }
        val avgIn = if (logged.isEmpty()) 0.0 else logged.sumOf { it.eat }.toDouble() / logged.size
        val avgOut = if (logged.isEmpty()) 0.0 else logged.sumOf { it.out }.toDouble() / logged.size
        val pc = if (full.isEmpty()) Widgets.Pace("partial", "Only today so far", C.FG3)
            else Widgets.pace(full.sumOf { it.eat }.toDouble() / full.size, full.sumOf { it.out }.toDouble() / full.size, full.sumOf { it.restDay }.toDouble() / full.size, goal, false)
        val kgWk = if (full.isEmpty()) 0.0 else (full.sumOf { it.out } - full.sumOf { it.eat }).toDouble() / full.size * 7 / 7700
        val paceTxt = if (full.isEmpty()) "pace shows after a full day" else if (abs(kgWk) < 0.05) "about even" else "${String.format(Locale.US, "%.2f", abs(kgWk))} kg a week ${if (kgWk > 0) "down" else "up"}"
        val net = avgOut - avgIn; val netL = if (net >= 0) "Deficit" else "Surplus"; val netC = if (pc.key == "partial") (if (net >= 0) C.MINT else C.PINK) else pc.color
        if (logged.isEmpty()) {
            fit(184f, 184f); card(); text("In & out · this week", 16f, 28f, 12.5f, C.FG, 820)
            wrap("No food logged this week yet. Days count once you log what you eat.", 16f, 60f, W - 32, 12.5f, C.FG2, 4)
            return "In and out this week: nothing logged yet"
        }
        when (size) {
            "s" -> { fit(184f, 184f); card(); val pad = 16f
                text("In & out · this week", pad, pad + 10, 12.5f, C.FG, 820, maxW = W - 2 * pad)
                val ch = H - 2 * pad - 20 - 40; pairs(pad, pad + 20, W - 2 * pad, ch, 8f, 2f, false)
                val y = H - pad - 6; val nw = num(fmt(abs(net).roundToInt()), pad, y, 28f, netC)
                text("kcal ${netL.lowercase()} a day", pad + nw + 10, y - 15, 10.5f, C.FG2, maxW = W - 2 * pad - nw - 10)
                dot(pad + nw + 14, y - 3, 4.5f, pc.color); text(shortPace(pc), pad + nw + 22, y + 1, 11.5f, pc.color, 820, maxW = W - 2 * pad - nw - 22) }
            "m" -> { fit(376f, 184f); card(); val pad = 15f; val cw = min(W * 0.55f, W - 2 * pad - 150)
                text("In & out · this week", pad, pad + 10, 12.5f, C.FG, 820)
                pairs(pad, pad + 22, cw, H - 2 * pad - 22 - 14, 10f, 2f, true)
                val x = pad + cw + 16; val rw = W - x - pad; var y = (H - 120) / 2 + 14
                for ((l, v, c) in listOf(Triple("Eaten a day", avgIn, acc), Triple("Burned a day", avgOut, C.AMBER), Triple("$netL a day", abs(net), netC))) {
                    text(l, x, y, 11.5f, C.FG2); num(fmt(v.roundToInt()), x + rw, y + 1, 17f, c, Paint.Align.RIGHT); y += 26 }
                fill(x, y - 14, rw, 1f, 0f, C.LINE)
                val lw = text(shortPace(pc), x, y + 6, 12f, pc.color, 820, maxW = rw); text(" · $paceTxt", x + lw, y + 6, 12f, C.FG2, maxW = rw - lw) }
            else -> { fit(376f, 376f); card(); val pad = 17f
                text("In & out", pad, pad + 14, 14f, C.FG, 820); text(m.weekTitle, W - pad, pad + 14, 11.5f, C.FG2, align = Paint.Align.RIGHT)
                val extra = H - 376; pairs(pad, pad + 26, W - 2 * pad, 108f + max(0f, extra * 0.6f), 17f, 3f, true, letters = true)
                var y = pad + 26 + 108 + max(0f, extra * 0.6f) + 32
                // legend
                val items = listOf(Triple("Eaten", acc, 0), Triple("At rest", C.REST, 0), Triple("Moving", C.AMBER, 0), Triple("Resting burn", C.VIOLET, 1))
                var lx = (W - items.sumOf { (l, _, _) -> (width(l, 10.5f, 720) + 28).toDouble() }.toFloat()) / 2
                for ((l, c, k) in items) { if (k == 1) dash(lx, lx + 12, y - 4, c) else fill(lx, y - 8.5f, 9f, 9f, 3f, c); lx += 15 + text(l, lx + 15, y, 10.5f, C.FG2) + 13 }
                y += 16; val cw = (W - 2 * pad) / 3
                for ((i, t) in listOf(Triple("Eaten a day", avgIn, acc), Triple("Burned a day", avgOut, C.AMBER), Triple("$netL a day", abs(net), netC)).withIndex()) {
                    val cx = pad + cw * i + cw / 2; text(t.first, cx, y + 12, 11f, C.FG2, align = Paint.Align.CENTER); num(fmt(t.second.roundToInt()), cx, y + 38, 22f, t.third, Paint.Align.CENTER) }
                y += 52; val ch = H - pad - y
                fill(pad, y, W - 2 * pad, ch, 20f, C.S3)
                shape(pad + 31, y + ch / 2, 19f, 9, 0.07, C.dim(pc.color, 0.25)); text(if (pc.key == "ok") "✓" else if (pc.key == "partial") "…" else "!", pad + 31, y + ch / 2 + 6, 17f, pc.color, 900, Paint.Align.CENTER)
                val tx = pad + 62; val tw = W - pad - 12 - tx
                val lw = text(pc.label, tx, y + ch / 2 - 3, 13f, pc.color, 850, maxW = tw); text(" · $paceTxt", tx + lw, y + ch / 2 - 3, 13f, C.FG, 850, maxW = tw - lw)
                val td = m.week.firstOrNull { it.today }
                if (td != null) text("Today so far: ate ${fmt(td.eat)} · burned ${fmt(td.out)}", tx, y + ch / 2 + 15, 11f, C.FG2, maxW = tw) }
        }
        return "In and out this week: ate ${fmt(avgIn.roundToInt())} and burned ${fmt(avgOut.roundToInt())} kcal a day, ${pc.label}"
    }
    private fun shortPace(pc: Widgets.Pace) = when (pc.key) { "ok" -> pc.label.substringBefore(" ").let { if (it == "In") "In balance" else it }; "partial" -> "Today only"; else -> pc.label }
    /** Eaten next to burned (resting + moving) for each day of the week, with the eating target and resting line. */
    private fun pairs(x: Float, y: Float, w: Float, h: Float, bw: Float, gap: Float, dots: Boolean, letters: Boolean = false) {
        val foot = (if (dots) 12f else 0f) + (if (letters) 16f else 0f); val ch = h - foot
        val top = max(2500.0, (m.week.maxOfOrNull { max(it.eat, it.out) } ?: 0) * 1.05)
        val yv = { v: Double -> (y + ch - v / top * ch).toFloat() }
        fill(x, yv(m.hi.toDouble()), w, yv(m.lo.toDouble()) - yv(m.hi.toDouble()), 3f, C.dim(acc, 0.22))
        val slot = w / 7; val r = min(bw / 2, 5f)
        m.week.forEachIndexed { i, d -> val c = x + slot * i + slot / 2
            if (!d.future) {
                val low = !d.today && d.logged && d.eat < d.restDay * 0.95
                if (d.eat > 0) fill(c - gap / 2 - bw, yv(d.eat.toDouble()), bw, (y + ch) - yv(d.eat.toDouble()), r, if (low) C.ERR else if (d.today) C.dim(acc, 0.5) else acc)
                if (d.out > 0) { val rest = min(d.rest, d.out); val ry = yv(rest.toDouble()); fill(c + gap / 2, ry, bw, (y + ch) - ry, r, C.REST)
                    val mv = d.out - rest; if (mv > 0) { val my = yv(d.out.toDouble()); fill(c + gap / 2, my - 1.5f, bw, ry - my, r, C.AMBER) } }
                if (dots && d.logged) dot(c, y + ch + 7, 3.2f, Widgets.pace(d.eat.toDouble(), d.out.toDouble(), d.restDay.toDouble(), m.goal, d.today).color)
            }
            if (letters) text(d.label, c, y + h - 2, 10.5f, if (d.today) acc else C.FG2, 780, Paint.Align.CENTER) }
        val logged = m.week.filter { it.logged && !it.future }
        if (logged.isNotEmpty()) dash(x, x + w, yv(logged.sumOf { it.restDay }.toDouble() / logged.size), C.VIOLET)
    }

    // ═══ IN & OUT · TODAY ═══
    /** a horizontal bar of coloured parts (value, colour), on a track, scaled to top */
    private fun hbar(x: Float, y: Float, w: Float, h: Float, parts: List<Pair<Int, Int>>, top: Double, band: Pair<Int, Int>? = null) {
        fill(x, y, w, h, h / 2, C.S4)
        if (band != null) { val a = (band.first / top * w).toFloat(); val b = (band.second / top * w).toFloat(); fill(x + a, y - 2, max(2f, b - a), h + 4, 3f, C.dim(acc, 0.28)) }
        var cx = x; val gap = 2f
        val vis = parts.filter { it.first > 0 }
        vis.forEachIndexed { i, (v, c) -> val pw = (v / top * w).toFloat(); if (pw < 0.5f) return@forEachIndexed
            fill(cx, y, max(h * 0.6f, pw - (if (i < vis.size - 1) gap else 0f)), h, h / 2, c); cx += pw }
    }
    private val mealCols = listOf(C.AMBER, C.MINT, C.PINK, C.SKY)
    private fun inParts(): List<Pair<Int, Int>> { val ps = m.meals.mapIndexedNotNull { i, (_, _, k) -> if (k > 0) k to mealCols[i % 4] else null }
        val s = ps.sumOf { it.first }; return if (s > 0) ps + listOf(max(0, m.eat - s) to acc) else listOf(m.eat to acc) }
    private fun outParts() = listOf(m.rest to C.VIOLET, m.walkKcal to C.MINT, m.everyday to C.SKY, m.workout to C.AMBER, m.digest to C.PINK)
    private fun leftTxt(): String { val r = m.rod
        return when { r == null -> if (m.eat < m.lo) "Eat ~${fmt((m.lo + m.hi) / 2 - m.eat)} more" else if (m.eat > m.hi) "${fmt(m.eat - m.hi)} over target" else "In range"
            r.over > 0 -> "${fmt(r.over)} kcal over"; r.loLeft > 0 -> "Eat ~${fmt(r.left)} more"; else -> "In range" } }
    private fun proTxt(): String { val r = m.rod ?: return ""; return if (r.pro > 0) "${r.pro} g protein to go" else "Protein done ✓" }
    private fun today(size: String): String {
        val net = m.out - m.eat; val netL = if (net >= 0) "deficit" else "surplus"; val netC = if (net >= 0) (if (net > 1000) C.AMBER else C.MINT) else C.PINK
        val top = max(max(m.out, m.eat), m.hi).toDouble() * 1.06
        val desc = "In and out today: ate ${fmt(m.eat)}, burned ${fmt(m.out)} kcal, ${fmt(abs(net))} $netL. ${leftTxt()}"
        when (size) {
            "1" -> { fit(120f, 120f); card(); val pad = 12f; val bw = W - 2 * pad
                text("Today", pad, pad + 10, 11f, C.FG2, 780)
                hbar(pad, pad + 20, bw, 9f, inParts(), top, m.lo to m.hi); hbar(pad, pad + 35, bw, 9f, outParts(), top)
                num(short(abs(net)), pad, H - pad - 14, 26f, netC, maxW = bw); text("kcal $netL", pad, H - pad, 10.5f, C.FG2, maxW = bw) }
            "w" -> { fit(280f, 100f); card(); val pad = 14f; val bw = W * 0.55f
                text("In & out · today", pad, pad + 10, 12f, C.FG, 820, maxW = bw)
                text("In ${fmt(m.eat)}", pad, pad + 30, 10.5f, C.FG2); hbar(pad, pad + 35, bw, 8f, inParts(), top, m.lo to m.hi)
                text("Out ${fmt(m.out)}", pad, pad + 58, 10.5f, C.FG2); hbar(pad, pad + 63, bw, 8f, outParts(), top)
                val rx = W - pad; num(fmt(abs(net)), rx, pad + 40, 24f, netC, Paint.Align.RIGHT, W - bw - 3 * pad)
                text("kcal $netL", rx, pad + 56, 10.5f, C.FG2, align = Paint.Align.RIGHT); text(leftTxt(), rx, H - pad, 11f, acc, 800, Paint.Align.RIGHT, W - bw - 2.5f * pad) }
            "s" -> { fit(184f, 184f); card(); val pad = 15f; val bw = W - 2 * pad
                text("In & out · today", pad, pad + 10, 12.5f, C.FG, 820, maxW = bw)
                text("Eaten", pad, pad + 34, 11f, C.FG2); num(fmt(m.eat), W - pad, pad + 35, 15f, acc, Paint.Align.RIGHT); hbar(pad, pad + 41, bw, 10f, inParts(), top, m.lo to m.hi)
                text("Burned", pad, pad + 72, 11f, C.FG2); num(fmt(m.out), W - pad, pad + 73, 15f, C.AMBER, Paint.Align.RIGHT); hbar(pad, pad + 79, bw, 10f, outParts(), top)
                val nw = num(fmt(abs(net)), pad, H - pad - 18, 24f, netC); text("kcal $netL", pad + nw + 6, H - pad - 18, 10.5f, C.FG2, maxW = bw - nw - 6)
                text(leftTxt() + (if (m.rod != null && m.rod.pro > 0) " · ${m.rod.pro} g protein" else ""), pad, H - pad, 11.5f, acc, 800, maxW = bw) }
            "m" -> { fit(376f, 184f); card(); val pad = 16f; val lw = W * 0.56f
                text("In & out · today", pad, pad + 10, 12.5f, C.FG, 820)
                text("Eaten", pad, pad + 36, 11f, C.FG2); num(fmt(m.eat), pad + lw, pad + 37, 15f, acc, Paint.Align.RIGHT); hbar(pad, pad + 43, lw, 11f, inParts(), top, m.lo to m.hi)
                text("Burned", pad, pad + 78, 11f, C.FG2); num(fmt(m.out), pad + lw, pad + 79, 15f, C.AMBER, Paint.Align.RIGHT); hbar(pad, pad + 85, lw, 11f, outParts(), top)
                text("target ${fmt(m.lo)}–${fmt(m.hi)} · resting ${fmt(m.restDay)}", pad, H - pad, 10.5f, C.FG3, maxW = lw)
                val x = pad + lw + 18; val rw = W - x - pad
                num(fmt(abs(net)), x, pad + 46, 28f, netC, maxW = rw); text("kcal $netL so far", x, pad + 62, 10.5f, C.FG2, maxW = rw)
                text(leftTxt(), x, pad + 92, 13f, acc, 850, maxW = rw); text(proTxt(), x, pad + 110, 11f, C.FG2, maxW = rw)
                val nx = m.rod?.meals?.firstOrNull(); if (nx != null) wrapN("${nx.meal}: ${nx.eat}", x, pad + 132, rw, 11f, C.FG, 2) }
            else -> { fit(376f, 376f); card(); val pad = 17f; val bw = W - 2 * pad
                text("In & out · today", pad, pad + 12, 14f, C.FG, 820); text(leftTxt(), W - pad, pad + 12, 12f, acc, 850, Paint.Align.RIGHT, bw * 0.5f)
                text("Eaten", pad, pad + 40, 11f, C.FG2); num(fmt(m.eat), W - pad, pad + 41, 15f, acc, Paint.Align.RIGHT); hbar(pad, pad + 47, bw, 12f, inParts(), top, m.lo to m.hi)
                text("Burned", pad, pad + 80, 11f, C.FG2); num(fmt(m.out), W - pad, pad + 81, 15f, C.AMBER, Paint.Align.RIGHT); hbar(pad, pad + 87, bw, 12f, outParts(), top)
                var y = pad + 128; val cw = bw / 3
                val r = m.rod; val more = r?.let { if (it.over > 0) -it.over else it.left } ?: max(0, (m.lo + m.hi) / 2 - m.eat)
                for ((i, t) in listOf(Triple(netL.replaceFirstChar { it.uppercase() } + " so far", fmt(abs(net)), netC),
                        Triple(if (more < 0) "Over target" else "To eat", fmt(abs(more)), if (more < 0) C.AMBER else acc),
                        Triple("Protein to go", if (r == null) "—" else if (r.pro > 0) "${r.pro} g" else "✓", C.VIOLET)).withIndex()) {
                    val cx = pad + cw * i + cw / 2; text(t.first, cx, y, 11f, C.FG2, align = Paint.Align.CENTER, maxW = cw - 6); num(t.second, cx, y + 24, 20f, t.third, Paint.Align.CENTER, cw - 6) }
                y += 44; fill(pad, y, bw, H - pad - y, 20f, C.S3)
                eatList(pad + 14, y + 22, bw - 28, H - pad - y - 30, 3) }
        }
        return desc
    }

    // ═══ WHAT TO EAT ═══
    /** the coach's meals for the rest of today, or what's next when they aren't up to date */
    private fun eatList(x: Float, y0: Float, w: Float, h: Float, maxMeals: Int): Float {
        val r = m.rod; var y = y0
        if (r == null) { text("Open the app for what to eat next", x, y, 12f, C.FG2, maxW = w); return y + 18 }
        if (r.closed) { text("Kitchen’s closed for today", x, y, 13f, C.FG, 820, maxW = w); text("Water if you’re hungry, and an early night", x, y + 18, 11.5f, C.FG2, maxW = w); return y + 36 }
        if (r.done) { text("All meals logged for today", x, y, 13f, C.FG, 820, maxW = w); return y + 18 }
        if (r.meals.isEmpty()) { text("Next: " + r.next.joinToString(", ") { "${it.first.lowercase()} ~${it.second}" }, x, y, 12.5f, C.FG, 800, maxW = w)
            text("Open the app for the coach’s suggestions", x, y + 18, 11f, C.FG2, maxW = w); return y + 36 }
        val lines = if (h / max(1, min(maxMeals, r.meals.size)) > 80) 3 else 2
        for (ml in r.meals.take(maxMeals)) { if (y > y0 + h - 14) break
            val kw = text("${fmt(ml.kcal)} kcal · ${ml.pro} g", x + w, y, 11f, C.FG2, 760, Paint.Align.RIGHT)
            val lw = text(ml.meal, x, y, 12.5f, acc, 850, maxW = w * 0.5f)
            if (ml.by.isNotEmpty() && w - lw - kw - 10 > 40) text(" · by ${ml.by}", x + lw, y, 11f, C.FG2, maxW = w - lw - kw - 10)
            y = wrapN(ml.eat, x, y + 17, w, 12f, C.FG, lines) + 24 }
        if (r.tip.isNotEmpty() && y < y0 + h + 4) text("💡 " + r.tip, x, y - 4, 11f, C.FG2, maxW = w)
        return y
    }
    /** wrap() that says where it ended */
    private fun wrapN(s: String, x: Float, y: Float, w: Float, size: Float, color: Int, lines: Int): Float {
        val words = s.split(" "); var line = ""; var yy = y; var n = 1
        for (wd in words) { val t = if (line.isEmpty()) wd else "$line $wd"
            if (width(t, size, 720) > w && line.isNotEmpty()) { if (n >= lines) { text("$line…", x, yy, size, color, maxW = w); return yy }
                text(line, x, yy, size, color); yy += size * 1.3f; line = wd; n++ } else line = t }
        if (line.isNotEmpty()) text(line, x, yy, size, color, maxW = w)
        return yy
    }
    /** in & out today in one thin strip: eaten over burned, with the numbers */
    private fun strip(x: Float, y: Float, w: Float) {
        val top = max(max(m.out, m.eat), m.hi).toDouble() * 1.06
        hbar(x, y, w, 6f, inParts(), top, m.lo to m.hi); hbar(x, y + 9, w, 6f, outParts(), top)
        val net = m.out - m.eat
        text("${fmt(m.eat)} in · ${fmt(m.out)} out · ${fmt(abs(net))} ${if (net >= 0) "deficit" else "surplus"}", x, y + 29, 10.5f, C.FG2, maxW = w)
    }
    private fun eat(size: String): String {
        val r = m.rod; val first = r?.meals?.firstOrNull()
        val head = when { r == null -> "Eat ~${fmt(max(0, (m.lo + m.hi) / 2 - m.eat))} more"; r.over > 0 -> "${fmt(r.over)} over"; r.loLeft > 0 -> "~${fmt(r.left)} kcal more"; else -> "In range" }
        val desc = "What to eat: $head" + (first?.let { ". ${it.meal}: ${it.eat}" } ?: "")
        when (size) {
            "1" -> { val R = min(W, H) / 2 - 1; val cx = W / 2; val cy = H / 2
                shape(cx, cy, R, 9, 0.07, C.BG); text("🍽️", cx, cy - R * 0.3f, R * 0.28f, C.FG, 700, Paint.Align.CENTER)
                val v = if (r != null && r.over > 0) "+" + short(r.over) else short(r?.left ?: max(0, (m.lo + m.hi) / 2 - m.eat))
                num(v, cx, cy + R * 0.2f, R * 0.42f, if (r != null && r.over > 0) C.AMBER else acc, Paint.Align.CENTER, R * 1.2f)
                text(if (r != null && r.over > 0) "over" else "kcal to eat", cx, cy + R * 0.45f, R * 0.18f, C.FG2, 720, Paint.Align.CENTER, R * 1.3f) }
            "w" -> { fit(280f, 100f); card(); val pad = 14f; val bw = W - 2 * pad
                if (first != null) { val lw = text("${first.meal} · ${head}", pad, pad + 10, 12f, acc, 850, maxW = bw); wrapN(first.eat, pad, pad + 30, bw, 12f, C.FG, 2) }
                else { text(head, pad, pad + 10, 13f, acc, 850, maxW = bw); eatList(pad, pad + 30, bw, 30f, 1) }
                val top = max(max(m.out, m.eat), m.hi).toDouble() * 1.06
                hbar(pad, H - pad - 13, bw, 5f, inParts(), top, m.lo to m.hi); hbar(pad, H - pad - 5, bw, 5f, outParts(), top) }
            "s" -> { fit(184f, 184f); card(); val pad = 14f; val bw = W - 2 * pad
                text("What to eat", pad, pad + 10, 12.5f, C.FG, 820, maxW = bw * 0.55f); text(head, W - pad, pad + 10, 11.5f, acc, 850, Paint.Align.RIGHT, bw * 0.5f)
                strip(pad, pad + 20, bw)
                eatList(pad, pad + 72, bw, H - pad - 72 - pad, 2) }
            "m" -> { fit(376f, 184f); card(); val pad = 16f; val lw = W * 0.36f
                text("What to eat", pad, pad + 12, 13f, C.FG, 820)
                num(head.replace(" kcal more", ""), pad, pad + 48, 24f, if (r != null && r.over > 0) C.AMBER else acc, maxW = lw)
                text(if (r != null && r.over > 0) "kcal over today" else "kcal more today", pad, pad + 64, 10.5f, C.FG2, maxW = lw)
                text(proTxt(), pad, pad + 84, 11f, C.FG2, maxW = lw)
                strip(pad, H - pad - 44, lw)
                val x = pad + lw + 16; fill(x - 6, pad - 4, W - x - pad + 10, H - 2 * pad + 8, 18f, C.S3)
                eatList(x + 8, pad + 16, W - x - pad - 12, H - 2 * pad - 16, 2) }
            else -> { fit(376f, 376f); card(); val pad = 17f; val bw = W - 2 * pad
                text("What to eat · rest of today", pad, pad + 12, 14f, C.FG, 820, maxW = bw * 0.62f); text(head, W - pad, pad + 12, 12.5f, acc, 850, Paint.Align.RIGHT, bw * 0.38f)
                strip(pad, pad + 24, bw)
                if (r != null && r.head.isNotEmpty()) text(r.head, pad, pad + 86, 12.5f, C.FG, 800, maxW = bw)
                val y = pad + 100; fill(pad, y, bw, H - pad - y, 20f, C.S3)
                eatList(pad + 14, y + 24, bw - 28, H - pad - y - 32, 4) }
        }
        return desc
    }
}
