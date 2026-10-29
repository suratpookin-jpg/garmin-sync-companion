package com.personallife.garminsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.temporal.ChronoUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
        val endpointUrl = prefs.getString("gas_url", "") ?: ""
        val secretToken = prefs.getString("bridge_secret", "") ?: ""

        if (endpointUrl.isBlank()) {
            return Result.success()
        }

        val hcManager = HealthConnectManager(applicationContext)
        if (!hcManager.hasAllPermissions()) {
            return Result.success()
        }

        val network = NetworkManager()
        var anySuccess = false

        // 1. ขาออก (Upload): ดึงกิจกรรม Garmin 24 ชั่วโมงล่าสุด ส่งเข้า GAS
        var recentFoodsFromUpload: List<FoodItem>? = null
        var recentMetricsFromUpload: List<BodyMetricItem>? = null

        try {
            val since = Instant.now().minus(24, ChronoUnit.HOURS)
            val workouts = hcManager.readRecentGarminWorkouts(since)

            if (workouts.isNotEmpty()) {
                val payload = WorkoutSyncPayload(activities = workouts)
                val result = network.syncWorkouts(endpointUrl, secretToken, payload)
                if (result.isSuccess) {
                    anySuccess = true
                    val bridgeRes = result.getOrNull()
                    recentFoodsFromUpload = bridgeRes?.recentFoods
                    recentMetricsFromUpload = bridgeRes?.recentBodyMetrics
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. ขาเข้า (Download อาหาร): ดึงประวัติอาหารจาก GAS เขียนลง Health Connect -> Samsung Health
        try {
            val foodsToSync = if (!recentFoodsFromUpload.isNullOrEmpty()) {
                recentFoodsFromUpload
            } else {
                val foodResult = network.fetchRecentFoods(endpointUrl, secretToken, days = 3)
                if (foodResult.isSuccess) foodResult.getOrNull()?.first else null
            }

            if (!foodsToSync.isNullOrEmpty()) {
                val (inserted, skipped) = hcManager.writeNutritionRecords(foodsToSync)
                if (inserted > 0 || skipped > 0) {
                    anySuccess = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. ขาเข้า (Download น้ำหนัก Xiaomi S400): ดึงน้ำหนักและไขมัน เขียนลง Health Connect -> Samsung Health
        try {
            val metricsToSync = if (!recentMetricsFromUpload.isNullOrEmpty()) {
                recentMetricsFromUpload
            } else {
                val metricResult = network.fetchRecentBodyMetrics(endpointUrl, secretToken, days = 7)
                if (metricResult.isSuccess) metricResult.getOrNull() else null
            }

            if (!metricsToSync.isNullOrEmpty()) {
                val (inserted, skipped) = hcManager.writeBodyMetricRecords(metricsToSync)
                if (inserted > 0 || skipped > 0) {
                    anySuccess = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (anySuccess) {
            prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
            Result.success()
        } else {
            Result.success()
        }
    }
}
