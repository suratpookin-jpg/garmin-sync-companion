package com.personallife.garminsync

import com.google.gson.annotations.SerializedName

data class WorkoutSyncPayload(
    @SerializedName("clientVersion") val clientVersion: String = "1.2.0",
    @SerializedName("source") val source: String = "GARMIN_HEALTH_CONNECT",
    @SerializedName("activities") val activities: List<WorkoutItem>
)

data class WorkoutItem(
    @SerializedName("externalId") val externalId: String,
    @SerializedName("compositeKey") val compositeKey: String,
    @SerializedName("date") val date: String,             // YYYY-MM-DD
    @SerializedName("time") val time: String,             // HH:mm:ss
    @SerializedName("activityType") val activityType: String, // 'วิ่ง', 'เดิน', 'ปั่นจักรยาน'
    @SerializedName("rawExerciseType") val rawExerciseType: String,
    @SerializedName("title") val title: String,
    @SerializedName("durationMinutes") val durationMinutes: Double,
    @SerializedName("caloriesBurned") val caloriesBurned: Double, // MUST BE ACTIVE CALORIES
    @SerializedName("distanceKm") val distanceKm: Double?,
    @SerializedName("avgHeartRate") val avgHeartRate: Int?,
    @SerializedName("avgPace") val avgPace: String?,
    @SerializedName("note") val note: String
)

data class FoodItem(
    @SerializedName("foodId") val foodId: String,
    @SerializedName("date") val date: String,             // YYYY-MM-DD
    @SerializedName("mealType") val mealType: String?,    // เช้า, กลางวัน, เย็น, ของว่าง / เครื่องดื่ม
    @SerializedName("description") val description: String?,
    @SerializedName("calories") val calories: Double,
    @SerializedName("protein") val protein: Double = 0.0,
    @SerializedName("carbs") val carbs: Double = 0.0,
    @SerializedName("fat") val fat: Double = 0.0,
    @SerializedName("fiber") val fiber: Double = 0.0,
    @SerializedName("createdAt") val createdAt: String? = null
)

data class FoodLogsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("count") val count: Int? = 0,
    @SerializedName("days") val days: Int? = 0,
    @SerializedName("foods") val foods: List<FoodItem>? = null,
    @SerializedName("bodyMetrics") val bodyMetrics: List<BodyMetricItem>? = null,
    @SerializedName("error") val error: String? = null
)

data class BodyMetricItem(
    @SerializedName("metricId") val metricId: String,
    @SerializedName("date") val date: String,             // YYYY-MM-DD
    @SerializedName("weightKg") val weightKg: Double,
    @SerializedName("bodyFatPct") val bodyFatPct: Double = 0.0,
    @SerializedName("muscleKg") val muscleKg: Double = 0.0,
    @SerializedName("bmrKcal") val bmrKcal: Double = 0.0,
    @SerializedName("createdAt") val createdAt: String? = null
)

data class BodyMetricsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("count") val count: Int? = 0,
    @SerializedName("bodyMetrics") val bodyMetrics: List<BodyMetricItem>? = null,
    @SerializedName("error") val error: String? = null
)

data class BridgeResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("inserted") val inserted: Int?,
    @SerializedName("skipped") val skipped: Int?,
    @SerializedName("recentFoods") val recentFoods: List<FoodItem>? = null,
    @SerializedName("recentBodyMetrics") val recentBodyMetrics: List<BodyMetricItem>? = null,
    @SerializedName("error") val error: String? = null
)
