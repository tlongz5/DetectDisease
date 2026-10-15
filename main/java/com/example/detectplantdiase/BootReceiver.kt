package com.example.detectplantdiase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver tự động khởi động lại dịch vụ giám sát khi điện thoại khởi động lại máy.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val interval = ConfigManager.getInterval(context)
            if (interval > 0f) {
                GardenMonitorService.startMonitoring(context)
            }
        }
    }
}
