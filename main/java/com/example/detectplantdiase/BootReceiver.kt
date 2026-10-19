package com.example.detectplantdiase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver tự động khởi động lại dịch vụ giám sát khi điện thoại khởi động lại máy,
 * nếu trước đó người dùng đang bật tự động chụp.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && ConfigManager.isMonitoringEnabled(context)) {
            GardenMonitorService.startMonitoring(context)
        }
    }
}
