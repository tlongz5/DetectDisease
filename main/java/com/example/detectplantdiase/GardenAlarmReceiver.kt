package com.example.detectplantdiase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Receiver nhận tín hiệu hẹn giờ chính xác từ AlarmManager
 * Đánh thức hoặc chuyển lệnh tới GardenMonitorService để thực hiện GET ảnh và phân tích AI.
 */
class GardenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, GardenMonitorService::class.java).apply {
            action = GardenMonitorService.ACTION_ALARM_TICK
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
