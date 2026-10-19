package com.example.detectplantdiase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject

/** Cac lenh goi HTTP toi ESP32-CAM. Co dung mang nen phai goi o luong nen (Dispatchers.IO). */
object EspClient {

    /** Tinh trang dat ESP gui len /status (Nano bao qua UART moi 5 giay). null = chua co so lieu */
    data class Soil(val moisture: Int, val pump: Boolean, val mist: Boolean, val fault: Boolean)

    data class Status(val reachable: Boolean, val error: String? = null, val soil: Soil? = null)

    /** Tai 1 anh JPEG tu /capture. Co gioi han thoi gian de app khong bi treo khi ESP32-CAM mat mang. */
    fun fetchImage(context: Context, captureUrl: String = ConfigManager.getCaptureUrl(context)): Bitmap {
        val conn = ConfigManager.openEsp(context, captureUrl)
        conn.connectTimeout = 7000
        conn.readTimeout = 25000
        try {
            return conn.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("ESP32-CAM không trả về ảnh")
        } finally {
            conn.disconnect()
        }
    }

    /** Goi thu GET <baseUrl>/ : ESP tra loi (ke ca 404) la mang da thong */
    fun ping(context: Context, baseUrl: String): Status = try {
        val conn = ConfigManager.openEsp(context, "$baseUrl/")
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            Status(reachable = conn.responseCode in 200..499)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Status(reachable = false, error = e.message ?: "Không phản hồi")
    }

    /** Doc /status: ESP co tra loi khong, kem do am dat / bom / phun suong neu Nano da gui len */
    fun fetchStatus(context: Context): Status {
        try {
            val conn = ConfigManager.openEsp(context, "${ConfigManager.getBaseUrl(context)}/status")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            try {
                // Firmware cu chua co /status (404) nhung van la ESP dang tra loi
                if (conn.responseCode != 200) return Status(reachable = conn.responseCode in 200..499)
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val soil = json.optJSONObject("soil")?.let {
                    Soil(
                        moisture = it.optInt("moisture"),
                        pump = it.optBoolean("pump"),
                        mist = it.optBoolean("mist"),
                        fault = it.optBoolean("fault")
                    )
                }
                return Status(reachable = true, soil = soil)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return Status(reachable = false, error = e.message ?: "Không phản hồi")
        }
    }
}
