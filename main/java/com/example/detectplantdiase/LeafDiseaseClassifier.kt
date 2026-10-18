package com.example.detectplantdiase

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** 1 lop trong leaf_labels.json (file labels.json do ai/export_onnx.py tao ra) */
data class LeafClass(
    val id: String,
    val code: String,
    val nameVi: String,
    val healthy: Boolean,
    val lcdVi: String = code,
)

/** Ket qua phan tich 1 anh, giong Predictor.analyze() trong ai/predict.py */
data class LeafResult(
    val status: String,            // "ok" / "too_dark" / "no_leaf"
    val level: String,             // "healthy" / "suspect" / "diseased" ("none" khi status khac "ok")
    val diseaseProb: Float,        // P(benh) cua ca anh sau khi bo phieu (xem LeafVote.vote)
    val diseaseClass: LeafClass?,  // Neu la benh thi kha nang cao nhat la benh nay
    val regions: Int,              // So la da cham diem
    val healthyClass: LeafClass? = null,  // Lop "khoe" kha nang cao nhat, de biet la cay gi khi la khoe
)
// Chu hien len LCD (2 dong la + qua) nam o LcdMessage.kt

/**
 * Phan tich benh la bang 2 model ONNX, giong ai/predict.py:
 *  1. LeafDetector (YOLO26n) khoanh tung chiec la. Qua, dat, troi... khong phai la nen bi bo,
 *     khong con bi model benh doan thanh 1 benh nao do.
 *  2. Cat tung la dua vao model benh (EfficientNet-B0, 15 lop), roi gop bang bo phieu (LeafVote).
 */
class LeafDiseaseClassifier(
    context: Context,
    modelAsset: String = "leaf_disease_model.onnx",
    labelsAsset: String = "leaf_labels.json",
    detectorAsset: String = "leaf_detector.onnx",
) {
    companion object {
        // Giong ai/config.py: doi o ben do thi doi ca o day
        const val DET_CONF = 0.25f            // Khung la ca chua co diem tin cay duoi muc nay thi bo
        const val LEAF_MIN_SIDE = 32          // La co canh ngan < 32 px thi bo (phong to len 224 chi con mo nhoe)
        const val MAX_LEAVES = 12             // Chi cham diem toi da 12 la chac chan nhat
        const val DARK_MAX_BRIGHTNESS = 35f   // Do sang trung binh (0-255) duoi muc nay -> anh qua toi
        const val RESIZE_SHORT = 256          // get_transforms(train=False): Resize(256) roi CenterCrop(224)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val detector: LeafDetector

    val classes: List<LeafClass>
    private val healthyIdx: List<Int>
    private val diseaseIdx: List<Int>
    private val size: Int  // 224
    private val mean: FloatArray
    private val std: FloatArray

    init {
        // Doc thang model tu assets vao bo nho (~16 MB). Khong chep ra filesDir,
        // de lan sau thay model moi trong assets thi app dung ngay model moi.
        val modelBytes = context.assets.open(modelAsset).use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
        inputName = session.inputNames.first()  // "input"
        detector = LeafDetector(context, detectorAsset)

        val json = JSONObject(context.assets.open(labelsAsset).bufferedReader().use { it.readText() })
        size = json.getInt("img_size")
        mean = FloatArray(3) { json.getJSONArray("mean").getDouble(it).toFloat() }
        std = FloatArray(3) { json.getJSONArray("std").getDouble(it).toFloat() }
        val arr = json.getJSONArray("labels")
        classes = List(arr.length()) { i ->
            val c = arr.getJSONObject(i)
            require(c.getInt("index") == i) { "$labelsAsset: nhan thu $i bi sai thu tu" }
            LeafClass(
                id = c.getString("id"),
                code = c.getString("code"),
                nameVi = c.getString("name_vi"),
                healthy = c.getBoolean("is_healthy"),
                lcdVi = c.optString("lcd_vi", c.getString("code"))
            )
        }
        healthyIdx = classes.indices.filter { classes[it].healthy }
        diseaseIdx = classes.indices.filter { !classes[it].healthy }
    }

    /** Phan tich 1 anh (anh ca vuon tu ESP32-CAM hoac anh 1 chiec la). */
    fun analyze(image: Bitmap): LeafResult {
        if (brightness(image) < DARK_MAX_BRIGHTNESS) return LeafResult("too_dark", "none", 0f, null, 0)

        // Buoc 1: tim la. Bo la qua nho, giu toi da MAX_LEAVES la chac chan nhat
        val leaves = detector.detect(image, DET_CONF)
            .filter { minOf(it.width, it.height) >= LEAF_MIN_SIDE }
            .take(MAX_LEAVES)
        if (leaves.isEmpty()) return LeafResult("no_leaf", "none", 0f, null, 0)

        // Buoc 2: cham diem tung la
        val probs = classify(leaves.map { leaf ->
            val c = LeafVote.leafCropBox(leaf, image.width, image.height)
            Bitmap.createBitmap(image, c.x0, c.y0, c.width, c.height)
        })
        val diseaseProbs = probs.map { p -> 1f - healthyIdx.sumOf { p[it].toDouble() }.toFloat() }

        // Buoc 3: bo phieu
        val (level, diseaseProb) = LeafVote.vote(diseaseProbs)
        // Benh gi: trung binh xac suat cac lop benh cua nhung la bi nghi (khong co la nao bi nghi thi lay ca anh)
        val voters = diseaseProbs.indices.filter { diseaseProbs[it] >= LeafVote.SUSPECT_THRESHOLD }
            .ifEmpty { diseaseProbs.indices.toList() }
        val k = diseaseIdx.maxBy { i -> voters.sumOf { probs[it][i].toDouble() } }
        // Cay gi (khi khoe): lop "khoe" co trung binh xac suat cao nhat tren moi la
        val h = healthyIdx.maxBy { i -> probs.sumOf { it[i].toDouble() } }
        return LeafResult("ok", level, diseaseProb, classes[k], leaves.size, classes[h])
    }

    /** Cham diem nhieu vung anh cung luc. Tra ve mang [so vung][15] xac suat (model da co san Softmax). */
    fun classify(crops: List<Bitmap>): Array<FloatArray> {
        val plane = size * size
        val data = FloatArray(crops.size * 3 * plane)
        val pixels = IntArray(plane)
        crops.forEachIndexed { n, bmp ->
            preprocess(bmp).getPixels(pixels, 0, size, 0, 0, size, size)
            val base = n * 3 * plane
            for (i in 0 until plane) {
                val px = pixels[i]
                // (x/255 - mean) / std, xep theo thu tu kenh R, G, B (NCHW)
                data[base + i] = (((px shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                data[base + plane + i] = (((px shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                data[base + 2 * plane + i] = ((px and 0xFF) / 255f - mean[2]) / std[2]
            }
        }
        val shape = longArrayOf(crops.size.toLong(), 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return result[0].value as Array<FloatArray>
            }
        }
    }

    /** Do sang trung binh 0-255 (kenh V cua HSV), giong brightness() trong predict.py */
    private fun brightness(image: Bitmap): Float {
        val small = smoothScale(image, 64, 64)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        val hsv = FloatArray(3)
        var vSum = 0
        for (px in pixels) {
            Color.colorToHSV(px, hsv)  // Android: V 0-1
            vSum += (hsv[2] * 255f).roundToInt()
        }
        return vSum / pixels.size.toFloat()
    }

    /** Giong get_transforms(train=False): thu canh ngan ve 256 (giu ti le), roi cat giua 224x224. */
    private fun preprocess(src: Bitmap): Bitmap {
        val short = minOf(src.width, src.height)
        // Kich thuoc dich tinh tu anh goc, lam tron xuong giong torchvision
        val w = if (src.width == short) RESIZE_SHORT else src.width * RESIZE_SHORT / short
        val h = if (src.height == short) RESIZE_SHORT else src.height * RESIZE_SHORT / short
        val resized = smoothScale(src, w, h)
        return Bitmap.createBitmap(resized, (w - size) / 2, (h - size) / 2, size, size)
    }

    fun close() {
        session.close()
        detector.close()
    }
}

/** Phan tinh toan thuan (khong dung Android) de unit test duoc, giong vote() va leaf_crop_box() trong ai/ */
object LeafVote {
    const val DISEASE_THRESHOLD = 0.9f  // P(benh) >= 90% -> "Benh"
    const val SUSPECT_THRESHOLD = 0.6f  // 60-90% -> "Nghi", duoi 60% -> "Khoe"
    const val MIN_VOTES = 2             // Can it nhat 2 la cung vuot nguong moi bao "Benh"; 1 la le chi bao "Nghi"
    const val CROP_PAD = 0.1            // Cat la rong hon khung 10% moi phia

    fun level(prob: Float): String = when {
        prob >= DISEASE_THRESHOLD -> "diseased"
        prob >= SUSPECT_THRESHOLD -> "suspect"
        else -> "healthy"
    }

    /**
     * Gop P(benh) cua tung la thanh (muc, P(benh)) cua ca anh. Lay la "benh" thu MIN_VOTES:
     * no vuot nguong nghia la co it nhat MIN_VOTES la vuot nguong. Anh chi co 1 la thi la do tu quyet.
     */
    fun vote(diseaseProbs: List<Float>): Pair<String, Float> {
        val ranked = diseaseProbs.sortedDescending()
        val score = ranked[minOf(MIN_VOTES, ranked.size) - 1]
        val lv = level(score)
        if (lv == "healthy" && ranked[0] >= DISEASE_THRESHOLD) {
            return Pair("suspect", ranked[0])  // Chi 1 la benh ro: chua du phieu bao "Benh", nhung nen ra xem cay
        }
        return Pair(lv, score)
    }

    /** Vung cat 1 la: hinh vuong quanh khung (canh = canh dai + 2*10%), day vao trong anh neu la sat mep. */
    fun leafCropBox(box: LeafBox, w: Int, h: Int): LeafBox {
        val side = minOf(maxOf(box.width, box.height) * (1 + 2 * CROP_PAD), w.toDouble(), h.toDouble())
        val cx = ((box.x0 + box.x1) / 2.0).coerceIn(side / 2, w - side / 2)
        val cy = ((box.y0 + box.y1) / 2.0).coerceIn(side / 2, h - side / 2)
        // Math.rint = round() cua Python (x.5 ve so chan): vung cat trung tung pixel voi predict.py
        return LeafBox(
            Math.rint(cx - side / 2).toInt(), Math.rint(cy - side / 2).toInt(),
            Math.rint(cx + side / 2).toInt(), Math.rint(cy + side / 2).toInt()
        )
    }
}
