package com.example.detectplantdiase

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.detectplantdiase.databinding.ItemDetectionBinding

class HistoryAdapter(
    private val onItemClick: (DetectionRecord) -> Unit
) : ListAdapter<DetectionRecord, HistoryAdapter.ViewHolder>(DIFF) {

    // Anh thu nho da doc: cuon len xuong hoac co ban ghi moi (30 giay/lan) khong phai doc lai file
    private val thumbs = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDetectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemDetectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DetectionRecord) {
            val thumb = thumbs.get(item.imagePath)
                ?: HistoryManager.loadThumbnail(item.imagePath, 160)?.also { thumbs.put(item.imagePath, it) }
            if (thumb != null) {
                binding.imgThumb.setImageBitmap(thumb)
            } else {
                binding.imgThumb.setImageResource(R.drawable.ic_image)
            }

            binding.txtTitle.text = item.displayTitle
            binding.txtStatus.applyBadge(overallStyle(item.overallStatus))
            binding.txtLeaf.text = item.leafBrief
            binding.txtFruit.text = item.fruitBrief
            binding.txtSubtitle.text = "${HistoryManager.formatTimeShort(item.timestamp)} • ${item.source}"

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DetectionRecord>() {
            override fun areItemsTheSame(oldItem: DetectionRecord, newItem: DetectionRecord) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DetectionRecord, newItem: DetectionRecord) = oldItem == newItem
        }
    }
}
