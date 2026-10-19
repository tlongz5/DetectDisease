package com.example.detectplantdiase

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Quản lý lưu trữ và truy xuất cấu hình IP ESP32-CAM và chế độ chụp ảnh
 */
object ConfigManager {
    private const val PREF_NAME = "garden_cam_config"
    private const val KEY_IP = "esp_ip"
    private const val KEY_START_DELAY = "start_delay"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_TIME_UNIT = "time_unit"
    private const val KEY_CAPTURE_MODE = "capture_mode"

    // 4 Chế độ chụp (Modes)
    const val MODE_INSTANT = "MODE_INSTANT"       // Mode 1: Chụp ngay lập tức (không cần đợi)
    const val MODE_1_MINUTE = "MODE_1_MINUTE"     // Mode 2: Sau 1 phút (chu kỳ 1 phút)
    const val MODE_1_HOUR = "MODE_1_HOUR"         // Mode 3: Sau 1 giờ (chu kỳ 1 giờ)
    const val MODE_1_DAY = "MODE_1_DAY"           // Mode 4: Sau 1 ngày (chu kỳ 1 ngày)
    const val MODE_CUSTOM = "MODE_CUSTOM"         // Mode tùy chỉnh khác

    const val UNIT_MINUTES = "MINUTES"
    const val UNIT_HOURS = "HOURS"
    const val UNIT_DAYS = "DAYS"

    const val DEFAULT_IP = "192.168.100.102"

    // ESP32-CAM tu phat WiFi "TuoiCay-CAM" voi IP co dinh. Dien thoai vao mang nay thi app tu goi dia chi nay
    const val ESP_AP_IP = "192.168.4.1"
    private const val ESP_AP_PREFIX = "192.168.4."
    const val DEFAULT_START_DELAY = 0f
    const val DEFAULT_INTERVAL = 1f
    const val DEFAULT_MODE = MODE_INSTANT

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveConfig(
        context: Context,
        ip: String,
        interval: Float,
        timeUnit: String = UNIT_HOURS,
        mode: String = MODE_1_HOUR,
        startDelay: Float = 0f
    ) {
        getPrefs(context).edit().apply {
            putString(KEY_IP, normalizeIp(ip))
            putFloat(KEY_INTERVAL, interval)
            putString(KEY_TIME_UNIT, timeUnit)
            putString(KEY_CAPTURE_MODE, mode)
            putFloat(KEY_START_DELAY, startDelay)
            apply()
        }
    }

    fun getIp(context: Context): String {
        val ip = getPrefs(context).getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        return if (ip.isBlank()) DEFAULT_IP else normalizeIp(ip)
    }

    fun getMode(context: Context): String {
        return getPrefs(context).getString(KEY_CAPTURE_MODE, DEFAULT_MODE) ?: DEFAULT_MODE
    }

    fun getStartDelay(context: Context): Float {
        return getPrefs(context).getFloat(KEY_START_DELAY, DEFAULT_START_DELAY)
    }

    fun getInterval(context: Context): Float {
        return getPrefs(context).getFloat(KEY_INTERVAL, DEFAULT_INTERVAL)
    }

    fun getTimeUnit(context: Context): String {
        return getPrefs(context).getString(KEY_TIME_UNIT, UNIT_HOURS) ?: UNIT_HOURS
    }

    fun getUnitDisplayName(unit: String): String {
        return when (unit) {
            UNIT_MINUTES -> "phút"
            UNIT_DAYS -> "ngày"
            else -> "giờ"
        }
    }

    fun getModeDisplayName(context: Context): String {
        return when (getMode(context)) {
            MODE_INSTANT -> "⚡ Chụp ngay lập tức"
            MODE_1_MINUTE -> "⏱️ Sau mỗi 1 phút"
            MODE_1_HOUR -> "🕒 Sau mỗi 1 giờ"
            MODE_1_DAY -> "📅 Sau mỗi 1 ngày"
            else -> {
                val interval = getInterval(context)
                val unit = getTimeUnit(context)
                val unitName = getUnitDisplayName(unit)
                val numStr = if (interval % 1.0f == 0f) interval.toInt().toString() else interval.toString()
                "⚙️ Tùy chỉnh: $numStr $unitName"
            }
        }
    }

    fun getIntervalDisplayText(context: Context): String {
        return getModeDisplayName(context)
    }

    fun normalizeIp(input: String): String {
        return input.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
    }

    /** Mang WiFi dien thoai dang noi, null neu khong noi WiFi */
    @Suppress("DEPRECATION") // allNetworks van chay tren Android moi; cach thay the phai dang ky callback
    private fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** Dien thoai dang vao thang WiFi TuoiCay-CAM cua ESP32-CAM: IP WiFi cua dien thoai co dang 192.168.4.x */
    fun isOnEspAp(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = wifiNetwork(context) ?: return false
        return cm.getLinkProperties(net)?.linkAddresses?.any {
            it.address.hostAddress?.startsWith(ESP_AP_PREFIX) == true
        } == true
    }

    /**
     * Mo ket noi HTTP toi ESP32-CAM, luon di qua WiFi. Mang TuoiCay-CAM khong co Internet nen Android van coi 4G la
     * mang chinh: ket noi khong gan vao WiFi se di qua 4G va khong bao gio toi duoc ESP.
     */
    fun openEsp(context: Context, url: String): HttpURLConnection {
        val u = URL(url)
        val net = wifiNetwork(context)
        return (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }

    /**
     * URL cơ sở để gọi API, ví dụ: "http://192.168.1.50".
     * Đang vào WiFi TuoiCay-CAM thì luôn là http://192.168.4.1, không phụ thuộc IP đã lưu.
     */
    fun getBaseUrl(context: Context): String {
        if (isOnEspAp(context)) return "http://$ESP_AP_IP"
        val ip = getIp(context)
        return "http://$ip"
    }

    /**
     * URL API chụp ảnh, không bật đèn flash. Muốn bật flash (trong nhà, thiếu sáng) thì thêm "?flash=1".
     */
    fun getCaptureUrl(context: Context): String {
        return "${getBaseUrl(context)}/capture"
    }

    /**
     * Quy đổi thời gian trễ bắt đầu sang mili-giây
     */
    fun startDelayToMillis(context: Context): Long {
        if (getMode(context) == MODE_INSTANT) return 0L
        val delay = getStartDelay(context)
        val unit = getTimeUnit(context)
        return when (unit) {
            UNIT_MINUTES -> (delay * 60 * 1000).toLong()
            UNIT_DAYS -> (delay * 24 * 3600 * 1000).toLong()
            else -> (delay * 3600 * 1000).toLong()
        }
    }

    /**
     * Quy đổi chu kỳ lặp lại sang mili-giây
     */
    fun intervalToMillis(context: Context): Long {
        return when (getMode(context)) {
            MODE_INSTANT -> 0L // Không lặp định kỳ, chỉ chụp ngay
            MODE_1_MINUTE -> 60 * 1000L
            MODE_1_HOUR -> 3600 * 1000L
            MODE_1_DAY -> 24 * 3600 * 1000L
            else -> {
                val interval = getInterval(context)
                val unit = getTimeUnit(context)
                when (unit) {
                    UNIT_MINUTES -> (interval * 60 * 1000).toLong()
                    UNIT_DAYS -> (interval * 24 * 3600 * 1000).toLong()
                    else -> (interval * 3600 * 1000).toLong()
                }
            }
        }
    }
}
