package com.example.detectplantdiase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.ImageProxy
import com.example.detectplantdiase.databinding.DialogProgressBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/** Doc anh tu camera dien thoai / thu vien thanh Bitmap cho model AI */
object PhotoInput {
    const val SOURCE_CAMERA = "Camera điện thoại"
    const val SOURCE_GALLERY = "Thư viện ảnh"

    // Anh ESP32-CAM la 1600x1200: dua anh dien thoai (12-50 MP) ve co tuong tu, model chay nhanh, lich su nhe
    private const val MAX_SIDE = 1600

    fun decodeUri(context: Context, uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_SIDE.toFloat() / maxOf(w, h)
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // Model doc pixel bang getPixels: can bitmap phan mem
        }
    }

    /** Anh CameraX: xoay dung chieu (cam doc may nhung cam bien nam ngang) va thu nho */
    fun fromCamera(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
        if (rotation == 0 && scale == 1f) return raw
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }
}

/** Hop thoai "Dang phan tich..." (khong bam ra ngoai de tat duoc) */
class AnalyzingDialog(context: Context) {
    private val binding = DialogProgressBinding.inflate(LayoutInflater.from(context))
    private val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
        .setView(binding.root)
        .setCancelable(false)
        .create()

    fun setMessage(text: String) {
        binding.tvProgress.text = text
    }

    fun show() = dialog.show()

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}
