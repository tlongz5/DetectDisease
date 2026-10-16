package com.example.detectplantdiase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.detectplantdiase.databinding.ActivityDailyBinding

class DailyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailyBinding
    private lateinit var historyAdapter: HistoryAdapter
    private var latestRecord: DetectionRecord? = null

    private val monitorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            binding.btnInstantCapture.isEnabled = true
            binding.btnInstantCapture.text = "⚡ Chụp ngay lập tức từ vườn"
            loadHistoryData()
            updateServiceStatusUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        binding = ActivityDailyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()

        // Lắng nghe broadcast từ Foreground Service khi có kết quả mới hoặc đổi trạng thái
        val filter = IntentFilter().apply {
            addAction(GardenMonitorService.BROADCAST_DETECTION_UPDATED)
            addAction(GardenMonitorService.BROADCAST_SERVICE_STATUS)
        }
        ContextCompat.registerReceiver(
            this,
            monitorReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        binding.btnInstantCapture.isEnabled = true
        binding.btnInstantCapture.text = "⚡ Chụp ngay lập tức từ vườn"
        loadHistoryData()
        updateServiceStatusUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(monitorReceiver)
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(emptyList()) { record ->
            openDetailScreen(record.id)
        }
        binding.recyclerHistory.apply {
            layoutManager = LinearLayoutManager(this@DailyActivity)
            adapter = historyAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupListeners() {
        // Nút chuyển sang MainActivity để test kiểm tra model
        binding.btnGoToMain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        // Bấm nút Chụp ngay lập tức từ vườn (không phải chờ chu kỳ)
        binding.btnInstantCapture.setOnClickListener {
            binding.btnInstantCapture.isEnabled = false
            binding.btnInstantCapture.text = "⏳ Đang gọi GET chụp & phân tích..."
            GardenMonitorService.triggerNow(this)
            Toast.makeText(this, "⚡ Đang lấy ảnh trực tiếp từ vườn...", Toast.LENGTH_SHORT).show()
        }

        // Bấm vào item lớn nhất gần nhất -> vào màn chi tiết
        binding.cardLatest.setOnClickListener {
            latestRecord?.let {
                openDetailScreen(it.id)
            }
        }

        // Bấm vào thanh trạng thái chạy ngầm để BẬT / TẮT giám sát
        binding.cardServiceStatus.setOnClickListener {
            if (GardenMonitorService.isServiceRunning) {
                GardenMonitorService.stopMonitoring(this)
                Toast.makeText(this, "Đã tạm dừng tự động giám sát", Toast.LENGTH_SHORT).show()
            } else {
                GardenMonitorService.startMonitoring(this)
                Toast.makeText(this, "Đã bật tự động giám sát chạy ngầm", Toast.LENGTH_SHORT).show()
            }
            updateServiceStatusUi()
        }

        // Nhấn giữ để yêu cầu chụp ngay một ảnh
        binding.cardServiceStatus.setOnLongClickListener {
            GardenMonitorService.triggerNow(this)
            Toast.makeText(this, "Đang chụp ảnh ngay từ vườn...", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun updateServiceStatusUi() {
        val isRunning = GardenMonitorService.isServiceRunning
        val modeDisplay = ConfigManager.getModeDisplayName(this)

        if (isRunning) {
            binding.txtServiceStatus.text = "🟢 Giám sát: $modeDisplay"
            binding.cardServiceStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.light_green))
            binding.txtServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_green_dark))
        } else {
            binding.txtServiceStatus.text = "⚪ Giám sát ngầm: ĐANG TẮT"
            binding.cardServiceStatus.setCardBackgroundColor(Color.parseColor("#EEEEEE"))
            binding.txtServiceStatus.setTextColor(Color.parseColor("#757575"))
        }
    }

    private fun loadHistoryData() {
        val historyList = HistoryManager.getHistory(this)

        if (historyList.isEmpty()) {
            latestRecord = null
            binding.cardLatest.visibility = View.GONE
            binding.cardEmptyLatest.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
            binding.txtEmptyOldHistory.visibility = View.VISIBLE
            binding.txtHistoryCount.text = "0 bản ghi"
            return
        }

        // 1. Hiển thị item đầu tiên lớn nhất (Lần kiểm tra gần nhất)
        val latest = historyList.first()
        latestRecord = latest
        binding.cardEmptyLatest.visibility = View.GONE
        binding.cardLatest.visibility = View.VISIBLE

        val bmp = HistoryManager.loadBitmapFromFile(latest.imagePath)
        if (bmp != null) {
            binding.imgLatest.setImageBitmap(bmp)
        } else {
            binding.imgLatest.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        binding.txtLatestTime.text = "🕒 ${HistoryManager.formatTime(latest.timestamp)}"
        binding.txtLatestSourceBadge.text = latest.source
        binding.txtLatestTitle.text = latest.overallTitle

        // Badge màu sắc cho item lớn nhất
        val dangerColor = ContextCompat.getColor(this, R.color.color_danger)
        val warningColor = ContextCompat.getColor(this, R.color.color_warning)
        val healthyColor = ContextCompat.getColor(this, R.color.color_healthy)
        val infoColor = ContextCompat.getColor(this, R.color.color_info)

        when (latest.overallStatus) {
            "DISEASED" -> {
                binding.txtLatestStatusBadge.text = "🚨 CÓ BỆNH"
                binding.txtLatestStatusBadge.backgroundTintList = ColorStateList.valueOf(dangerColor)
            }
            "WARNING" -> {
                binding.txtLatestStatusBadge.text = "⚠️ CẢNH BÁO"
                binding.txtLatestStatusBadge.backgroundTintList = ColorStateList.valueOf(warningColor)
            }
            "HEALTHY" -> {
                binding.txtLatestStatusBadge.text = "🌿 KHỎE MẠNH"
                binding.txtLatestStatusBadge.backgroundTintList = ColorStateList.valueOf(healthyColor)
            }
            else -> {
                binding.txtLatestStatusBadge.text = "ℹ️ CHƯA RÕ"
                binding.txtLatestStatusBadge.backgroundTintList = ColorStateList.valueOf(infoColor)
            }
        }

        // Dấu hiệu lá tóm tắt
        val leafBrief = when (latest.leafLevel) {
            "diseased" -> "Bệnh ${(latest.leafDiseaseProb * 100).toInt()}%: ${latest.leafDiseaseNameVi ?: ""}"
            "suspect" -> "Nghi ngờ ${(latest.leafDiseaseProb * 100).toInt()}%: ${latest.leafDiseaseNameVi ?: ""}"
            "healthy" -> "Lá khỏe mạnh"
            else -> latest.leafStatus
        }
        binding.txtLatestLeafInfo.text = "🍃 Lá: $leafBrief"

        // Dấu hiệu quả tóm tắt
        val confPct = (latest.fruitConfidence * 100).toInt()
        val fruitBrief = if (latest.fruitConfidence < 0.5f || latest.fruitNameVi.contains("không rõ", ignoreCase = true) || latest.fruitLabel == "unknown") {
            if (confPct > 0) "không rõ ($confPct%)" else "không rõ"
        } else if (confPct > 0) {
            "${latest.fruitNameVi} ($confPct%)"
        } else {
            latest.fruitNameVi
        }
        binding.txtLatestFruitInfo.text = "🍅 Quả: $fruitBrief"

        // 2. Hiển thị danh sách lịch sử cũ (các item từ thứ 2 trở đi)
        val olderRecords = historyList.drop(1)
        if (olderRecords.isNotEmpty()) {
            binding.recyclerHistory.visibility = View.VISIBLE
            binding.txtEmptyOldHistory.visibility = View.GONE
            binding.txtHistoryCount.text = "${olderRecords.size} bản ghi cũ"
            historyAdapter.updateData(olderRecords)
        } else {
            binding.recyclerHistory.visibility = View.GONE
            binding.txtEmptyOldHistory.visibility = View.VISIBLE
            binding.txtHistoryCount.text = "0 bản ghi cũ"
            historyAdapter.updateData(emptyList())
        }
    }

    private fun openDetailScreen(recordId: String) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_RECORD_ID, recordId)
        }
        startActivity(intent)
    }
}