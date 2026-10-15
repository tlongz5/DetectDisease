package com.example.detectplantdiase

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.detectplantdiase.databinding.ActivityDetailBinding

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
        val record = if (recordId != null) {
            HistoryManager.getRecordById(this, recordId)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_RECORD) as? DetectionRecord
        }

        if (record == null) {
            finish()
            return
        }

        bindData(record)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun bindData(r: DetectionRecord) {
        // 1. Ảnh và thông tin cơ bản
        val bitmap = HistoryManager.loadBitmapFromFile(r.imagePath)
        if (bitmap != null) {
            binding.imgDetail.setImageBitmap(bitmap)
        } else {
            binding.imgDetail.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        binding.tvDetailTime.text = "🕒 ${HistoryManager.formatTime(r.timestamp)}"
        binding.tvDetailSource.text = r.source

        val dangerColor = ContextCompat.getColor(this, R.color.color_danger)
        val warningColor = ContextCompat.getColor(this, R.color.color_warning)
        val healthyColor = ContextCompat.getColor(this, R.color.color_healthy)

        // 1. Dấu hiệu lá
        when (r.leafLevel) {
            "diseased" -> {
                binding.tvLeafLevelBadge.text = "NHIỄM BỆNH"
                binding.tvLeafLevelBadge.backgroundTintList = ColorStateList.valueOf(dangerColor)
            }
            "suspect" -> {
                binding.tvLeafLevelBadge.text = "NGHI NGỜ"
                binding.tvLeafLevelBadge.backgroundTintList = ColorStateList.valueOf(warningColor)
            }
            "healthy" -> {
                binding.tvLeafLevelBadge.text = "KHỎE MẠNH"
                binding.tvLeafLevelBadge.backgroundTintList = ColorStateList.valueOf(healthyColor)
            }
            else -> {
                binding.tvLeafLevelBadge.text = "KHÔNG RÕ"
                binding.tvLeafLevelBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#757575"))
            }
        }

        val leafTitle = r.leafDiseaseNameVi ?: when (r.leafStatus) {
            "too_dark" -> "Ảnh quá tối"
            "no_leaf" -> "Không nhận diện được lá cây"
            else -> "Lá cây phát triển bình thường"
        }
        val codeSuffix = if (!r.leafDiseaseCode.isNullOrBlank()) " (${r.leafDiseaseCode})" else ""
        binding.tvLeafDiagnosis.text = leafTitle + codeSuffix

        val leafProbText = if (r.leafLevel == "diseased" || r.leafLevel == "suspect") {
            "Xác suất nhiễm bệnh: ${(r.leafDiseaseProb * 100).toInt()}%"
        } else if (r.leafLevel == "healthy") {
            "Độ an toàn khỏe mạnh: ${((1 - r.leafDiseaseProb) * 100).toInt()}%"
        } else {
            "Trạng thái phân tích: ${r.leafStatus}"
        }
        binding.tvLeafProb.text = leafProbText
        binding.tvLeafSymptoms.text = r.leafSymptoms

        // 2. Dấu hiệu quả
        val isFruitKnown = r.fruitConfidence >= 0.5f && r.fruitLabel != null && r.fruitLabel != "unknown" && !r.fruitNameVi.contains("không rõ", ignoreCase = true)
        val isFruitRotten = isFruitKnown && r.fruitLabel.contains("rotten", ignoreCase = true)
        val isFruitFresh = isFruitKnown && (r.fruitLabel.contains("fresh", ignoreCase = true) || r.fruitLabel.startsWith("ripe_", ignoreCase = true))
        val isFruitInter = isFruitKnown && r.fruitLabel.contains("intermediate", ignoreCase = true)

        when {
            isFruitRotten -> {
                binding.tvFruitBadge.text = "BỊ THỐI HỎNG"
                binding.tvFruitBadge.backgroundTintList = ColorStateList.valueOf(dangerColor)
            }
            isFruitInter -> {
                binding.tvFruitBadge.text = "SẮP HỎNG / HÉO"
                binding.tvFruitBadge.backgroundTintList = ColorStateList.valueOf(warningColor)
            }
            isFruitFresh -> {
                binding.tvFruitBadge.text = "TƯƠI TỐT"
                binding.tvFruitBadge.backgroundTintList = ColorStateList.valueOf(healthyColor)
            }
            else -> {
                binding.tvFruitBadge.text = "KHÔNG RÕ"
                binding.tvFruitBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#757575"))
            }
        }

        binding.tvFruitName.text = if (!isFruitKnown) "Không rõ" else r.fruitNameVi
        val confPct = (r.fruitConfidence * 100).toInt()
        binding.tvFruitConf.text = when {
            !isFruitKnown && confPct > 0 -> "Độ tin cậy: $confPct% (< 50% - Không rõ)"
            confPct > 0 -> "Độ tin cậy nhận diện: $confPct%"
            else -> "Không phát hiện quả rõ nét"
        }
        binding.tvFruitSymptoms.text = r.fruitSymptoms
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_RECORD = "extra_record"
    }
}
