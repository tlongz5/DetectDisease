package com.example.detectplantdiase

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.detectplantdiase.databinding.ActivityDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Man Ket qua phan tich cua 1 lan chup. Bam quay lai ve man truoc (Trang chu / Lich su) */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"

        fun open(context: Context, recordId: String) {
            context.startActivity(Intent(context, DetailActivity::class.java).putExtra(EXTRA_RECORD_ID, recordId))
        }
    }

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.applySystemBarPadding(top = true)
        binding.scrollDetail.applySystemBarPadding(bottom = true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val record = intent.getStringExtra(EXTRA_RECORD_ID)?.let { HistoryManager.getRecordById(this, it) }
        if (record == null) {
            finish()
            return
        }
        bindData(record)
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun bindData(r: DetectionRecord) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { HistoryManager.loadBitmapFromFile(r.imagePath) }
            if (bitmap != null) binding.imgDetail.setImageBitmap(bitmap) else binding.imgDetail.setImageResource(R.drawable.ic_image)
        }

        // 1. Tong ket
        val overall = overallStyle(r.overallStatus)
        binding.cardOverall.setCardBackgroundColor(color(overall.container))
        binding.imgOverallIcon.setImageResource(overall.icon)
        binding.imgOverallIcon.imageTintList = ColorStateList.valueOf(color(overall.color))
        binding.tvOverallLabel.text = overall.label
        binding.tvOverallLabel.setTextColor(color(overall.color))
        binding.tvOverallTitle.text = r.displayTitle
        binding.tvOverallSummary.text = r.overallSummary

        binding.chipTime.text = HistoryManager.formatTime(r.timestamp)
        binding.chipSource.text = r.source
        binding.chipInference.text = "AI ${String.format(Locale.forLanguageTag("vi"), "%.1f", r.inferenceTimeMs / 1000f)} giây"

        // 2. La
        val leaf = leafStyle(r.leafLevel)
        binding.tvLeafBadge.applyBadge(leaf)
        val code = if (!r.leafDiseaseCode.isNullOrBlank()) " (${r.leafDiseaseCode})" else ""
        // Model luon tra ve "benh kha nang cao nhat" ke ca khi la khoe: chi hien ten benh khi bi benh / nghi benh
        binding.tvLeafDiagnosis.text = when (r.leafLevel) {
            "diseased", "suspect" -> (r.leafDiseaseNameVi ?: "Bệnh lá") + code
            "healthy" -> "Lá cà chua khỏe mạnh"
            else -> when (r.leafStatus) {
                "too_dark" -> "Ảnh quá tối"
                "no_leaf" -> "Không nhận diện được lá cà chua"
                else -> "Chưa phân tích được lá"
            }
        }
        val leafPct = when (r.leafLevel) {
            "diseased", "suspect" -> (r.leafDiseaseProb * 100).toInt()
            "healthy" -> ((1 - r.leafDiseaseProb) * 100).toInt()
            else -> null
        }
        binding.layoutLeafProb.isVisible = leafPct != null
        if (leafPct != null) {
            binding.tvLeafProb.text =
                if (r.leafLevel == "healthy") "Mức độ khỏe mạnh $leafPct%" else "Xác suất nhiễm bệnh $leafPct%"
            binding.progressLeaf.setIndicatorColor(color(leaf.color))
            binding.progressLeaf.setProgressCompat(leafPct, false)
        }
        binding.tvLeafSymptoms.text = r.leafSymptoms

        // 3. Qua
        binding.tvFruitBadge.applyBadge(r.fruitStyle())
        binding.tvFruitName.text = if (r.isFruitKnown) r.fruitNameVi else "Không rõ"
        val fruitPct = (r.fruitConfidence * 100).toInt()
        binding.layoutFruitConf.isVisible = fruitPct > 0
        binding.tvFruitConf.text =
            if (r.isFruitKnown) "Độ tin cậy $fruitPct%" else "Độ tin cậy $fruitPct% (dưới 50% nên chưa kết luận)"
        binding.progressFruit.setIndicatorColor(color(r.fruitStyle().color))
        binding.progressFruit.setProgressCompat(fruitPct, false)
        binding.tvFruitSymptoms.text = r.fruitSymptoms

        // 4. Loi khuyen + noi dung LCD
        binding.tvAdvice.text = r.advice
        binding.cardLcd.isVisible = !r.lcdText.isNullOrBlank()
        binding.tvLcd.text = r.lcdText
    }
}
