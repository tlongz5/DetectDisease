package com.example.detectplantdiase

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GardenMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.detectplantdiase.ACTION_START_MONITOR"
        const val ACTION_STOP = "com.example.detectplantdiase.ACTION_STOP_MONITOR"
        const val ACTION_TRIGGER_NOW = "com.example.detectplantdiase.ACTION_TRIGGER_NOW"
        const val ACTION_ALARM_TICK = "com.example.detectplantdiase.ACTION_ALARM_TICK"

        const val BROADCAST_DETECTION_UPDATED = "com.example.detectplantdiase.DETECTION_UPDATED"
        const val BROADCAST_SERVICE_STATUS = "com.example.detectplantdiase.SERVICE_STATUS"

        const val CHANNEL_SERVICE_ID = "garden_monitor_service_channel"
        const val CHANNEL_ALERT_ID = "garden_disease_alert_channel"
        const val NOTIFICATION_SERVICE_ID = 2001
        const val NOTIFICATION_ALERT_ID = 2002

        const val ALARM_REQUEST_CODE = 3001

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var isCapturing: Boolean = false
            private set

        var nextRunTimeMillis: Long = 0L
            private set

        fun startMonitoring(context: Context) {
            val intent = Intent(context, GardenMonitorService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stopMonitoring(context: Context) {
            val intent = Intent(context, GardenMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun triggerNow(context: Context) {
            val intent = Intent(context, GardenMonitorService::class.java).apply {
                action = ACTION_TRIGGER_NOW
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var leafModel: LeafDiseaseClassifier? = null
    private var fruitModel: FreshnessClassifier? = null
    @Volatile private var modelsLoaded = false

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        loadAiModels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                isServiceRunning = true
                promoteToForeground("Đang khởi động giám sát...")
                broadcastStatus()
                scheduleInitialOrNextRun(isFirstRun = true)
            }

            ACTION_STOP -> {
                isServiceRunning = false
                cancelAlarm()
                broadcastStatus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TRIGGER_NOW -> {
                isServiceRunning = true
                promoteToForeground("Đang chụp ảnh từ ESP32-CAM...")
                executeCaptureAndInference(manualTrigger = true)
            }

            ACTION_ALARM_TICK -> {
                if (isServiceRunning) {
                    promoteToForeground("Đang tự động chụp ảnh theo lịch...")
                    executeCaptureAndInference(manualTrigger = false)
                }
            }
        }

        return START_STICKY
    }

    private fun loadAiModels() {
        serviceScope.launch(Dispatchers.Default) {
            leafModel = try {
                LeafDiseaseClassifier(this@GardenMonitorService)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            fruitModel = try {
                FreshnessClassifier(this@GardenMonitorService)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            modelsLoaded = true
        }
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildOngoingNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_SERVICE_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_SERVICE_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_SERVICE_ID, notification)
        }
    }

    private fun scheduleInitialOrNextRun(isFirstRun: Boolean) {
        val mode = ConfigManager.getMode(this)
        val startDelayMs = ConfigManager.startDelayToMillis(this)

        if (isFirstRun && startDelayMs > 0L) {
            // Có thời gian hẹn giờ bắt đầu sau X giờ/phút
            val triggerTime = System.currentTimeMillis() + startDelayMs
            scheduleExactAlarm(triggerTime)
            val timeStr = formatClockTime(triggerTime)
            updateOngoingNotification("Lần chụp đầu tiên lúc: $timeStr")
        } else {
            // Chạy ngay lần đầu tiên
            executeCaptureAndInference(manualTrigger = (mode == ConfigManager.MODE_INSTANT))
        }
    }

    private fun scheduleNextIntervalRun() {
        val mode = ConfigManager.getMode(this)
        if (mode == ConfigManager.MODE_INSTANT) {
            updateOngoingNotification("Chế độ: Chụp ngay lập tức (Đã hoàn tất)")
            nextRunTimeMillis = 0L
            broadcastStatus()
            return
        }

        val intervalMs = ConfigManager.intervalToMillis(this)
        if (intervalMs > 0L && isServiceRunning) {
            val nextTime = System.currentTimeMillis() + intervalMs
            scheduleExactAlarm(nextTime)
            val timeStr = formatClockTime(nextTime)
            val modeTitle = ConfigManager.getModeDisplayName(this)
            updateOngoingNotification("Lần chụp kế tiếp lúc: $timeStr ($modeTitle)")
        } else {
            updateOngoingNotification("Đã hoàn tất lịch chụp tự động")
            nextRunTimeMillis = 0L
        }
        broadcastStatus()
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleExactAlarm(triggerAtMillis: Long) {
        nextRunTimeMillis = triggerAtMillis
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(this, GardenAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TICK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: Exception) {
            // Fallback nếu máy không cho phép exact alarm
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, GardenAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TICK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        nextRunTimeMillis = 0L
    }

    private fun executeCaptureAndInference(manualTrigger: Boolean) {
        if (isCapturing) return
        isCapturing = true

        serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DetectPlantDiase:CaptureWakeLock"
            )
            wakeLock.acquire(90_000L) // Giữ CPU chạy tối đa 90 giây để hoàn tất tải ảnh và inference

            try {
                // Đợi model nạp xong nếu đang nạp
                var retryCount = 0
                while (!modelsLoaded && retryCount < 15) {
                    kotlinx.coroutines.delay(500)
                    retryCount++
                }

                val espUrl = ConfigManager.getBaseUrl(this@GardenMonitorService)
                val captureUrl = ConfigManager.getCaptureUrl(this@GardenMonitorService)
                updateOngoingNotification("Đang tải ảnh từ vườn ($captureUrl)...")

                val bmp = fetchEspImage(captureUrl)
                updateOngoingNotification("Đang phân tích AI (Lá & Quả)...")

                val t0 = System.currentTimeMillis()
                val leafResult = leafModel?.analyze(bmp)
                val fruitResult = fruitModel?.predict(bmp)
                val ms = System.currentTimeMillis() - t0

                val lcd = leafResult?.lcdText()
                if (lcd != null) {
                    sendResultToEsp(espUrl, lcd)
                }

                // Lưu vào nhật ký HistoryManager
                val record = HistoryManager.saveDetection(
                    context = this@GardenMonitorService,
                    bitmap = bmp,
                    leafResult = leafResult,
                    fruitResult = fruitResult,
                    source = if (manualTrigger) "ESP32-CAM (Chụp ngay)" else "ESP32-CAM (Tự động)",
                    inferenceTimeMs = ms,
                    lcdText = lcd
                )

                // Phát broadcast để màn hình Daily/Main cập nhật giao diện ngay
                sendBroadcast(Intent(BROADCAST_DETECTION_UPDATED))

                // Nếu phát hiện bệnh hoặc cảnh báo, gửi thông báo khẩn cấp
                if (record.overallStatus == "DISEASED" || record.overallStatus == "WARNING") {
                    sendDiseaseAlertNotification(record)
                }

                val currentTimeStr = formatClockTime(System.currentTimeMillis())
                updateOngoingNotification("Chụp xong lúc $currentTimeStr (${record.overallTitle})")

            } catch (e: Exception) {
                e.printStackTrace()
                val currentTimeStr = formatClockTime(System.currentTimeMillis())
                updateOngoingNotification("⚠️ Lỗi chụp lúc $currentTimeStr: ${e.message}")
            } finally {
                isCapturing = false
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }

                // Lên lịch lần chụp tiếp theo (nếu chu kỳ > 0)
                if (!manualTrigger) {
                    scheduleNextIntervalRun()
                }
            }
        }
    }

    private fun fetchEspImage(captureUrl: String): Bitmap {
        val conn = URL(captureUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 25000
        try {
            return conn.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("ESP32-CAM không trả về ảnh")
        } finally {
            conn.disconnect()
        }
    }

    private fun sendResultToEsp(espUrl: String, text: String): String = try {
        val conn = URL("$espUrl/result").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "text/plain")
        try {
            conn.outputStream.use { it.write(text.toByteArray()) }
            when (conn.responseCode) {
                200 -> "đã gửi về Nano"
                else -> "lỗi code ${conn.responseCode}"
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "lỗi: ${e.message}"
    }

    private fun buildOngoingNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, DailyActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GardenMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerIntent = Intent(this, GardenMonitorService::class.java).apply {
            action = ACTION_TRIGGER_NOW
        }
        val triggerPendingIntent = PendingIntent.getService(
            this,
            2,
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ip = ConfigManager.getIp(this)

        return NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🌿 Tự động giám sát vườn cây ($ip)")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_camera, "Chụp ngay", triggerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng", stopPendingIntent)
            .build()
    }

    private fun updateOngoingNotification(statusText: String) {
        if (isServiceRunning) {
            val notification = buildOngoingNotification(statusText)
            notificationManager.notify(NOTIFICATION_SERVICE_ID, notification)
        }
    }

    private fun sendDiseaseAlertNotification(record: DetectionRecord) {
        val openDetailIntent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_RECORD_ID, record.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            record.id.hashCode(),
            openDetailIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(record.overallTitle)
            .setContentText(record.overallSummary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${record.overallSummary}\n\n👉 Lời khuyên:\n${record.advice}")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        notificationManager.notify(NOTIFICATION_ALERT_ID, alertNotification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Kênh 1: Cho Foreground Service (chạy ngầm, không kêu)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Giám sát Vườn cây (Chạy ngầm)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị trạng thái hoạt động tự động chụp ảnh từ vườn"
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Kênh 2: Cho Cảnh báo Bệnh hại (có chuông và rung)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "Cảnh báo Bệnh hại Cây trồng",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khẩn cấp khi phát hiện dấu hiệu bệnh lá hoặc thối quả"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun broadcastStatus() {
        val intent = Intent(BROADCAST_SERVICE_STATUS).apply {
            putExtra("isRunning", isServiceRunning)
            putExtra("nextRun", nextRunTimeMillis)
        }
        sendBroadcast(intent)
    }

    private fun formatClockTime(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        cancelAlarm()
        broadcastStatus()
        serviceScope.cancel()
        leafModel?.close()
        fruitModel?.close()
    }
}
