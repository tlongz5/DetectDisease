package com.example.detectplantdiase

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

data class FruitLabelDetail(
    val fruit: String,
    val status: String,
    val summary: String
)

data class Prediction(
    val label: String,
    val confidence: Float,
    val fruit: String = "",
    val status: String = "",
    val nameVi: String = ""
)

class FreshnessClassifier(context: Context, modelAsset: String = "detect_plant_disease.onnx") {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val labels = HashMap<Int, String>()
    private val details = HashMap<Int, FruitLabelDetail>()
    private val size: Int
    private val mean: FloatArray
    private val std: FloatArray

    init {
        val modelFile = File(context.filesDir, modelAsset)
        if (!modelFile.exists()) {
            context.assets.open(modelAsset).use { input ->
                modelFile.outputStream().use { input.copyTo(it) }
            }
        }
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        val json = JSONObject(context.assets.open("labels.json").bufferedReader().use { it.readText() })
        val map = json.getJSONObject("id2label")
        map.keys().forEach { k -> labels[k.toInt()] = map.getString(k) }

        val viMap = json.optJSONObject("labels_vi")
        if (viMap != null) {
            viMap.keys().forEach { k ->
                val obj = viMap.getJSONObject(k)
                details[k.toInt()] = FruitLabelDetail(
                    fruit = obj.optString("fruit"),
                    status = obj.optString("status"),
                    summary = obj.optString("summary")
                )
            }
        }
        
        size = json.getJSONArray("input_size").getInt(0)
        mean = FloatArray(3) { json.getJSONArray("mean").getDouble(it).toFloat() }
        std = FloatArray(3) { json.getJSONArray("std").getDouble(it).toFloat() }
    }

    private fun preprocess(src: Bitmap): Bitmap {
        val short = minOf(src.width, src.height)
        val resizeShort = (size * 1.14f).toInt() // Gần tương đương với resize 256 rồi crop 224
        val w = if (src.width == short) resizeShort else src.width * resizeShort / short
        val h = if (src.height == short) resizeShort else src.height * resizeShort / short
        val resized = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createBitmap(resized, (w - size) / 2, (h - size) / 2, size, size)
    }

    fun predict(bitmap: Bitmap): Prediction {
        val cropped = preprocess(bitmap)
        val pixels = IntArray(size * size)
        cropped.getPixels(pixels, 0, size, 0, 0, size, size)

        val data = FloatArray(3 * size * size)
        val plane = size * size
        for (i in pixels.indices) {
            val p = pixels[i]
            data[i]             = (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0] // R
            data[plane + i]     = (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1]  // G
            data[2 * plane + i] = ((p and 0xFF) / 255f - mean[2]) / std[2]          // B
        }

        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf("pixel_values" to input)).use  { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<FloatArray>)[0]
                return pickTomato(logits)
            }
        }
    }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.5f // Trong 3 nhãn cà chua, nhãn cao nhất dưới 50% => không rõ tươi hay hỏng
        // Model biết 30 loại nông sản. Tổng xác suất 3 nhãn cà chua (trên cả 30 nhãn) dưới 50% => không thấy quả cà chua.
        // Thử trên 69 ảnh lá cà chua PlantDoc: chỉ lọc 3 nhãn cà chua thì 34 ảnh bị báo có quả;
        // thêm ngưỡng này thì chỉ 1 ảnh qua (0.53), mà ảnh đó có quả cà chua xanh thật. Ảnh quả rõ được 0.92.
        const val MIN_TOMATO_PROB = 0.5f
        const val NO_TOMATO = "no_tomato"  // Prediction.status khi không thấy quả cà chua

        /** Chỉ xét quả cà chua. Trả về (nhãn cà chua cao nhất, xác suất của nó trong 3 nhãn cà chua, tổng P(cà chua)). */
        fun tomatoScores(logits: FloatArray, labels: Map<Int, String>): Triple<Int, Float, Float> {
            val max = logits.max()
            val exps = FloatArray(logits.size) { exp(logits[it] - max) }
            val all = exps.sum()
            val tomato = labels.filterValues { it.startsWith("Tomato_") }.keys
            val tomatoSum = tomato.sumOf { exps[it].toDouble() }.toFloat()
            val best = tomato.maxBy { exps[it] }
            return Triple(best, exps[best] / tomatoSum, tomatoSum / all)
        }
    }

    private fun pickTomato(logits: FloatArray): Prediction {
        val (best, conf, tomatoProb) = tomatoScores(logits, labels)
        val d = details[best]

        if (tomatoProb < MIN_TOMATO_PROB) {
            // confidence < 50% nên các màn hình lịch sử tự hiện "Không rõ"
            return Prediction(
                label = "unknown",
                confidence = tomatoProb,
                fruit = "",
                status = NO_TOMATO,
                nameVi = "không rõ (không thấy quả cà chua)"
            )
        }
        // Nhận diện quả tỉ lệ nhỏ hơn 50% thì => không rõ
        if (conf < CONFIDENCE_THRESHOLD) {
            return Prediction(
                label = "unknown",
                confidence = conf,
                fruit = "",
                status = "unknown",
                nameVi = "không rõ"
            )
        }

        return Prediction(
            label = labels[best]!!,
            confidence = conf,
            fruit = d?.fruit ?: "",
            status = d?.status ?: "",
            nameVi = d?.summary ?: labels[best]!!
        )
    }

    fun close() {
        session.close()
    }

}