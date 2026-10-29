package com.personallife.garminsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.grams
import androidx.health.connect.client.units.kilocalories
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class HealthConnectManager(private val context: Context) {

    private val client by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(requiredPermissions)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * เขียนข้อมูลอาหาร (NutritionRecord) ลง Health Connect
     */
    suspend fun writeNutritionRecords(foods: List<FoodItem>): Pair<Int, Int> {
        if (foods.isEmpty()) return Pair(0, 0)

        val now = Instant.now()
        val sevenDaysAgo = now.minus(7, ChronoUnit.DAYS)
        val existingClientIds = mutableSetOf<String>()

        try {
            val existing = client.readRecords(
                ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sevenDaysAgo, now.plus(1, ChronoUnit.DAYS))
                )
            )
            for (rec in existing.records) {
                val cid = rec.metadata.clientRecordId
                if (!cid.isNullOrBlank()) {
                    existingClientIds.add(cid)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val recordsToInsert = mutableListOf<NutritionRecord>()
        var skipped = 0

        for (food in foods) {
            val uniqueId = if (!food.foodId.isNullOrBlank()) {
                food.foodId.trim()
            } else {
                "FOOD_${food.date}_${(food.description ?: "").hashCode()}"
            }

            if (existingClientIds.contains(uniqueId)) {
                skipped++
                continue
            }

            val startInstant = parseFoodTimestamp(food)
            val endInstant = startInstant.plus(1, ChronoUnit.MINUTES)

            val record = NutritionRecord(
                name = food.description?.ifBlank { "อาหาร" } ?: "อาหาร",
                energy = food.calories.kilocalories,
                protein = if (food.protein > 0) food.protein.grams else null,
                totalCarbohydrate = if (food.carbs > 0) food.carbs.grams else null,
                totalFat = if (food.fat > 0) food.fat.grams else null,
                dietaryFiber = if (food.fiber > 0) food.fiber.grams else null,
                mealType = mapMealType(food.mealType),
                startTime = startInstant,
                startZoneOffset = ZoneOffset.ofHours(7),
                endTime = endInstant,
                endZoneOffset = ZoneOffset.ofHours(7),
                metadata = Metadata(clientRecordId = uniqueId)
            )

            recordsToInsert.add(record)
            existingClientIds.add(uniqueId)
        }

        if (recordsToInsert.isEmpty()) {
            return Pair(0, skipped)
        }

        return try {
            client.insertRecords(recordsToInsert)
            Pair(recordsToInsert.size, skipped)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(0, skipped)
        }
    }

    /**
     * เขียนข้อมูลองค์ประกอบร่างกายเต็มรูปแบบ (Xiaomi S400) ลง Health Connect
     * รองรับทั้ง Weight, Body Fat %, Muscle Mass, Bone Mass และ BMR
     * ตรวจสอบความซ้ำแยกรายประเภท เพื่อให้ค่าที่เคยขาด (เช่น Body Fat) สามารถเติมเต็มได้
     */
    suspend fun writeBodyMetricRecords(metrics: List<BodyMetricItem>): Pair<Int, Int> {
        if (metrics.isEmpty()) return Pair(0, 0)

        val now = Instant.now()
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val timeFilter = TimeRangeFilter.between(thirtyDaysAgo, now.plus(1, ChronoUnit.DAYS))

        val existingWeightIds = mutableSetOf<String>()
        val existingFatIds = mutableSetOf<String>()
        val existingLeanIds = mutableSetOf<String>()
        val existingBoneIds = mutableSetOf<String>()
        val existingBmrIds = mutableSetOf<String>()

        try {
            val weights = client.readRecords(ReadRecordsRequest(WeightRecord::class, timeFilter))
            for (rec in weights.records) rec.metadata.clientRecordId?.let { existingWeightIds.add(it) }
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val fats = client.readRecords(ReadRecordsRequest(BodyFatRecord::class, timeFilter))
            for (rec in fats.records) rec.metadata.clientRecordId?.let { existingFatIds.add(it) }
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val leans = client.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, timeFilter))
            for (rec in leans.records) rec.metadata.clientRecordId?.let { existingLeanIds.add(it) }
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val bones = client.readRecords(ReadRecordsRequest(BoneMassRecord::class, timeFilter))
            for (rec in bones.records) rec.metadata.clientRecordId?.let { existingBoneIds.add(it) }
        } catch (e: Exception) { e.printStackTrace() }

        try {
            val bmrs = client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, timeFilter))
            for (rec in bmrs.records) rec.metadata.clientRecordId?.let { existingBmrIds.add(it) }
        } catch (e: Exception) { e.printStackTrace() }

        val recordsToInsert = mutableListOf<androidx.health.connect.client.records.Record>()
        var skipped = 0

        for (item in metrics) {
            val instant = parseMetricTimestamp(item)
            var itemHadNewField = false

            // 1. WeightRecord (น้ำหนักตัว)
            if (item.weightKg > 0.0) {
                val weightClientId = "WEIGHT_${item.metricId}"
                if (!existingWeightIds.contains(weightClientId)) {
                    recordsToInsert.add(
                        WeightRecord(
                            weight = Mass.kilograms(item.weightKg),
                            time = instant,
                            zoneOffset = ZoneOffset.ofHours(7),
                            metadata = Metadata(clientRecordId = weightClientId)
                        )
                    )
                    existingWeightIds.add(weightClientId)
                    itemHadNewField = true
                }
            }

            // 2. BodyFatRecord (เปอร์เซ็นต์ไขมันในร่างกาย)
            if (item.bodyFatPct > 0.0 && item.bodyFatPct <= 100.0) {
                val fatClientId = "BODYFAT_${item.metricId}"
                if (!existingFatIds.contains(fatClientId)) {
                    recordsToInsert.add(
                        BodyFatRecord(
                            percentage = Percentage(item.bodyFatPct),
                            time = instant,
                            zoneOffset = ZoneOffset.ofHours(7),
                            metadata = Metadata(clientRecordId = fatClientId)
                        )
                    )
                    existingFatIds.add(fatClientId)
                    itemHadNewField = true
                }
            }

            // 3. LeanBodyMassRecord (มวลกล้ามเนื้อโครงร่าง)
            if (item.muscleKg > 0.0) {
                val leanClientId = "LEANMASS_${item.metricId}"
                if (!existingLeanIds.contains(leanClientId)) {
                    recordsToInsert.add(
                        LeanBodyMassRecord(
                            mass = Mass.kilograms(item.muscleKg),
                            time = instant,
                            zoneOffset = ZoneOffset.ofHours(7),
                            metadata = Metadata(clientRecordId = leanClientId)
                        )
                    )
                    existingLeanIds.add(leanClientId)
                    itemHadNewField = true
                }
            }

            // 4. BoneMassRecord (มวลกระดูก)
            if (item.boneMassKg > 0.0) {
                val boneClientId = "BONEMASS_${item.metricId}"
                if (!existingBoneIds.contains(boneClientId)) {
                    recordsToInsert.add(
                        BoneMassRecord(
                            mass = Mass.kilograms(item.boneMassKg),
                            time = instant,
                            zoneOffset = ZoneOffset.ofHours(7),
                            metadata = Metadata(clientRecordId = boneClientId)
                        )
                    )
                    existingBoneIds.add(boneClientId)
                    itemHadNewField = true
                }
            }

            // 5. BasalMetabolicRateRecord (อัตราเผาผลาญพลังงานพื้นฐาน BMR)
            if (item.bmrKcal > 0.0) {
                val bmrClientId = "BMR_${item.metricId}"
                if (!existingBmrIds.contains(bmrClientId)) {
                    recordsToInsert.add(
                        BasalMetabolicRateRecord(
                            basalMetabolicRate = Power.kilocaloriesPerDay(item.bmrKcal),
                            time = instant,
                            zoneOffset = ZoneOffset.ofHours(7),
                            metadata = Metadata(clientRecordId = bmrClientId)
                        )
                    )
                    existingBmrIds.add(bmrClientId)
                    itemHadNewField = true
                }
            }

            if (!itemHadNewField) {
                skipped++
            }
        }

        if (recordsToInsert.isEmpty()) {
            return Pair(0, skipped)
        }

        return try {
            client.insertRecords(recordsToInsert)
            Pair(recordsToInsert.size, skipped)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(0, skipped)
        }
    }

    private fun parseFoodTimestamp(food: FoodItem): Instant {
        val zoneBangkok = ZoneId.of("Asia/Bangkok")
        if (!food.createdAt.isNullOrBlank()) {
            try {
                return Instant.parse(food.createdAt)
            } catch (e: Exception) {
                try {
                    return ZonedDateTime.parse(food.createdAt).toInstant()
                } catch (e2: Exception) {}
            }
        }

        return try {
            val localDate = LocalDate.parse(food.date)
            val (hour, min) = when (food.mealType?.trim()) {
                "เช้า" -> Pair(8, 0)
                "กลางวัน" -> Pair(12, 30)
                "เย็น" -> Pair(18, 30)
                "ของว่าง / เครื่องดื่ม", "ของว่าง" -> Pair(15, 0)
                else -> Pair(12, 0)
            }
            localDate.atTime(hour, min).atZone(zoneBangkok).toInstant()
        } catch (e: Exception) {
            Instant.now()
        }
    }

    private fun parseMetricTimestamp(item: BodyMetricItem): Instant {
        val zoneBangkok = ZoneId.of("Asia/Bangkok")
        if (!item.createdAt.isNullOrBlank()) {
            try {
                return Instant.parse(item.createdAt)
            } catch (e: Exception) {
                try {
                    return ZonedDateTime.parse(item.createdAt).toInstant()
                } catch (e2: Exception) {}
            }
        }

        return try {
            val localDate = LocalDate.parse(item.date)
            localDate.atTime(7, 30).atZone(zoneBangkok).toInstant()
        } catch (e: Exception) {
            Instant.now()
        }
    }

    private fun mapMealType(thaiMealType: String?): Int {
        return when (thaiMealType?.trim()) {
            "เช้า" -> MealType.MEAL_TYPE_BREAKFAST
            "กลางวัน" -> MealType.MEAL_TYPE_LUNCH
            "เย็น" -> MealType.MEAL_TYPE_DINNER
            "ของว่าง / เครื่องดื่ม", "ของว่าง", "ของหวาน" -> MealType.MEAL_TYPE_SNACK
            else -> MealType.MEAL_TYPE_UNKNOWN
        }
    }

    suspend fun readRecentGarminWorkouts(
        startTime: Instant,
        endTime: Instant = Instant.now()
    ): List<WorkoutItem> {
        val workouts = mutableListOf<WorkoutItem>()
        val zoneBangkok = ZoneId.of("Asia/Bangkok")
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(zoneBangkok)
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(zoneBangkok)

        try {
            val sessionResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )

            for (session in sessionResponse.records) {
                val packageName = session.metadata.dataOrigin.packageName
                val isGarmin = packageName.contains("garmin", ignoreCase = true)

                val sessionStart = session.startTime
                val sessionEnd = session.endTime
                val durationMillis = Duration.between(sessionStart, sessionEnd).toMillis()
                val durationMinutes = Math.round((durationMillis / 60000.0) * 10.0) / 10.0

                if (durationMinutes <= 0.0) continue

                var activeCalories = 0.0
                try {
                    val calAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd)
                        )
                    )
                    activeCalories = calAgg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                } catch (e: Exception) {}

                var distanceKm: Double? = null
                try {
                    val distAgg = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd)
                        )
                    )
                    val meters = distAgg[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                    if (meters != null && meters > 0) {
                        distanceKm = Math.round((meters / 1000.0) * 100.0) / 100.0
                    }
                } catch (e: Exception) {}

                var avgHeartRate: Int? = null
                try {
                    val hrResponse = client.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd)
                        )
                    )
                    val samples = hrResponse.records.flatMap { it.samples }
                    if (samples.isNotEmpty()) {
                        avgHeartRate = samples.map { it.beatsPerMinute }.average().toInt()
                    }
                } catch (e: Exception) {}

                var avgPace: String? = null
                if (distanceKm != null && distanceKm > 0.05 && durationMinutes > 0) {
                    val totalPaceSeconds = ((durationMinutes * 60) / distanceKm).toInt()
                    val paceMin = totalPaceSeconds / 60
                    val paceSec = totalPaceSeconds % 60
                    avgPace = String.format(Locale.US, "%d:%02d /km", paceMin, paceSec)
                }

                val dateStr = dateFormatter.format(sessionStart)
                val timeStr = timeFormatter.format(sessionStart)
                val typeThai = mapExerciseTypeToThai(session.exerciseType)
                val title = session.title ?: if (isGarmin) "Garmin Workout" else "Workout"
                val rawType = getExerciseTypeName(session.exerciseType)

                val compositeKey = "HC_GARMIN_${sessionStart.epochSecond}_${rawType}"
                val externalId = session.metadata.id

                val note = buildString {
                    append(title)
                    if (!avgPace.isNullOrEmpty()) append(" | Pace: $avgPace")
                    append(" | Source: $packageName")
                }

                workouts.add(
                    WorkoutItem(
                        externalId = externalId,
                        compositeKey = compositeKey,
                        date = dateStr,
                        time = timeStr,
                        activityType = typeThai,
                        rawExerciseType = rawType,
                        title = title,
                        durationMinutes = durationMinutes,
                        caloriesBurned = Math.round(activeCalories).toDouble(),
                        distanceKm = distanceKm,
                        avgHeartRate = avgHeartRate,
                        avgPace = avgPace,
                        note = note
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return workouts
    }

    private fun mapExerciseTypeToThai(exerciseType: Int): String {
        return when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "วิ่ง"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "เดิน"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "ปั่นจักรยาน"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "ว่ายน้ำ"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "เวทเทรนนิ่ง"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "โยคะ"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "แบดมินตัน"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "ฟุตบอล"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "คาร์ดิโอ"
            else -> "ออกกำลังกาย"
        }
    }

    private fun getExerciseTypeName(exerciseType: Int): String {
        return when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "BIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH_TRAINING"
            else -> "EXERCISE_$exerciseType"
        }
    }
}
