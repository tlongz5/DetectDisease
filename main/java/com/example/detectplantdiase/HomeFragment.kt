package com.example.detectplantdiase

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.detectplantdiase.databinding.FragmentHomeBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Trang chu: ket noi ESP32-CAM, 3 cach chup anh, tu dong chup, ket qua gan nhat */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var latest: DetectionRecord? = null
    private var statusJob: Job? = null
    private var syncingControls = false   // Dang dat switch / chip bang code: bo qua listener

    // Vua bam "Chup ngay": co ket qua thi mo luon man Ket qua
    private var waitingEspResult = false
    private var espRequestedAt = 0L

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analyzeGalleryPhoto(uri)
    }

    // Android 13+: xin quyen thong bao de hien thong bao "dang giam sat" va canh bao benh. Tu choi van chay duoc
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startMonitoring()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (_binding == null) return
            when (intent.action) {
                GardenMonitorService.BROADCAST_DETECTION_UPDATED -> {
                    loadLatest()
                    val source = intent.getStringExtra(AiEngine.EXTRA_SOURCE)
                    val isEsp = source == GardenMonitorService.SOURCE_ESP_MANUAL || source == GardenMonitorService.SOURCE_ESP_AUTO
                    if (waitingEspResult && isEsp) {
                        waitingEspResult = false
                        intent.getStringExtra(AiEngine.EXTRA_RECORD_ID)?.let { DetailActivity.open(requireContext(), it) }
                    }
                    refreshEspStatus()
                }

                GardenMonitorService.BROADCAST_CAPTURE_FAILED -> {
                    if (waitingEspResult) {
                        waitingEspResult = false
                        val error = intent.getStringExtra(GardenMonitorService.EXTRA_ERROR)
                        snackbar("Không chụp được ảnh từ vườn: $error")
                            ?.setAction("Cài đặt") { openSettings() }
                            ?.show()
                    }
                    refreshEspStatus()
                }
            }
            updateAutoCaptureUi()
            updateEspActionUi()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)
        binding.scrollHome.applySystemBarPadding(top = true)  // Cuon len khong bi lot sau thanh trang thai

        val vi = Locale.forLanguageTag("vi")
        binding.tvDate.text = SimpleDateFormat("EEEE, dd/MM/yyyy", vi).format(Date())
            .replaceFirstChar { it.titlecase(vi) }

        binding.btnSettings.setOnClickListener { openSettings() }
        binding.cardEsp.setOnClickListener { openSettings() }
        binding.actionEsp.setOnClickListener { captureFromEsp() }
        binding.actionCamera.setOnClickListener { startActivity(Intent(requireContext(), CameraActivity::class.java)) }
        binding.actionGallery.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnSeeAll.setOnClickListener { (activity as? MainActivity)?.openHistoryTab() }
        binding.cardLatest.setOnClickListener { latest?.let { DetailActivity.open(requireContext(), it.id) } }

        binding.switchAuto.setOnCheckedChangeListener { _, isChecked ->
            if (!syncingControls) onAutoCaptureToggled(isChecked)
        }
        binding.chipGroupInterval.setOnCheckedStateChangeListener { _, checkedIds ->
            if (!syncingControls) onIntervalSelected(checkedIds.firstOrNull())
        }
        // Nhap so phut / gio o man Cai dat; quay ve thi chip tu khop lai voi che do da luu
        binding.chipCustom.setOnClickListener { openSettings() }

        // Dem nguoc toi lan chup ke tiep (30 giay/lan thi nhin thay ngay), moi giay 1 lan khi man dang hien
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (waitingEspResult && System.currentTimeMillis() - espRequestedAt > 90_000L) {
                        waitingEspResult = false  // Service khong bao lai (vd bi Android dung): thoi cho
                    }
                    updateAutoStatusText()
                    updateEspActionUi()
                    delay(1000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(GardenMonitorService.BROADCAST_DETECTION_UPDATED)
            addAction(GardenMonitorService.BROADCAST_SERVICE_STATUS)
            addAction(GardenMonitorService.BROADCAST_CAPTURE_FAILED)
        }
        ContextCompat.registerReceiver(requireContext(), receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()  // Vua quay ve tu Cai dat / Camera / Ket qua
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshAll()
    }

    override fun onStop() {
        super.onStop()
        requireContext().unregisterReceiver(receiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshAll() {
        updateAutoCaptureUi()
        updateEspActionUi()
        loadLatest()
        refreshEspStatus()
    }

    /** Snackbar noi phia tren nut Camera, khong de len thanh dieu huong. null khi man da dong */
    private fun snackbar(text: String, duration: Int = Snackbar.LENGTH_LONG): Snackbar? {
        val b = _binding ?: return null
        return Snackbar.make(b.root, text, duration).setAnchorView(activity?.findViewById(R.id.fabCamera))
    }

    private fun openSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java))
    }

    // ---------- Ket noi ESP32-CAM ----------

    private fun refreshEspStatus() {
        val ctx = requireContext().applicationContext
        binding.tvEspAddress.text = if (ConfigManager.isOnEspAp(ctx)) {
            "${ConfigManager.ESP_AP_IP} • WiFi TuoiCay-CAM"
        } else {
            ConfigManager.getIp(ctx)
        }
        if (statusJob?.isActive != true) {
            binding.tvEspStatus.applyBadge(StatusStyle("Đang kiểm tra…", R.color.text_secondary, R.color.white, 0))
        }

        statusJob?.cancel()
        statusJob = viewLifecycleOwner.lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { EspClient.fetchStatus(ctx) }
            val b = _binding ?: return@launch
            b.tvEspStatus.applyBadge(
                if (status.reachable) StatusStyle("Đã kết nối", R.color.primary_green, R.color.white, 0)
                else StatusStyle("Mất kết nối", R.color.color_danger, R.color.white, 0)
            )
            val soil = status.soil
            b.tvMoisture.text = when {
                soil == null -> "--"
                soil.fault -> "Lỗi"
                else -> "${soil.moisture}%"
            }
            b.tvPump.text = soil?.let { if (it.pump) "Đang bơm" else "Tắt" } ?: "--"
            b.tvMist.text = soil?.let { if (it.mist) "Đang phun" else "Tắt" } ?: "--"
        }
    }

    private fun captureFromEsp() {
        if (GardenMonitorService.isCapturing || waitingEspResult) return
        waitingEspResult = true
        espRequestedAt = System.currentTimeMillis()
        GardenMonitorService.triggerNow(requireContext())
        updateEspActionUi()
    }

    private fun updateEspActionUi() {
        val b = _binding ?: return
        val busy = GardenMonitorService.isCapturing || waitingEspResult
        b.progressEsp.isVisible = busy
        b.imgEspAction.alpha = if (busy) 0.4f else 1f
        b.tvEspAction.text = if (busy) "Đang chụp…" else "Chụp ngay"
        b.actionEsp.isEnabled = !busy
    }

    // ---------- Tu dong chup ----------

    private fun onAutoCaptureToggled(on: Boolean) {
        val ctx = requireContext()
        if (!on) {
            GardenMonitorService.stopMonitoring(ctx)
            snackbar("Đã tắt tự động chụp", Snackbar.LENGTH_SHORT)?.show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val ctx = context ?: return
        GardenMonitorService.startMonitoring(ctx)
        val mode = ConfigManager.getModeDisplayName(ctx).lowercase(Locale.forLanguageTag("vi"))
        snackbar("Đã bật tự động chụp $mode", Snackbar.LENGTH_SHORT)?.show()
    }

    private fun onIntervalSelected(chipId: Int?) {
        val ctx = requireContext()
        val mode = when (chipId) {
            R.id.chip30s -> ConfigManager.MODE_30_SECONDS
            R.id.chip1m -> ConfigManager.MODE_1_MINUTE
            R.id.chip1h -> ConfigManager.MODE_1_HOUR
            R.id.chip1d -> ConfigManager.MODE_1_DAY
            else -> return  // chipCustom: mo Cai dat trong setOnClickListener
        }
        ConfigManager.setMode(ctx, mode)
        // Dang tu dong chup thi hen lai lich theo chu ky moi
        if (GardenMonitorService.isServiceRunning) GardenMonitorService.startMonitoring(ctx)
        updateAutoCaptureUi()
    }

    private fun updateAutoCaptureUi() {
        val b = _binding ?: return
        val ctx = requireContext()
        val mode = ConfigManager.getMode(ctx)

        syncingControls = true
        b.switchAuto.isChecked = GardenMonitorService.isServiceRunning
        b.chipGroupInterval.check(
            when (mode) {
                ConfigManager.MODE_30_SECONDS -> R.id.chip30s
                ConfigManager.MODE_1_MINUTE -> R.id.chip1m
                ConfigManager.MODE_1_HOUR -> R.id.chip1h
                ConfigManager.MODE_1_DAY -> R.id.chip1d
                else -> R.id.chipCustom
            }
        )
        b.chipCustom.text = if (mode == ConfigManager.MODE_CUSTOM) ConfigManager.getModeDisplayName(ctx) else "Tùy chỉnh…"
        syncingControls = false

        b.tvAutoHint.isVisible = mode == ConfigManager.MODE_30_SECONDS
        updateAutoStatusText()
    }

    private fun updateAutoStatusText() {
        val b = _binding ?: return
        val ctx = context ?: return
        val modeName = ConfigManager.getModeDisplayName(ctx).lowercase(Locale.forLanguageTag("vi"))
        val next = GardenMonitorService.nextRunTimeMillis
        val left = next - System.currentTimeMillis()
        b.tvAutoStatus.text = when {
            !GardenMonitorService.isServiceRunning -> "Đang tắt • $modeName"
            GardenMonitorService.isCapturing -> "Đang chụp ảnh…"
            next > 0 && left in 0..119_999 -> "Đang bật • lần tới sau ${(left + 999) / 1000} giây"
            next > 0 && left > 0 -> "Đang bật • lần tới lúc ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(next))}"
            else -> "Đang bật • $modeName"
        }
    }

    // ---------- Ket qua gan nhat ----------

    private fun loadLatest() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val (record, thumb) = withContext(Dispatchers.IO) {
                val r = HistoryManager.getLatest(ctx)
                r to r?.let { HistoryManager.loadThumbnail(it.imagePath, 540) }
            }
            val b = _binding ?: return@launch
            latest = record
            b.cardLatest.isVisible = record != null
            b.cardEmpty.isVisible = record == null
            if (record == null) return@launch

            if (thumb != null) b.imgLatest.setImageBitmap(thumb) else b.imgLatest.setImageResource(R.drawable.ic_image)
            b.tvLatestBadge.applyBadge(overallStyle(record.overallStatus))
            b.tvLatestTitle.text = record.displayTitle
            b.tvLatestLeaf.text = "Lá: ${record.leafBrief}"
            b.tvLatestFruit.text = "Quả: ${record.fruitBrief}"
            b.tvLatestMeta.text = "${HistoryManager.formatTimeShort(record.timestamp)} • ${record.source}"
        }
    }

    // ---------- Anh tu thu vien ----------

    private fun analyzeGalleryPhoto(uri: Uri) {
        val ctx = requireContext()
        val dialog = AnalyzingDialog(ctx).also { it.show() }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { PhotoInput.decodeUri(ctx, uri) }
                if (!AiEngine.isLoaded) dialog.setMessage("Đang nạp model AI (lần đầu mất vài giây)…")
                AiEngine.load(ctx)
                dialog.setMessage("Đang phân tích ảnh…")
                val outcome = AiEngine.analyzeAndSave(ctx, bitmap, PhotoInput.SOURCE_GALLERY, sendToEsp = false)
                DetailActivity.open(ctx, outcome.record.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar("Không phân tích được ảnh: ${e.message}")?.show()
            } finally {
                dialog.dismiss()
            }
        }
    }
}
