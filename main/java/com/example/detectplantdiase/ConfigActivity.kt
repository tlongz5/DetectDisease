package com.example.detectplantdiase

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.detectplantdiase.databinding.ActivityConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Tiến hành khởi động service dù được cấp quyền hay không
        startServiceAndNavigate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedConfig()
        setupListeners()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun loadSavedConfig() {
        val savedIp = ConfigManager.getIp(this)
        val savedMode = ConfigManager.getMode(this)
        val savedInterval = ConfigManager.getInterval(this)
        val savedUnit = ConfigManager.getTimeUnit(this)

        binding.edtIpAddress.setText(savedIp)
        binding.edtInterval.setText(formatNumber(savedInterval))

        when (savedMode) {
            ConfigManager.MODE_INSTANT -> binding.rbModeInstant.isChecked = true
            ConfigManager.MODE_1_MINUTE -> binding.rbMode1Minute.isChecked = true
            ConfigManager.MODE_1_DAY -> binding.rbMode1Day.isChecked = true
            ConfigManager.MODE_CUSTOM -> {
                binding.rbModeCustom.isChecked = true
                binding.layoutCustomInterval.visibility = View.VISIBLE
            }
            else -> binding.rbMode1Hour.isChecked = true
        }

        when (savedUnit) {
            ConfigManager.UNIT_MINUTES -> binding.rbUnitMinutes.isChecked = true
            ConfigManager.UNIT_DAYS -> binding.rbUnitDays.isChecked = true
            else -> binding.rbUnitHours.isChecked = true
        }

        updateModeDescription(savedMode)
    }

    private fun formatNumber(value: Float): String {
        return if (value % 1.0f == 0f) {
            value.toInt().toString()
        } else {
            value.toString()
        }
    }

    private fun updateModeDescription(mode: String) {
        when (mode) {
            ConfigManager.MODE_INSTANT -> {
                binding.tvModeDescription.text = "⚡ Chế độ Chụp ngay lập tức: Hệ thống sẽ gọi lệnh GET chụp ảnh và phân tích AI ngay khi bạn bấm Xác nhận mà không cần chờ đợi!"
            }
            ConfigManager.MODE_1_MINUTE -> {
                binding.tvModeDescription.text = "⏱️ Chế độ Sau 1 phút: Chụp ngay lần đầu, sau đó tự động lặp lại mỗi 1 phút (rất tiện để thử nghiệm chức năng)."
            }
            ConfigManager.MODE_1_HOUR -> {
                binding.tvModeDescription.text = "🕒 Chế độ Sau 1 giờ: Chụp ngay lần đầu, sau đó tự động lặp lại đều đặn mỗi 1 giờ để theo dõi tình trạng cây."
            }
            ConfigManager.MODE_1_DAY -> {
                binding.tvModeDescription.text = "📅 Chế độ Sau 1 ngày: Chụp ngay lần đầu, sau đó tự động chụp 1 lần mỗi ngày (24 giờ), tiết kiệm tối đa pin."
            }
            else -> {
                binding.tvModeDescription.text = "⚙️ Chế độ Tùy chỉnh: Nhập số thời gian và đơn vị bạn mong muốn ở bên dưới."
            }
        }
    }

    private fun setupListeners() {
        binding.rgModes.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeInstant -> {
                    binding.layoutCustomInterval.visibility = View.GONE
                    updateModeDescription(ConfigManager.MODE_INSTANT)
                }
                R.id.rbMode1Minute -> {
                    binding.layoutCustomInterval.visibility = View.GONE
                    updateModeDescription(ConfigManager.MODE_1_MINUTE)
                }
                R.id.rbMode1Hour -> {
                    binding.layoutCustomInterval.visibility = View.GONE
                    updateModeDescription(ConfigManager.MODE_1_HOUR)
                }
                R.id.rbMode1Day -> {
                    binding.layoutCustomInterval.visibility = View.GONE
                    updateModeDescription(ConfigManager.MODE_1_DAY)
                }
                R.id.rbModeCustom -> {
                    binding.layoutCustomInterval.visibility = View.VISIBLE
                    updateModeDescription(ConfigManager.MODE_CUSTOM)
                }
            }
        }

        binding.btnTestConnection.setOnClickListener {
            val ip = binding.edtIpAddress.text?.toString()?.trim().orEmpty()
            if (ip.isBlank()) {
                binding.tilIpAddress.error = "Vui lòng nhập địa chỉ IP"
                return@setOnClickListener
            }
            binding.tilIpAddress.error = null
            testEspConnection(ip)
        }

        binding.btnOk.setOnClickListener {
            saveAndProceed()
        }
    }

    private fun testEspConnection(ip: String) {
        binding.tvConnectionStatus.text = "Đang kiểm tra kết nối tới ESP32-CAM..."
        binding.tvConnectionStatus.setTextColor(Color.parseColor("#757575"))
        btnSetEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val cleanIp = ConfigManager.normalizeIp(ip)
            var success = false
            var errorMsg = ""

            try {
                val conn = ConfigManager.openEsp(this@ConfigActivity, "http://$cleanIp/")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                val responseCode = conn.responseCode
                conn.disconnect()
                success = responseCode in 200..499
            } catch (e: Exception) {
                errorMsg = e.message ?: "Không phản hồi"
            }

            withContext(Dispatchers.Main) {
                btnSetEnabled(true)
                if (success) {
                    binding.tvConnectionStatus.text = "🟢 Kết nối thành công tới ESP32-CAM"
                    binding.tvConnectionStatus.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    binding.tvConnectionStatus.text = "🔴 Không kết nối được ($errorMsg)"
                    binding.tvConnectionStatus.setTextColor(Color.parseColor("#D32F2F"))
                }
            }
        }
    }

    private fun btnSetEnabled(enabled: Boolean) {
        binding.btnTestConnection.isEnabled = enabled
    }

    private fun saveAndProceed() {
        val ip = binding.edtIpAddress.text?.toString()?.trim().orEmpty()

        if (ip.isBlank()) {
            binding.tilIpAddress.error = "Vui lòng nhập địa chỉ IP"
            binding.edtIpAddress.requestFocus()
            return
        }
        binding.tilIpAddress.error = null

        val selectedMode: String
        val interval: Float
        val unit: String

        when (binding.rgModes.checkedRadioButtonId) {
            R.id.rbModeInstant -> {
                selectedMode = ConfigManager.MODE_INSTANT
                interval = 0f
                unit = ConfigManager.UNIT_MINUTES
            }
            R.id.rbMode1Minute -> {
                selectedMode = ConfigManager.MODE_1_MINUTE
                interval = 1f
                unit = ConfigManager.UNIT_MINUTES
            }
            R.id.rbMode1Day -> {
                selectedMode = ConfigManager.MODE_1_DAY
                interval = 1f
                unit = ConfigManager.UNIT_DAYS
            }
            R.id.rbModeCustom -> {
                selectedMode = ConfigManager.MODE_CUSTOM
                val intervalStr = binding.edtInterval.text?.toString()?.trim().orEmpty()
                val parsed = intervalStr.toFloatOrNull()
                if (parsed == null || parsed <= 0) {
                    binding.tilInterval.error = "Vui lòng nhập số thời gian > 0"
                    binding.edtInterval.requestFocus()
                    return
                }
                binding.tilInterval.error = null
                interval = parsed
                unit = when {
                    binding.rbUnitMinutes.isChecked -> ConfigManager.UNIT_MINUTES
                    binding.rbUnitDays.isChecked -> ConfigManager.UNIT_DAYS
                    else -> ConfigManager.UNIT_HOURS
                }
            }
            else -> { // Default: rbMode1Hour
                selectedMode = ConfigManager.MODE_1_HOUR
                interval = 1f
                unit = ConfigManager.UNIT_HOURS
            }
        }

        // Lưu cấu hình
        ConfigManager.saveConfig(
            context = this,
            ip = ip,
            interval = interval,
            timeUnit = unit,
            mode = selectedMode,
            startDelay = 0f
        )

        val modeName = ConfigManager.getModeDisplayName(this)
        Toast.makeText(this, "Đã chọn: $modeName", Toast.LENGTH_SHORT).show()

        // Kiểm tra quyền thông báo trên Android 13+ (để hiển thị Foreground Service & Alert)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServiceAndNavigate()
        }
    }

    private fun startServiceAndNavigate() {
        // Khởi động Foreground Service
        GardenMonitorService.startMonitoring(this)

        val intent = Intent(this, DailyActivity::class.java)
        startActivity(intent)
        finish()
    }
}
