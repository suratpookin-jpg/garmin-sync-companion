package com.personallife.garminsync

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.personallife.garminsync.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hcManager: HealthConnectManager
    private val networkManager = NetworkManager()

    private val requestPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        lifecycleScope.launch {
            updatePermissionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hcManager = HealthConnectManager(this)

        loadPreferences()
        setupListeners()

        lifecycleScope.launch {
            updatePermissionStatus()
            updateLastSyncDisplay()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
        binding.etGasUrl.setText(prefs.getString("gas_url", ""))
        binding.etBridgeSecret.setText(prefs.getString("bridge_secret", ""))
        binding.switchAutoSync.isChecked = prefs.getBoolean("auto_sync_enabled", false)
    }

    private fun setupListeners() {
        binding.btnGrantPermissions.setOnClickListener {
            lifecycleScope.launch {
                if (hcManager.hasAllPermissions()) {
                    Toast.makeText(this@MainActivity, "ได้รับสิทธิ์ครบถ้วนแล้ว (กิจกรรม + อาหาร + น้ำหนัก)", Toast.LENGTH_SHORT).show()
                } else {
                    requestPermissionLauncher.launch(hcManager.requiredPermissions)
                }
            }
        }

        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etGasUrl.text?.toString()?.trim() ?: ""
            val secret = binding.etBridgeSecret.text?.toString()?.trim() ?: ""

            getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("gas_url", url)
                .putString("bridge_secret", secret)
                .apply()

            Toast.makeText(this, "💾 บันทึกการตั้งค่าเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
            appendLog("✅ บันทึก URL และ Secret Token สำเร็จ")
        }

        binding.btnSyncNow.setOnClickListener {
            performManualSync()
        }

        binding.switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_sync_enabled", isChecked)
                .apply()

            if (isChecked) {
                scheduleBackgroundSync()
                appendLog("🕒 เปิดการซิงก์อัตโนมัติ 3-Way ในพื้นหลัง (ทุก 30 นาที)")
            } else {
                cancelBackgroundSync()
                appendLog("⏹️ ปิดการซิงก์อัตโนมัติในพื้นหลัง")
            }
        }
    }

    private suspend fun updatePermissionStatus() {
        val hasPerms = hcManager.hasAllPermissions()
        if (hasPerms) {
            binding.tvPermissionStatus.text = "สถานะ: ✅ ได้รับสิทธิ์ Health Connect ครบถ้วน (กิจกรรม + อาหาร + น้ำหนัก)"
            binding.tvPermissionStatus.setTextColor(getColor(R.color.success))
            binding.btnGrantPermissions.isEnabled = false
            binding.btnGrantPermissions.text = "ได้รับสิทธิ์ครบแล้ว"
        } else {
            binding.tvPermissionStatus.text = "สถานะ: ⚠️ ยังไม่ได้รับสิทธิ์ Health Connect ครบถ้วน"
            binding.tvPermissionStatus.setTextColor(getColor(R.color.danger))
            binding.btnGrantPermissions.isEnabled = true
            binding.btnGrantPermissions.text = getString(R.string.btn_check_permissions)
        }
    }

    /**
     * ดำเนินการ 3-Way Health Sync:
     * 1. Garmin Workouts (Health Connect -> GAS)
     * 2. Food Logs (GAS -> Health Connect -> Samsung Health)
     * 3. Body Metrics (Xiaomi S400 in GAS -> Health Connect -> Samsung Health)
     */
    private fun performManualSync() {
        val url = binding.etGasUrl.text?.toString()?.trim() ?: ""
        val secret = binding.etBridgeSecret.text?.toString()?.trim() ?: ""

        if (url.isBlank()) {
            Toast.makeText(this, "กรุณากรอก GAS Web App URL ก่อนซิงก์", Toast.LENGTH_LONG).show()
            appendLog("❌ ผิดพลาด: ยังไม่ได้ระบุ GAS Web App URL")
            return
        }

        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "⏳ กำลังซิงก์ข้อมูล 3-Way..."
        appendLog("🚀 เริ่มการซิงก์ 3-Way (Garmin ➔ GAS, อาหาร & น้ำหนัก ➔ Samsung Health)...")

        lifecycleScope.launch {
            try {
                if (!hcManager.hasAllPermissions()) {
                    appendLog("⚠️ ยังไม่ได้รับสิทธิ์ Health Connect ครบถ้วน กรุณากดปุ่มขอสิทธิ์ก่อน")
                    binding.btnSyncNow.isEnabled = true
                    binding.btnSyncNow.text = getString(R.string.btn_sync_now)
                    return@launch
                }

                var syncSucceeded = false
                var recentFoodsFromUpload: List<FoodItem>? = null
                var recentMetricsFromUpload: List<BodyMetricItem>? = null

                // ==========================================
                // ขั้นตอนที่ 1: อัปโหลดกิจกรรม Garmin ไปยัง GAS
                // ==========================================
                val startTime = Instant.now().minus(48, ChronoUnit.HOURS)
                appendLog("🔍 [1/3] ค้นหากิจกรรม Garmin ย้อนหลัง 48 ชม. ...")
                val workouts = hcManager.readRecentGarminWorkouts(startTime)

                if (workouts.isNotEmpty()) {
                    appendLog("📦 พบกิจกรรม ${workouts.size} รายการ กำลังอัปโหลดไปยังชีต...")
                    val payload = WorkoutSyncPayload(activities = workouts)
                    val result = networkManager.syncWorkouts(url, secret, payload)

                    result.onSuccess { res ->
                        if (res.success) {
                            appendLog("✅ อัปโหลดกิจกรรมสำเร็จ: บันทึกใหม่ ${res.inserted ?: 0}, ข้าม ${res.skipped ?: 0}")
                            syncSucceeded = true
                            recentFoodsFromUpload = res.recentFoods
                            recentMetricsFromUpload = res.recentBodyMetrics
                        } else {
                            appendLog("⚠️ อัปโหลดกิจกรรมไม่สำเร็จ: ${res.error ?: "ไม่ทราบสาเหตุ"}")
                        }
                    }.onFailure { err ->
                        appendLog("❌ เชื่อมต่ออัปโหลดกิจกรรมล้มเหลว: ${err.message}")
                    }
                } else {
                    appendLog("ℹ️ ไม่พบกิจกรรมใหม่จาก Garmin Connect ใน Health Connect")
                }

                // ==========================================
                // ขั้นตอนที่ 2: ดึงข้อมูลอาหารจาก GAS เข้า Health Connect (สำหรับ Samsung Health)
                // ==========================================
                appendLog("🥗 [2/3] กำลังตรวจสอบข้อมูลอาหารล่าสุดจาก Google Sheets...")
                val foodsToSync = if (!recentFoodsFromUpload.isNullOrEmpty()) {
                    recentFoodsFromUpload
                } else {
                    val foodResult = networkManager.fetchRecentFoods(url, secret, days = 3)
                    if (foodResult.isSuccess) foodResult.getOrNull()?.first else null
                }

                if (!foodsToSync.isNullOrEmpty()) {
                    appendLog("📥 ได้รับข้อมูลอาหาร ${foodsToSync.size} รายการ กำลังเขียนลง Health Connect...")
                    val (inserted, skipped) = hcManager.writeNutritionRecords(foodsToSync)
                    appendLog("✅ บันทึกอาหารลง Health Connect สำเร็จ: +$inserted รายการใหม่ (ข้ามที่ซ้ำ: $skipped)")
                    syncSucceeded = true
                } else {
                    appendLog("ℹ️ ไม่พบรายการอาหารใหม่ในรอบ 3 วันล่าสุด")
                }

                // ==========================================
                // ขั้นตอนที่ 3: ดึงข้อมูลน้ำหนักและ %ไขมัน (Xiaomi S400) เข้า Samsung Health
                // ==========================================
                appendLog("⚖️ [3/3] กำลังตรวจสอบค่าน้ำหนัก & %ไขมัน (Xiaomi S400) จาก Google Sheets...")
                val metricsToSync = if (!recentMetricsFromUpload.isNullOrEmpty()) {
                    recentMetricsFromUpload
                } else {
                    val metricResult = networkManager.fetchRecentBodyMetrics(url, secret, days = 7)
                    if (metricResult.isSuccess) metricResult.getOrNull() else null
                }

                if (!metricsToSync.isNullOrEmpty()) {
                    val sample = metricsToSync.lastOrNull()
                    appendLog("📥 ได้รับ ${metricsToSync.size} รายการ (ค่าล่าสุด: หนัก ${sample?.weightKg} kg, ไขมัน ${sample?.bodyFatPct}%)")
                    val (inserted, skipped) = hcManager.writeBodyMetricRecords(metricsToSync)
                    appendLog("✅ บันทึกน้ำหนัก & %ไขมันลง Health Connect สำเร็จ: +$inserted รายการใหม่ (ข้ามที่ซ้ำ: $skipped)")
                    appendLog("✨ Samsung Health จะแสดงค่าน้ำหนักและองค์ประกอบร่างกายให้อัตโนมัติ")
                    syncSucceeded = true
                } else {
                    appendLog("ℹ️ ไม่พบค่าน้ำหนักใหม่ในชีต Health_BodyMetrics")
                }

                if (syncSucceeded) {
                    getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("last_sync_time", System.currentTimeMillis())
                        .apply()
                    updateLastSyncDisplay()
                    Toast.makeText(this@MainActivity, "ซิงก์ 3-Way สำเร็จเรียบร้อย!", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                appendLog("❌ เกิดข้อผิดพลาดไม่คาดคิด: ${e.message}")
            } finally {
                binding.btnSyncNow.isEnabled = true
                binding.btnSyncNow.text = getString(R.string.btn_sync_now)
            }
        }
    }

    private fun scheduleBackgroundSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "GarminAutoSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun cancelBackgroundSync() {
        WorkManager.getInstance(this).cancelUniqueWork("GarminAutoSync")
    }

    private fun updateLastSyncDisplay() {
        val lastSync = getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
            .getLong("last_sync_time", 0L)

        if (lastSync > 0) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            binding.tvLastSync.text = "ซิงก์ล่าสุด: ${sdf.format(Date(lastSync))}"
        } else {
            binding.tvLastSync.text = "ซิงก์ล่าสุด: ยังไม่เคยซิงก์"
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val current = binding.tvLogOutput.text.toString()
        binding.tvLogOutput.text = "[$time] $msg\n$current"
    }
}
