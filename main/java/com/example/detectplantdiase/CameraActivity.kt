package com.example.detectplantdiase

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.detectplantdiase.databinding.ActivityCameraBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Chup anh cay bang camera dien thoai ngay trong app (hoac chon anh tu thu vien), phan tich xong mo man Ket qua.
 * Man nay tu dong lai khi mo Ket qua, nen bam quay lai tu Ket qua se ve Trang chu.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var controller: LifecycleCameraController? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var flashOn = false
    private var busy = false
    private var permissionDeniedForever = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            // Tu choi 2 lan (hoac "Khong hoi lai") thi Android khong hien hop thoai nua: phai vao Cai dat cua may
            permissionDeniedForever = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showPermissionNeeded()
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analyze(PhotoInput.SOURCE_GALLERY) { PhotoInput.decodeUri(this, uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Nen den: icon thanh trang thai mau trang
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topBar.applySystemBarPadding(top = true)
        binding.bottomBar.applySystemBarPadding(bottom = true)

        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnFlip.setOnClickListener { flipCamera() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.btnGrant.setOnClickListener {
            if (permissionDeniedForever) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } else {
                requestCamera.launch(Manifest.permission.CAMERA)
            }
        }

        // Nap model trong luc nguoi dung canh khung hinh
        lifecycleScope.launch { AiEngine.load(applicationContext) }

        if (hasCameraPermission()) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        // Vua cap quyen trong Cai dat cua may roi quay lai
        if (controller == null && hasCameraPermission()) startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionNeeded() {
        binding.layoutPermission.isVisible = true
        binding.btnShutter.isEnabled = false
        binding.btnFlip.isVisible = false
        binding.btnFlash.isVisible = false
        binding.btnGrant.text = if (permissionDeniedForever) "Mở cài đặt quyền" else "Cấp quyền camera"
    }

    private fun startCamera() {
        if (controller != null) return
        binding.layoutPermission.isVisible = false
        binding.btnShutter.isEnabled = true
        binding.btnFlash.isVisible = true

        val c = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            // Gan bang anh ESP32-CAM (1600x1200) la du cho model, chup nhanh va nhe hon anh 50 MP
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            bindToLifecycle(this@CameraActivity)
        }
        binding.previewView.controller = c
        controller = c

        c.initializationFuture.addListener({
            val hasBoth = runCatching {
                c.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && c.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            }.getOrDefault(false)
            binding.btnFlip.isVisible = hasBoth
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        val c = controller ?: return
        val toFront = c.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
        c.cameraSelector = if (toFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        binding.btnFlash.isVisible = !toFront  // Camera truoc khong co den flash
    }

    private fun toggleFlash() {
        val c = controller ?: return
        flashOn = !flashOn
        c.imageCaptureFlashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        binding.btnFlash.setImageResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun takePhoto() {
        val c = controller ?: return
        if (busy) return
        setBusy(true, "Đang chụp…")
        c.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    PhotoInput.fromCamera(image)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                runOnUiThread {
                    if (bitmap == null) fail("Không đọc được ảnh vừa chụp") else analyze(PhotoInput.SOURCE_CAMERA) { bitmap }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { fail("Không chụp được ảnh: ${exception.message}") }
            }
        })
    }

    /** Phan tich anh (doc anh o luong nen), luu vao lich su roi mo man Ket qua */
    private fun analyze(source: String, loadBitmap: () -> Bitmap) {
        setBusy(true, "Đang phân tích ảnh…")
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadBitmap() }
                binding.imgCaptured.setImageBitmap(bitmap)
                if (!AiEngine.isLoaded) binding.tvProcessing.text = "Đang nạp model AI…\n(lần đầu mất vài giây)"
                AiEngine.load(applicationContext)
                binding.tvProcessing.text = "Đang phân tích ảnh…"
                val outcome = AiEngine.analyzeAndSave(this@CameraActivity, bitmap, source, sendToEsp = false)
                DetailActivity.open(this@CameraActivity, outcome.record.id)
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Không phân tích được ảnh: ${e.message}")
            }
        }
    }

    private fun setBusy(value: Boolean, message: String = "") {
        busy = value
        binding.layoutProcessing.isVisible = value
        binding.tvProcessing.text = message
        if (!value) binding.imgCaptured.setImageDrawable(null)
        binding.btnShutter.isEnabled = !value && controller != null
        binding.btnGallery.isEnabled = !value
    }

    private fun fail(message: String) {
        setBusy(false)
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).setAnchorView(binding.bottomBar).show()
    }
}
