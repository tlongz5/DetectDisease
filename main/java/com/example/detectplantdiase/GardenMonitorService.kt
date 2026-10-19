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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class GardenMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.detectplantdiase.ACTION_START_MONITOR"
        const val ACTION_STOP = "com.example.detectplantdiase.ACTION_STOP_MONITOR"
        const val ACTION_TRIGGER_NOW = "com.example.detectplantdiase.ACTION_TRIGGER_NOW"
        const val ACTION_ALARM_TICK = "com.example.detectplantdiase.ACTION_ALARM_TICK"

        const val BROADCAST_DETECTION_UPDATED = "com.example.detectplantdiase.DETECTION_UPDATED"
        const val BROADCAST_SERVICE_STATUS = "com.example.detectplantdiase.SERVICE_STATUS"
        const val BROADCAST_CAPTURE_FAILED = "com.example.detectplantdiase.CAPTURE_FAILED"
        const val EXTRA_ERROR = "error"

        const val SOURCE_ESP_MANUAL = "ESP32-CAM (Chụp ngay)"
        const val SOURCE_ESP_AUTO = "ESP32-CAM (Tự động)"

        const val CHANNEL_SERVICE_ID = "garden_monitor_service_channel"
        const val CHANNEL_ALERT_ID = "garden_disease_alert_channel"
        const val NOTIFICATION_SERVICE_ID = 2001
        const val NOTIFICATION_ALERT_ID = 2002

        const val ALARM_REQUEST_CODE = 3001

        // Chu ky ngan hon 1 phut (vd 30 giay) thi AlarmManager khong bao dung gio (Android gom/hoan bao thuc),
        // nen dem gio ngay trong service va giu CPU thuc
        private const val SHORT_INTERVAL_MS = 60_000L
        private const val LOOP_WAKELOCK_MS = 10 * 60_000L  // Moi lan hen lai gia han them, loi thi tu nha sau 10 phut

        // Chup 30 giay/lan thi khong bao benh lien tuc: chi bao khi tinh trang moi xuat hien, hoac nhac lai sau 30 phut
        private const val ALERT_REPEAT_MS = 30 * 60_000L

        /** Dang bat tu dong chup theo chu ky */
        @Volatile
        var isServiceRunning: Boolean = false
            private set

        private val capturing = AtomicBoolean(false)
        val isCapturing: Boolean get() = capturing.get()

        @Volatile
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

        /** Chup 1 anh tu ESP32-CAM ngay bay gio, khong anh huong lich tu dong chup */
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

    private var nextRunJob: Job? = null           // Hen gio chup cho chu ky ngan
    private var loopWakeLock: PowerManager.WakeLock? = null
    private var lastResultText = ""
    private var lastAlertStatus: String? = null
    private var lastAlertAt = 0L

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
        serviceScope.launch { AiEngine.load(this@GardenMonitorService) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: Android dong tien trinh roi mo lai service (START_STICKY)
        val action = intent?.action
            ?: if (ConfigManager.isMonitoringEnabled(this)) ACTION_START else ACTION_STOP

        when (action) {
            ACTION_START -> {
                cancelScheduledRuns()
                ConfigManager.setMonitoringEnabled(this, true)
                if (!promoteToForeground("Đang khởi động giám sát...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                isServiceRunning = true
                broadcastStatus()
                scheduleInitialOrNextRun()
            }

            ACTION_STOP -> {
                isServiceRunning = false
                ConfigManager.setMonitoringEnabled(this, false)
                cancelScheduledRuns()
                broadcastStatus()
                if (!isCapturing) stopServiceNow()  // Dang chup do thi chup xong moi dung
            }

            ACTION_TRIGGER_NOW -> {
                if (promoteToForeground("Đang chụp ảnh từ ESP32-CAM...")) {
                    executeCaptureAndInference(manualTrigger = true)
                } else if (!isServiceRunning && !isCapturing) {
                    stopSelf()
                }
            }

            ACTION_ALARM_TICK -> {
                // Tien trinh bi dong roi mo lai thi bien static mat, doc lai tu cau hinh
                if (!isServiceRunning && ConfigManager.isMonitoringEnabled(this)) isServiceRunning = true
                // Duoc goi bang startForegroundService nen luon phai len foreground, ke ca khi dung ngay
                val foreground = promoteToForeground("Đang tự động chụp ảnh theo lịch...")
                if (foreground && isServiceRunning) {
                    executeCaptureAndInference(manualTrigger = false)
                } else if (!isCapturing) {
                    isServiceRunning = false
                    stopServiceNow()
                }
            }
        }

        return START_STICKY
    }

    /**
     * Len foreground. Android 12+ co the tu choi khi app dang o nen (vd Android 15+ cam service dataSync chay
     * luc vua khoi dong may): luc do dung service, lan sau mo app MainActivity se bat lai tu dong chup.
     */
    private fun promoteToForeground(statusText: String): Boolean {
        val notification = buildOngoingNotification(statusText)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_SERVICE_ID, notification)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun stopServiceNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleInitialOrNextRun() {
        val startDelayMs = ConfigManager.startDelayToMillis(this)
        if (startDelayMs > 0L) {
            // Có thời gian hẹn giờ bắt đầu sau X giờ/phút
            val triggerTime = System.currentTimeMillis() + startDelayMs
            scheduleExactAlarm(triggerTime)
            updateOngoingNotification("Lần chụp đầu tiên lúc: ${formatClockTime(triggerTime)}")
        } else {
            // Chạy ngay lần đầu tiên
            executeCaptureAndInference(manualTrigger = false)
        }
    }

    /** Hen lan chup ke tiep, tinh tu luc bat dau lan chup truoc: 30 giay la 30 giay giua 2 lan bam may */
    @Synchronized
    private fun scheduleNextIntervalRun(startedAt: Long) {
        val intervalMs = ConfigManager.intervalToMillis(this)
        if (!isServiceRunning || intervalMs <= 0L) {
            nextRunTimeMillis = 0L
            broadcastStatus()
            return
        }

        val nextTime = maxOf(System.currentTimeMillis(), startedAt + intervalMs)
        if (intervalMs < SHORT_INTERVAL_MS) {
            scheduleInProcess(nextTime)
        } else {
            scheduleExactAlarm(nextTime)
        }
        val next = "Lần chụp kế tiếp: ${formatClockTime(nextTime)} (${ConfigManager.getModeDisplayName(this)})"
        updateOngoingNotification(if (lastResultText.isEmpty()) next else "$lastResultText\n$next")
        broadcastStatus()
    }

    private fun scheduleInProcess(triggerAtMillis: Long) {
        nextRunTimeMillis = triggerAtMillis
        val wakeLock = loopWakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DetectPlantDiase:ShortIntervalLoop")
            .apply { setReferenceCounted(false) }
            .also { loopWakeLock = it }
        wakeLock.acquire(LOOP_WAKELOCK_MS)  // Tat man hinh van dem gio va chup tiep

        nextRunJob?.cancel()
        nextRunJob = serviceScope.launch {
            delay(triggerAtMillis - System.currentTimeMillis())
            if (isServiceRunning) executeCaptureAndInference(manualTrigger = false)
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleExactAlarm(triggerAtMillis: Long) {
        nextRunTimeMillis = triggerAtMillis
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, GardenAlarmReceiver::class.java).apply { action = ACTION_ALARM_TICK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } catch (e: Exception) {
            // Fallback nếu máy không cho phép exact alarm
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    /** Huy moi lich chup dang cho (bao thuc AlarmManager va hen gio trong service) */
    @Synchronized
    private fun cancelScheduledRuns() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, GardenAlarmReceiver::class.java).apply { action = ACTION_ALARM_TICK },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        nextRunJob?.cancel()
        nextRunJob = null
        loopWakeLock?.let { if (it.isHeld) it.release() }
        nextRunTimeMillis = 0L
    }

    private fun executeCaptureAndInference(manualTrigger: Boolean) {
        if (!capturing.compareAndSet(false, true)) {
            // Dang chup do (vd bam "Chụp ngay" dung luc toi lich): khong chup chong, hen lan sau
            if (!manualTrigger) scheduleNextIntervalRun(System.currentTimeMillis())
            return
        }
        val startedAt = System.currentTimeMillis()
        broadcastStatus()

        serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DetectPlantDiase:CaptureWakeLock"
            )
            wakeLock.acquire(90_000L) // Giữ CPU chạy tối đa 90 giây để hoàn tất tải ảnh và inference

            try {
                updateOngoingNotification("Đang tải ảnh từ vườn (${ConfigManager.getCaptureUrl(this@GardenMonitorService)})...")
                val bmp = withContext(Dispatchers.IO) { EspClient.fetchImage(this@GardenMonitorService) }
                CaptureSound.playIfEnabled(this@GardenMonitorService)  // ESP vua chup xong: keu 1 tieng

                updateOngoingNotification("Đang phân tích AI (Lá & Quả)...")
                val (record, sent) = AiEngine.analyzeAndSave(
                    context = this@GardenMonitorService,
                    bitmap = bmp,
                    source = if (manualTrigger) SOURCE_ESP_MANUAL else SOURCE_ESP_AUTO,
                    sendToEsp = true
                )
                maybeSendDiseaseAlert(record)

                lastResultText = "Chụp xong lúc ${formatClockTime(System.currentTimeMillis())} " +
                    "(${record.overallTitle}) • LCD: $sent"
                updateOngoingNotification(lastResultText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                lastResultText = "⚠️ Lỗi chụp lúc ${formatClockTime(System.currentTimeMillis())}: ${e.message}"
                updateOngoingNotification(lastResultText)
                sendBroadcast(
                    Intent(BROADCAST_CAPTURE_FAILED).setPackage(packageName)
                        .putExtra(EXTRA_ERROR, e.message ?: "Không rõ lỗi")
                )
            } finally {
                capturing.set(false)
                if (wakeLock.isHeld) wakeLock.release()

                when {
                    !isServiceRunning -> stopServiceNow()  // Chup ngay khi dang tat tu dong chup: xong thi dung
                    !manualTrigger -> scheduleNextIntervalRun(startedAt)
                    else -> broadcastStatus()
                }
            }
        }
    }

    private fun maybeSendDiseaseAlert(record: DetectionRecord) {
        if (record.overallStatus != "DISEASED" && record.overallStatus != "WARNING") {
            lastAlertStatus = null
            return
        }
        val now = System.currentTimeMillis()
        if (record.overallStatus != lastAlertStatus || now - lastAlertAt >= ALERT_REPEAT_MS) {
            sendDiseaseAlertNotification(record)
            lastAlertStatus = record.overallStatus
            lastAlertAt = now
        }
    }

    private fun buildOngoingNotification(statusText: String): Notification {
        // Mo lai dung man Trang chu dang co, khong tao them 1 ban moi chong len
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GardenMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, GardenMonitorService::class.java).apply { action = ACTION_TRIGGER_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ip = ConfigManager.getIp(this)
        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_eco)
            .setContentTitle(if (isServiceRunning) "Tự động giám sát vườn ($ip)" else "Chụp ảnh từ vườn ($ip)")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_photo_camera, "Chụp ngay", triggerPendingIntent)
        if (isServiceRunning) builder.addAction(R.drawable.ic_close, "Dừng", stopPendingIntent)
        return builder.build()
    }

    private fun updateOngoingNotification(statusText: String) {
        if (isServiceRunning || isCapturing) {
            notificationManager.notify(NOTIFICATION_SERVICE_ID, buildOngoingNotification(statusText))
        }
    }

    private fun sendDiseaseAlertNotification(record: DetectionRecord) {
        // Bam thong bao mo man Ket qua, bam quay lai thi ve Trang chu (khong thoat app)
        val openDetailIntent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_RECORD_ID, record.id)
        }
        val pendingIntent = TaskStackBuilder.create(this)
            .addNextIntentWithParentStack(openDetailIntent)
            .getPendingIntent(record.id.hashCode(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_eco)
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

    private fun broadcastStatus() {
        sendBroadcast(Intent(BROADCAST_SERVICE_STATUS).setPackage(packageName))
    }

    private fun formatClockTime(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

    override fun onDestroy() {
        isServiceRunning = false
        cancelScheduledRuns()
        broadcastStatus()
        serviceScope.cancel()
        // Model AI dung chung (AiEngine) nen khong dong o day: man Camera co the dang dung
        super.onDestroy()
    }
}
