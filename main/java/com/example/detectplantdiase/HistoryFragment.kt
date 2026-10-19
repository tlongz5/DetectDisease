package com.example.detectplantdiase

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.detectplantdiase.databinding.FragmentHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trang Lich su: moi lan chup (camera vuon, camera dien thoai, thu vien), loc theo tinh trang */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter = HistoryAdapter { record -> DetailActivity.open(requireContext(), record.id) }
    private var all: List<DetectionRecord> = emptyList()
    private var filter: String? = null  // null = tat ca, hoac overallStatus: "DISEASED" / "WARNING" / "HEALTHY"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_binding != null) load()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHistoryBinding.bind(view)
        binding.historyRoot.applySystemBarPadding(top = true)

        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filter = when (checkedIds.firstOrNull()) {
                R.id.chipDiseased -> "DISEASED"
                R.id.chipWarning -> "WARNING"
                R.id.chipHealthy -> "HEALTHY"
                else -> null
            }
            applyFilter()
            binding.recyclerHistory.scrollToPosition(0)
        }
        binding.btnClearHistory.setOnClickListener { confirmClear() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            requireContext(),
            receiver,
            IntentFilter(GardenMonitorService.BROADCAST_DETECTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) load()
    }

    override fun onStop() {
        super.onStop()
        requireContext().unregisterReceiver(receiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerHistory.adapter = null
        _binding = null
    }

    private fun load() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { HistoryManager.getHistory(ctx) }
            if (_binding == null) return@launch
            all = list
            applyFilter()
        }
    }

    private fun applyFilter() {
        val shown = filter?.let { f -> all.filter { it.overallStatus == f } } ?: all
        adapter.submitList(shown)

        binding.tvHistoryCount.text = if (all.isEmpty()) "Chưa có dữ liệu" else "${all.size} lần chụp"
        binding.btnClearHistory.isEnabled = all.isNotEmpty()
        binding.layoutEmpty.isVisible = shown.isEmpty()
        if (all.isEmpty()) {
            binding.tvEmptyTitle.text = "Chưa có lần chụp nào"
            binding.tvEmptyMessage.text = "Ảnh chụp từ camera vườn, camera điện thoại và thư viện sẽ hiện ở đây."
        } else {
            binding.tvEmptyTitle.text = "Không có kết quả phù hợp"
            binding.tvEmptyMessage.text = "Thử chọn bộ lọc khác."
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Xóa toàn bộ lịch sử?")
            .setMessage("Xóa ${all.size} lần chụp cùng ảnh đã lưu trên điện thoại. Không thể hoàn tác.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                val ctx = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { HistoryManager.clearHistory(ctx) }
                    // Trang chu dang giu "ket qua gan nhat" cung cap nhat lai
                    ctx.sendBroadcast(Intent(GardenMonitorService.BROADCAST_DETECTION_UPDATED).setPackage(ctx.packageName))
                }
            }
            .show()
    }
}
