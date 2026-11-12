package com.yashgumaste.fitnesstracker

import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
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
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Health Connect bridge: reads daily steps and writes meals, water, walks/workouts, weight and sleep,
 * so they show up in Google Health and other Health Connect apps.
 * Records carry a clientRecordId (e.g. "meal-123"), so writing again updates instead of duplicating.
 */
@CapacitorPlugin(name = "HealthConnect")
class HealthConnectPlugin : Plugin() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val phone = Device(type = Device.TYPE_PHONE)

    private val types: Map<String, KClass<out Record>> = mapOf(
        "Steps" to StepsRecord::class,
        "Nutrition" to NutritionRecord::class,
        "Hydration" to HydrationRecord::class,
        "ExerciseSession" to ExerciseSessionRecord::class,
        "Weight" to WeightRecord::class,
        "SleepSession" to SleepSessionRecord::class
    )

    private fun client() = HealthConnectClient.getOrCreate(context)
    private fun zone(at: Instant) = ZoneId.systemDefault().rules.getOffset(at)

    @PluginMethod
    fun available(call: PluginCall) {
        val status = HealthConnectClient.getSdkStatus(context)
        val r = JSObject()
        r.put("available", status == HealthConnectClient.SDK_AVAILABLE)
        r.put("needsUpdate", status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
        call.resolve(r)
    }

    private fun wanted(call: PluginCall): Set<String> {
        val out = mutableSetOf<String>()
        call.getArray("read")?.toList<String>()?.forEach { t -> types[t]?.let { out.add(HealthPermission.getReadPermission(it)) } }
        call.getArray("write")?.toList<String>()?.forEach { t -> types[t]?.let { out.add(HealthPermission.getWritePermission(it)) } }
        return out
    }

    private fun grantedNames(granted: Set<String>): JSObject {
        val read = JSArray(); val write = JSArray()
        for ((name, k) in types) {
            if (granted.contains(HealthPermission.getReadPermission(k))) read.put(name)
            if (granted.contains(HealthPermission.getWritePermission(k))) write.put(name)
        }
        val r = JSObject(); r.put("read", read); r.put("write", write); return r
    }

    @PluginMethod
    fun granted(call: PluginCall) {
        scope.launch {
            try { call.resolve(grantedNames(client().permissionController.getGrantedPermissions())) }
            catch (e: Exception) { call.reject(e.message ?: "Health Connect not available") }
        }
    }

    @PluginMethod
    fun authorize(call: PluginCall) {
        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            val intent = contract.createIntent(context, wanted(call))
            startActivityForResult(call, intent, "authorizeResult")
        } catch (e: Exception) { call.reject(e.message ?: "Could not open Health Connect") }
    }

    @ActivityCallback
    private fun authorizeResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        scope.launch {
            try { call.resolve(grantedNames(client().permissionController.getGrantedPermissions())) }
            catch (e: Exception) { call.reject(e.message ?: "Health Connect not available") }
        }
    }

    /** Total steps per day for the last N days (Health Connect merges all step sources). */
    @PluginMethod
    fun readSteps(call: PluginCall) {
        val days = call.getInt("days", 7) ?: 7
        scope.launch {
            try {
                val z = ZoneId.systemDefault(); val today = LocalDate.now(z); val out = JSArray()
                for (i in 0 until days) {
                    val d = today.minusDays(i.toLong())
                    val res = client().aggregate(AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(d.atStartOfDay(z).toInstant(), d.plusDays(1).atStartOfDay(z).toInstant())))
                    val o = JSObject(); o.put("date", d.toString()); o.put("steps", res[StepsRecord.COUNT_TOTAL] ?: 0L); out.put(o)
                }
                val r = JSObject(); r.put("days", out); call.resolve(r)
            } catch (e: Exception) { call.reject(e.message ?: "Could not read steps") }
        }
    }

    /** Steps in fixed slices (default 10 min) for each day, ending at "end" (YYYY-MM-DD, default today). */
    @PluginMethod
    fun readStepBuckets(call: PluginCall) {
        val days = (call.getInt("days", 1) ?: 1).coerceIn(1, 31)
        val mins = (call.getInt("minutes", 10) ?: 10).coerceIn(5, 60)
        val z = ZoneId.systemDefault()
        val end = try { call.getString("end")?.let { LocalDate.parse(it) } } catch (e: Exception) { null } ?: LocalDate.now(z)
        scope.launch {
            try {
                val per = 1440 / mins; val out = JSArray()
                for (i in 0 until days) {
                    val d = end.minusDays(i.toLong())
                    val from = d.atStartOfDay(z).toInstant(); val to = d.plusDays(1).atStartOfDay(z).toInstant()
                    val res = client().aggregateGroupByDuration(AggregateGroupByDurationRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        timeRangeSlicer = Duration.ofMinutes(mins.toLong())))
                    val slots = LongArray(per)
                    for (g in res) {
                        val idx = (Duration.between(from, g.startTime).toMinutes() / mins).toInt()
                        if (idx in 0 until per) slots[idx] += g.result[StepsRecord.COUNT_TOTAL] ?: 0L
                    }
                    val b = JSArray(); for (v in slots) b.put(v)
                    val o = JSObject(); o.put("date", d.toString()); o.put("minutes", mins); o.put("b", b); out.put(o)
                }
                val r = JSObject(); r.put("days", out); call.resolve(r)
            } catch (e: Exception) { call.reject(e.message ?: "Could not read steps") }
        }
    }

    private fun meta(o: JSONObject) = Metadata.manualEntry(
        device = phone, clientRecordId = o.getString("id"), clientRecordVersion = o.getLong("version"))

    private fun build(o: JSONObject): Record? {
        val start = Instant.ofEpochMilli(o.optLong("start", o.optLong("time")))
        val end = Instant.ofEpochMilli(o.optLong("end", start.toEpochMilli() + 60_000))
        return when (o.getString("type")) {
            "Nutrition" -> NutritionRecord(
                startTime = start, startZoneOffset = zone(start), endTime = end, endZoneOffset = zone(end),
                name = o.optString("name").take(100),
                mealType = when (o.optString("mealType")) {
                    "Breakfast" -> MealType.MEAL_TYPE_BREAKFAST; "Lunch" -> MealType.MEAL_TYPE_LUNCH
                    "Dinner" -> MealType.MEAL_TYPE_DINNER; "Snack" -> MealType.MEAL_TYPE_SNACK
                    else -> MealType.MEAL_TYPE_UNKNOWN },
                energy = Energy.kilocalories(o.optDouble("kcal", 0.0)),
                protein = Mass.grams(o.optDouble("protein", 0.0)),
                totalCarbohydrate = Mass.grams(o.optDouble("carbs", 0.0)),
                totalFat = Mass.grams(o.optDouble("fat", 0.0)),
                metadata = meta(o))
            "Hydration" -> HydrationRecord(
                startTime = start, startZoneOffset = zone(start), endTime = end, endZoneOffset = zone(end),
                volume = Volume.milliliters(o.optDouble("ml", 0.0)), metadata = meta(o))
            "ExerciseSession" -> ExerciseSessionRecord(
                startTime = start, startZoneOffset = zone(start), endTime = end, endZoneOffset = zone(end),
                exerciseType = when (o.optString("exercise")) {
                    "walking" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    "running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                    "strength" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
                    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT },
                title = o.optString("title").ifEmpty { null }, metadata = meta(o))
            "Weight" -> WeightRecord(
                time = start, zoneOffset = zone(start), weight = Mass.kilograms(o.getDouble("kg")), metadata = meta(o))
            "SleepSession" -> SleepSessionRecord(
                startTime = start, startZoneOffset = zone(start), endTime = end, endZoneOffset = zone(end),
                title = o.optString("title").ifEmpty { null }, metadata = meta(o))
            else -> null
        }
    }

    /** Insert or update records (same id + higher version = update). Skips types without permission. */
    @PluginMethod
    fun upsert(call: PluginCall) {
        val list = call.getArray("records") ?: JSArray()
        scope.launch {
            try {
                val granted = client().permissionController.getGrantedPermissions()
                val recs = mutableListOf<Record>(); val failed = JSArray()
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    val k = types[o.optString("type")] ?: continue
                    if (!granted.contains(HealthPermission.getWritePermission(k))) continue
                    try { build(o)?.let { recs.add(it) } } catch (e: Exception) { failed.put(o.optString("id")) }
                }
                for (chunk in recs.chunked(500)) client().insertRecords(chunk)
                val r = JSObject(); r.put("written", recs.size); r.put("failed", failed); call.resolve(r)
            } catch (e: Exception) { call.reject(e.message ?: "Could not write to Health Connect") }
        }
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        val k = types[call.getString("type") ?: ""]
        val ids = call.getArray("ids")?.toList<String>() ?: emptyList()
        if (k == null || ids.isEmpty()) { call.resolve(); return }
        scope.launch {
            try { client().deleteRecords(k, recordIdsList = emptyList(), clientRecordIdsList = ids); call.resolve() }
            catch (e: Exception) { call.reject(e.message ?: "Could not delete from Health Connect") }
        }
    }
}
