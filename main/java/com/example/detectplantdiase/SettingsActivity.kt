package com.example.detectplantdiase

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.detectplantdiase.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cai dat: IP ESP32-CAM va chu ky tu dong chup. Luu xong quay ve Trang chu */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.applySystemBarPadding(top = true)
        binding.bottomBar.applySystemBarPadding(bottom = true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        loadSavedConfig()

        binding.rgModes.setOnCheckedChangeListener { _, checkedId -> updateModeUi(modeFor(checkedId)) }
        // Bat len thi keu thu 1 tieng de nghe am luong
        binding.switchCaptureSound.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) CaptureSound.play()
        }
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { saveAndFinish() }
    }

    private fun loadSavedConfig() {
        binding.edtIpAddress.setText(ConfigManager.getIp(this))
        binding.edtInterval.setText(formatNumber(ConfigManager.getInterval(this)))
        binding.switchCaptureSound.isChecked = ConfigManager.isCaptureSoundEnabled(this)

        val mode = ConfigManager.getMode(this)
        binding.rgModes.check(
            when (mode) {
                ConfigManager.MODE_30_SECONDS -> R.id.rbMode30s
                ConfigManager.MODE_1_MINUTE -> R.id.rbMode1Minute
                ConfigManager.MODE_1_DAY -> R.id.rbMode1Day
                ConfigManager.MODE_CUSTOM -> R.id.rbModeCustom
                else -> R.id.rbMode1Hour
            }
        )
        binding.toggleUnit.check(
            when (ConfigManager.getTimeUnit(this)) {
                ConfigManager.UNIT_MINUTES -> R.id.btnUnitMinutes
                ConfigManager.UNIT_DAYS -> R.id.btnUnitDays
                else -> R.id.btnUnitHours
            }
        )
        updateModeUi(mode)
    }

    private fun formatNumber(value: Float): String =
        if (value % 1.0f == 0f) value.toInt().toString() else value.toString()

    private fun modeFor(radioId: Int): String = when (radioId) {
        R.id.rbMode30s -> ConfigManager.MODE_30_SECONDS
        R.id.rbMode1Minute -> ConfigManager.MODE_1_MINUTE
        R.id.rbMode1Day -> ConfigManager.MODE_1_DAY
        R.id.rbModeCustom -> ConfigManager.MODE_CUSTOM
        else -> ConfigManager.MODE_1_HOUR
    }

    private fun updateModeUi(mode: String) {
        binding.layoutCustomInterval.isVisible = mode == ConfigManager.MODE_CUSTOM
        binding.tvModeDescription.text = when (mode) {
            ConfigManager.MODE_30_SECONDS ->
                "Chụp ngay lần đầu, sau đó cứ 30 giây chụp 1 ảnh và gửi kết quả lên LCD. Tốn pin và pin nóng hơn: nên cắm sạc cho điện thoại."
            ConfigManager.MODE_1_MINUTE ->
                "Chụp ngay lần đầu, sau đó lặp lại mỗi 1 phút. Tiện để thử hệ thống."
            ConfigManager.MODE_1_HOUR ->
                "Chụp ngay lần đầu, sau đó lặp lại mỗi 1 giờ để theo dõi tình trạng cây."
            ConfigManager.MODE_1_DAY ->
                "Chụp ngay lần đầu, sau đó chụp 1 lần mỗi ngày, tiết kiệm pin nhất."
            else -> "Nhập số thời gian và chọn đơn vị ở trên."
        }
    }

    /** Dang vao WiFi TuoiCay-CAM thi app luon goi 192.168.4.1 (getBaseUrl), nen kiem tra dung dia chi do */
    private fun testConnection() {
        val ip = ConfigManager.normalizeIp(binding.edtIpAddress.text?.toString().orEmpty())
        val onEspAp = ConfigManager.isOnEspAp(this)
        if (ip.isBlank() && !onEspAp) {
            binding.tilIpAddress.error = "Vui lòng nhập địa chỉ IP"
            return
        }
        binding.tilIpAddress.error = null
        val baseUrl = if (onEspAp) "http://${ConfigManager.ESP_AP_IP}" else "http://$ip"

        binding.btnTestConnection.isEnabled = false
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvConnectionStatus.text = "Đang kiểm tra…"

        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { EspClient.ping(this@SettingsActivity, baseUrl) }
            binding.btnTestConnection.isEnabled = true
            if (status.reachable) {
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_connected))
                binding.tvConnectionStatus.text = if (onEspAp) {
                    "Kết nối được qua WiFi TuoiCay-CAM (${ConfigManager.ESP_AP_IP})"
                } else {
                    "Kết nối thành công tới $ip"
                }
            } else {
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.status_error))
                binding.tvConnectionStatus.text = "Không kết nối được: ${status.error}"
            }
        }
    }

    private fun saveAndFinish() {
        val ip = binding.edtIpAddress.text?.toString()?.trim().orEmpty()
        if (ip.isBlank()) {
            binding.tilIpAddress.error = "Vui lòng nhập địa chỉ IP"
            binding.edtIpAddress.requestFocus()
            return
        }
        binding.tilIpAddress.error = null

        val mode = modeFor(binding.rgModes.checkedRadioButtonId)
        var interval = 1f
        var unit = ConfigManager.UNIT_HOURS
        when (mode) {
            ConfigManager.MODE_30_SECONDS, ConfigManager.MODE_1_MINUTE -> unit = ConfigManager.UNIT_MINUTES
            ConfigManager.MODE_1_DAY -> unit = ConfigManager.UNIT_DAYS
            ConfigManager.MODE_CUSTOM -> {
                val parsed = binding.edtInterval.text?.toString()?.trim()?.toFloatOrNull()
                if (parsed == null || parsed <= 0f) {
                    binding.tilInterval.error = "Vui lòng nhập số thời gian > 0"
                    binding.edtInterval.requestFocus()
                    return
                }
                binding.tilInterval.error = null
                interval = parsed
                unit = when (binding.toggleUnit.checkedButtonId) {
                    R.id.btnUnitMinutes -> ConfigManager.UNIT_MINUTES
                    R.id.btnUnitDays -> ConfigManager.UNIT_DAYS
                    else -> ConfigManager.UNIT_HOURS
                }
            }
        }

        ConfigManager.saveConfig(
            context = this,
            ip = ip,
            interval = interval,
            timeUnit = unit,
            mode = mode,
            startDelay = 0f
        )
        ConfigManager.setCaptureSoundEnabled(this, binding.switchCaptureSound.isChecked)
        // Dang tu dong chup thi hen lai lich theo cau hinh moi
        if (GardenMonitorService.isServiceRunning) GardenMonitorService.startMonitoring(this)

        Toast.makeText(this, "Đã lưu: ${ConfigManager.getModeDisplayName(this).lowercase()}", Toast.LENGTH_SHORT).show()
        finish()
    }
}
