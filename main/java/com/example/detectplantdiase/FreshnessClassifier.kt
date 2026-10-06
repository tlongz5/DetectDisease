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

data class Prediction(val label: String, val confidence: Float)

class FreshnessClassifier(context: Context, modelAsset: String = "detect_plant_disease.onnx") {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val labels = HashMap<Int, String>()
    private val size = 224

    init {
        val modelFile = File(context.filesDir, modelAsset)
        if (!modelFile.exists()) {
            context.assets.open(modelAsset).use { input ->
                modelFile.outputStream().use { input.copyTo(it) }
            }
        }
        session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        val json = context.assets.open("labels.json").bufferedReader().use { it.readText() }
        val map = JSONObject(json).getJSONObject("id2label")
        map.keys().forEach { k -> labels[k.toInt()] = map.getString(k) }
    }

    fun predict(bitmap: Bitmap): Prediction {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        // chuẩn hoá (x/255 - 0.5) / 0.5
        val data = FloatArray(3 * size * size)
        val plane = size * size
        for (i in pixels.indices) {
            val p = pixels[i]
            data[i]             = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f // R
            data[plane + i]     = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f  // G
            data[2 * plane + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f          // B
        }

        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { input ->
            session.run(mapOf("pixel_values" to input)).use  { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<FloatArray>)[0]
                return pickTomatoPotato(logits)
            }
        }
    }

    private fun pickTomatoPotato(logits: FloatArray): Prediction {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - max) }
        val keep = labels.filterValues { it.startsWith("Tomato") || it.startsWith("Potato") || it.startsWith("Bellpepper") }.keys
        val total = keep.sumOf { exps[it].toDouble() }.toFloat()
        val best = keep.maxByOrNull { exps[it] }!!
        return Prediction(labels[best]!!, exps[best] / total)
    }

    fun close() {
        session.close()
    }

}