package com.example.detectplantdiase

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** 1 chiec la tim duoc: khung tren anh goc (pixel) + diem tin cay 0..1 */
data class LeafBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float = 1f) {
    val width get() = x1 - x0
    val height get() = y1 - y0
}

/**
 * Model YOLO26n tim tung chiec la trong anh (ai/train_detector.py -> leaf_detector.onnx), giong ai/detector.py.
 * Vao [1,3,640,640] RGB 0..1, anh thu nho giu ti le roi dat giua o vuong to xam (114).
 * Ra [1,300,6] = x0,y0,x1,y1 (tren o 640x640), diem, lop. YOLO26 tu loc khung trung nen khong can NMS.
 */
class LeafDetector(context: Context, modelAsset: String = "leaf_detector.onnx") {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val size: Int

    init {
        val modelBytes = context.assets.open(modelAsset).use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
        inputName = session.inputNames.first()  // "images"
        size = 640
    }

    /** Tra ve cac la co diem >= conf, diem cao truoc. */
    fun detect(image: Bitmap, conf: Float): List<LeafBox> {
        val w = image.width
        val h = image.height
        val scale = min(size / w.toFloat(), size / h.toFloat())
        val nw = (w * scale).roundToInt()
        val nh = (h * scale).roundToInt()
        val px = (size - nw) / 2
        val py = (size - nh) / 2

        val canvasBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(smoothScale(image, nw, nh), px.toFloat(), py.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val plane = size * size
        val pixels = IntArray(plane)
        canvasBmp.getPixels(pixels, 0, size, 0, 0, size, size)
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 0xFF) / 255f
            data[plane + i] = ((p shr 8) and 0xFF) / 255f
            data[2 * plane + i] = (p and 0xFF) / 255f
        }

        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        val rows = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<FloatArray>>)[0]
            }
        }
        val leaves = mutableListOf<LeafBox>()
        for (r in rows) {
            if (r[4] < conf) continue
            // Doi ve toa do anh goc
            val box = LeafBox(
                ((r[0] - px) / scale).roundToInt().coerceAtLeast(0),
                ((r[1] - py) / scale).roundToInt().coerceAtLeast(0),
                ((r[2] - px) / scale).roundToInt().coerceAtMost(w),
                ((r[3] - py) / scale).roundToInt().coerceAtMost(h),
                r[4]
            )
            if (box.width > 0 && box.height > 0) leaves.add(box)
        }
        return leaves.sortedByDescending { it.score }
    }

    fun close() {
        session.close()
    }
}

/**
 * Thu nho anh cho muot giong PIL: anh ESP32-CAM 1600x1200 phai thu nho rat manh,
 * thu 1 phat se bi rang cua lam model doan sai, nen thu tung buoc 1/2 roi moi ve dung kich thuoc.
 */
fun smoothScale(src: Bitmap, w: Int, h: Int): Bitmap {
    var bmp = src
    while (bmp.width / 2 >= w && bmp.height / 2 >= h) {
        bmp = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
    }
    return Bitmap.createScaledBitmap(bmp, w, h, true)
}
