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
data class LeafClass(val id: String, val code: String, val nameVi: String, val healthy: Boolean)

/** Ket qua phan tich 1 anh, giong Predictor.analyze() trong ai/predict.py */
data class LeafResult(
    val status: String,            // "ok" / "too_dark" / "no_leaf"
    val level: String,             // "healthy" / "suspect" / "diseased" ("none" khi status khac "ok")
    val diseaseProb: Float,        // P(benh) = 1 - tong xac suat cac lop khoe, cua vung "benh" nhat
    val diseaseClass: LeafClass?,  // Neu la benh thi kha nang cao nhat la benh nay
    val regions: Int,              // So vung da cham diem (ca anh + cac o co la)
) {
    /** Chuoi toi da 13 ky tu, Nano hien sau "AI:" tren LCD. Giong lcd_text() trong predict.py */
    fun lcdText(): String = when {
        status == "too_dark" -> "Anh qua toi"
        status == "no_leaf" -> "Khong thay la"
        level == "diseased" -> "Benh ${(diseaseProb * 100).toInt()}% ${diseaseClass?.code}"
        level == "suspect" -> "Nghi ${(diseaseProb * 100).toInt()}% ${diseaseClass?.code}"
        else -> "Khoe ${((1 - diseaseProb) * 100).toInt()}%"
    }
}

/**
 * Model phat hien benh tren la (EfficientNet-B0, 15 lop) chay bang ONNX Runtime.
 *
 * Model duoc train tren anh tung chiec la, con ESP32-CAM chup ca vuon, nen anh lon duoc chia luoi 3x3 o
 * chong lan; o nao it mau la (dat, troi, chau...) thi bo qua. Xac suat benh cua anh = cao nhat trong cac vung.
 */
class LeafDiseaseClassifier(
    context: Context,
    modelAsset: String = "leaf_disease_model.onnx",
    labelsAsset: String = "leaf_labels.json",
) {
    companion object {
        // Giong ai/config.py va ai/predict.py: doi o ben do thi doi ca o day
        const val DISEASE_THRESHOLD = 0.9f    // P(benh) >= 90% -> "Benh"
        const val SUSPECT_THRESHOLD = 0.6f    // 60-90% -> "Nghi", duoi 60% -> "Khoe"
        const val TILE_GRID = 3
        const val TILE_OVERLAP = 0.25         // Moi o rong hon 25% so voi chia deu
        const val LEAF_MIN_RATIO = 0.15f      // Ca anh it hon 15% mau la -> "Khong thay la"
        const val TILE_LEAF_MIN_RATIO = 0.5f  // 1 o chi duoc cham diem khi >= 50% la mau la
        const val DARK_MAX_BRIGHTNESS = 35f   // Do sang trung binh (0-255) duoi muc nay -> anh qua toi
        const val RESIZE_SHORT = 256          // get_transforms(train=False): Resize(256) roi CenterCrop(224)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

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

        val json = JSONObject(context.assets.open(labelsAsset).bufferedReader().use { it.readText() })
        size = json.getInt("img_size")
        mean = FloatArray(3) { json.getJSONArray("mean").getDouble(it).toFloat() }
        std = FloatArray(3) { json.getJSONArray("std").getDouble(it).toFloat() }
        val arr = json.getJSONArray("labels")
        classes = List(arr.length()) { i ->
            val c = arr.getJSONObject(i)
            require(c.getInt("index") == i) { "$labelsAsset: nhan thu $i bi sai thu tu" }
            LeafClass(c.getString("id"), c.getString("code"), c.getString("name_vi"), c.getBoolean("is_healthy"))
        }
        healthyIdx = classes.indices.filter { classes[it].healthy }
        diseaseIdx = classes.indices.filter { !classes[it].healthy }
    }

    /** Phan tich 1 anh (anh ca vuon tu ESP32-CAM hoac anh 1 chiec la). */
    fun analyze(image: Bitmap): LeafResult {
        val (leafRatio, brightness) = colorStats(image)
        if (brightness < DARK_MAX_BRIGHTNESS) return LeafResult("too_dark", "none", 0f, null, 0)

        // Vung dau tien luon la ca anh, sau do la cac o phan lon la mau la cay
        val areas = mutableListOf(image)
        for (tile in makeTiles(image)) {
            if (colorStats(tile).first >= TILE_LEAF_MIN_RATIO) areas.add(tile)
        }
        if (leafRatio < LEAF_MIN_RATIO && areas.size == 1) return LeafResult("no_leaf", "none", 0f, null, 0)

        val worst = classify(areas).maxBy { diseaseProbOf(it) }
        val diseaseProb = diseaseProbOf(worst)
        val k = diseaseIdx.maxBy { worst[it] }  // Chi xet cac lop benh de biet "neu benh thi la benh gi"
        val level = when {
            diseaseProb >= DISEASE_THRESHOLD -> "diseased"
            diseaseProb >= SUSPECT_THRESHOLD -> "suspect"
            else -> "healthy"
        }
        return LeafResult("ok", level, diseaseProb, classes[k], areas.size)
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

    private fun diseaseProbOf(p: FloatArray): Float = 1f - healthyIdx.sumOf { p[it].toDouble() }.toFloat()

    /** Chia anh thanh luoi 3x3 o chong lan, giong make_tiles() trong predict.py */
    private fun makeTiles(image: Bitmap): List<Bitmap> {
        val w = image.width
        val h = image.height
        val tw = (w / TILE_GRID.toDouble() * (1 + TILE_OVERLAP)).toInt()
        val th = (h / TILE_GRID.toDouble() * (1 + TILE_OVERLAP)).toInt()
        if (minOf(tw, th) < size) return emptyList()  // Anh nho: phong to vung nho chi lam anh mo them
        val tiles = mutableListOf<Bitmap>()
        for (row in 0 until TILE_GRID) {
            for (col in 0 until TILE_GRID) {
                val x = (col * (w - tw) / (TILE_GRID - 1).toDouble()).roundToInt()
                val y = (row * (h - th) / (TILE_GRID - 1).toDouble()).roundToInt()
                tiles.add(Bitmap.createBitmap(image, x, y, tw, th))
            }
        }
        return tiles
    }

    /** Tra ve (ti le pixel co mau la cay, do sang trung binh 0-255), giong color_stats() trong predict.py */
    private fun colorStats(image: Bitmap): Pair<Float, Float> {
        val small = smoothScale(image, 64, 64)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        val hsv = FloatArray(3)
        var plant = 0
        var vSum = 0
        for (px in pixels) {
            Color.colorToHSV(px, hsv)  // Android: H 0-360 do, S va V 0-1
            // Doi ve thang 0-255 cua PIL. Hue 25..130 ~ 35..185 do: tu vang (la benh) den xanh la.
            // Bo pixel nhat mau (nen xam, troi trang) va pixel qua toi.
            val h = (hsv[0] / 360f * 255f).toInt()
            val s = (hsv[1] * 255f).toInt()
            val v = (hsv[2] * 255f).roundToInt()
            if (h in 25..130 && s >= 50 && v >= 40) plant++
            vSum += v
        }
        return Pair(plant / pixels.size.toFloat(), vSum / pixels.size.toFloat())
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

    /**
     * Thu nho anh cho muot giong PIL: anh ESP32-CAM 1600x1200 phai thu nho rat manh,
     * thu 1 phat se bi rang cua lam model doan sai, nen thu tung buoc 1/2 roi moi ve dung kich thuoc.
     */
    private fun smoothScale(src: Bitmap, w: Int, h: Int): Bitmap {
        var bmp = src
        while (bmp.width / 2 >= w && bmp.height / 2 >= h) {
            bmp = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
        }
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    fun close() {
        session.close()
    }
}
