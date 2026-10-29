package com.personallife.garminsync

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NetworkManager {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 1. ส่งข้อมูลกิจกรรมการออกกำลังกาย (Garmin Workout -> GAS)
     * และรับรายการอาหารล่าสุดกลับมาด้วย (2-Way Sync Ingest)
     */
    suspend fun syncWorkouts(
        endpointUrl: String,
        secretToken: String,
        payload: WorkoutSyncPayload
    ): Result<BridgeResponse> = withContext(Dispatchers.IO) {
        try {
            if (endpointUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("ยังไม่ได้ระบุ GAS Web App URL"))
            }

            val jsonBody = gson.toJson(payload)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(endpointUrl.trim())
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Bridge-Token", secretToken.trim())
                .addHeader("User-Agent", "GarminSyncCompanion/1.1")
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: $responseText")
                )
            }

            try {
                val bridgeResponse = gson.fromJson(responseText, BridgeResponse::class.java)
                Result.success(bridgeResponse)
            } catch (jsonErr: Exception) {
                Result.success(
                    BridgeResponse(
                        success = true,
                        message = "ส่งข้อมูลเรียบร้อยแล้ว (การตอบกลับดิบ: ${responseText.take(80)})",
                        inserted = payload.activities.size,
                        skipped = 0,
                        recentFoods = null,
                        error = null
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 2. ดึงข้อมูลอาหารล่าสุดจาก Google Sheets (GAS -> Health Connect -> Samsung Health)
     */
    suspend fun fetchRecentFoods(
        endpointUrl: String,
        secretToken: String,
        days: Int = 3
    ): Result<List<FoodItem>> = withContext(Dispatchers.IO) {
        try {
            if (endpointUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("ยังไม่ได้ระบุ GAS Web App URL"))
            }

            val separator = if (endpointUrl.contains("?")) "&" else "?"
            val url = "$endpointUrl${separator}action=get_food_logs&days=$days&token=${secretToken.trim()}"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("X-Bridge-Token", secretToken.trim())
                .addHeader("User-Agent", "GarminSyncCompanion/1.1")
                .build()

            val response = client.newCall(request).execute()
            val responseText = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $responseText"))
            }

            val foodResp = gson.fromJson(responseText, FoodLogsResponse::class.java)
            if (foodResp != null && foodResp.success) {
                Result.success(foodResp.foods ?: emptyList())
            } else {
                Result.failure(Exception(foodResp?.error ?: "ไม่สามารถดึงข้อมูลอาหารจากเซิร์ฟเวอร์ได้"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
