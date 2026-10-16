package com.example.detectplantdiase

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.detectplantdiase.databinding.ItemDetectionBinding

class HistoryAdapter(
    private var items: List<DetectionRecord>,
    private val onItemClick: (DetectionRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    fun updateData(newItems: List<DetectionRecord>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDetectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDetectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DetectionRecord) {
            val context = binding.root.context

            // Ảnh thu nhỏ
            val bitmap = HistoryManager.loadBitmapFromFile(item.imagePath)
            if (bitmap != null) {
                binding.imgThumb.setImageBitmap(bitmap)
            } else {
                binding.imgThumb.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // Tiêu đề
            val title = if (!item.leafDiseaseNameVi.isNullOrBlank()) {
                "${item.leafDiseaseNameVi} ${if (!item.leafDiseaseCode.isNullOrBlank()) "(${item.leafDiseaseCode})" else ""}"
            } else {
                item.overallTitle
            }
            binding.txtTitle.text = title

            // Tóm tắt quả & lá
            val fruitPart = "🍅 " + when {
                item.fruitConfidence < 0.5f || item.fruitLabel == "unknown" || item.fruitNameVi.contains("không rõ", ignoreCase = true) -> "Không rõ"
                item.fruitLabel != null -> item.fruitNameVi
                else -> "Không có quả"
            }
            val leafPart = "🍃 " + when (item.leafLevel) {
                "diseased" -> "Bệnh ${(item.leafDiseaseProb * 100).toInt()}%"
                "suspect" -> "Nghi ngờ ${(item.leafDiseaseProb * 100).toInt()}%"
                "healthy" -> "Lá khỏe"
                else -> item.leafStatus
            }
            binding.txtSymptomsBrief.text = "$fruitPart • $leafPart"

            // Phụ đề thời gian & nguồn
            binding.txtSubtitle.text = "🕒 ${HistoryManager.formatTimeShort(item.timestamp)} • ${item.source}"

            // Trạng thái badge
            val dangerColor = ContextCompat.getColor(context, R.color.color_danger)
            val warningColor = ContextCompat.getColor(context, R.color.color_warning)
            val healthyColor = ContextCompat.getColor(context, R.color.color_healthy)

            when (item.overallStatus) {
                "DISEASED" -> {
                    binding.txtStatus.text = "CÓ BỆNH"
                    binding.txtStatus.backgroundTintList = ColorStateList.valueOf(dangerColor)
                }
                "WARNING" -> {
                    binding.txtStatus.text = "CẢNH BÁO"
                    binding.txtStatus.backgroundTintList = ColorStateList.valueOf(warningColor)
                }
                "HEALTHY" -> {
                    binding.txtStatus.text = "KHỎE MẠNH"
                    binding.txtStatus.backgroundTintList = ColorStateList.valueOf(healthyColor)
                }
                else -> {
                    binding.txtStatus.text = "CHƯA RÕ"
                    binding.txtStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#757575"))
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
