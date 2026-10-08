package com.example.detectplantdiase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        // IP cua ESP32-CAM: xem dong "Da ket noi. IP: ..." tren Serial Monitor cua ESP32-CAM.
        // Android thuong khong phan giai duoc ten esp32cam.local nen dung IP.
        const val ESP_URL = "http://192.168.1.50"
    }

    // 2 model chay song song tren cung 1 anh: model la (benh tren la) va model qua (do tuoi cua qua).
    // Thieu file model nao thi model do = null, app van chay phan con lai.
    private var leafModel: LeafDiseaseClassifier? = null
    private var fruitModel: FreshnessClassifier? = null
    @Volatile private var modelsLoaded = false

    private lateinit var imageView: ImageView
    private lateinit var tvResult: TextView
    private lateinit var tvSoil: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { classify(sendToEsp = false) { loadBitmap(it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.imageView)
        tvResult = findViewById(R.id.tvResult)
        tvSoil = findViewById(R.id.tvSoil)

        tvResult.text = "Đang tải model..."
        lifecycleScope.launch(Dispatchers.Default) {
            val status = StringBuilder()
            leafModel = try {
                LeafDiseaseClassifier(this@MainActivity)
            } catch (e: Exception) {
                status.appendLine("Model lá: lỗi (${e.message})")
                null
            }
            fruitModel = try {
                FreshnessClassifier(this@MainActivity)
            } catch (e: Exception) {
                status.appendLine("Model quả: chưa có file detect_plant_disease.onnx trong assets")
                null
            }
            modelsLoaded = true
            withContext(Dispatchers.Main) { tvResult.text = status.append("Sẵn sàng").toString() }
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickImage.launch("image/*") }
        // Anh tu ESP32-CAM: phan tich xong thi gui ket qua nguoc ve ESP32-CAM de chuyen xuong Nano
        findViewById<Button>(R.id.btnEsp).setOnClickListener { classify(sendToEsp = true) { fetchEspImage() } }
        findViewById<Button>(R.id.btnSoil).setOnClickListener { refreshSoil() }
    }

    /** Doc do am dat (Nano gui sang ESP32-CAM moi 5 giay) roi hien len dong tvSoil */
    private fun refreshSoil() {
        tvSoil.text = "Độ ẩm đất: đang đọc..."
        lifecycleScope.launch(Dispatchers.IO) {
            val text = fetchSoilText()
            withContext(Dispatchers.Main) { tvSoil.text = text }
        }
    }

    /** Lay muc "soil" trong trang /status cua ESP32-CAM, tra ve 1 dong mo ta */
    private fun fetchSoilText(): String = try {
        val conn = URL("$ESP_URL/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val json = try {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val soil = json.optJSONObject("soil")  // null khi ESP chua nhan duoc dong SOIL nao tu Nano
        when {
            soil == null -> "Độ ẩm đất: ESP32-CAM chưa nhận được dữ liệu từ Nano"
            soil.getBoolean("fault") -> "Độ ẩm đất: cảm biến lỗi (Raw ${soil.getInt("raw")})"
            // optBoolean: firmware ESP cu chua gui "fan" thi coi nhu quat tat
            else -> "Độ ẩm đất: ${soil.getInt("moisture")}% • Bơm: ${if (soil.getBoolean("pump")) "BẬT" else "TẮT"}" +
                " • Quạt: ${if (soil.optBoolean("fan")) "BẬT" else "TẮT"} (${soil.getLong("age_s")} giây trước)"
        }
    } catch (e: Exception) {
        "Độ ẩm đất: không đọc được (${e.message})"
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val maxDim = 800
            val width = info.size.width
            val height = info.size.height
            if (width > maxDim || height > maxDim) {
                val scale = maxOf(width, height) / maxDim
                decoder.setTargetSampleSize(scale)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** Tai 1 anh JPEG tu ESP32-CAM. Co gioi han thoi gian de app khong bi treo khi ESP32-CAM mat mang. */
    private fun fetchEspImage(): Bitmap {
        val conn = URL("$ESP_URL/capture").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        try {
            return conn.inputStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("ESP32-CAM không trả về ảnh")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Gui chuoi ket qua cho ESP32-CAM (POST /result, noi dung la text). ESP32-CAM chuyen tiep xuong Nano
     * qua UART thanh dong "AI:<text>". Tra ve trang thai de hien len man hinh.
     */
    private fun sendResultToEsp(text: String): String = try {
        val conn = URL("$ESP_URL/result").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "text/plain")
        try {
            conn.outputStream.use { it.write(text.toByteArray()) }
            when (val code = conn.responseCode) {
                200 -> "đã gửi về Nano"
                404 -> "chưa gửi được: firmware ESP32-CAM chưa có /result"
                else -> "chưa gửi được: ESP32-CAM trả lỗi $code"
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "chưa gửi được: ${e.message}"
    }

    private fun classify(sendToEsp: Boolean, getBitmap: () -> Bitmap) {
        if (!modelsLoaded) {
            tvResult.text = "Model chưa tải xong, thử lại sau vài giây"
            return
        }
        val leaf = leafModel
        val fruit = fruitModel
        if (leaf == null && fruit == null) {
            tvResult.text = "Không có model nào dùng được"
            return
        }
        tvResult.text = "Đang phân tích..."
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val bmp = getBitmap()
                val t0 = System.currentTimeMillis()
                val leafResult = leaf?.analyze(bmp)
                val fruitResult = fruit?.predict(bmp)
                val ms = System.currentTimeMillis() - t0
                // LCD chi co 16 ky tu nen chi gui muc benh cua model la, vd "BENH 99%"
                val lcd = leafResult?.lcdText()
                val sent = if (sendToEsp && lcd != null) sendResultToEsp(lcd) else null
                val soil = if (sendToEsp) fetchSoilText() else null

                val text = buildString {
                    appendLine(leafResult?.let { describe(it) } ?: "Lá: chưa có model")
                    appendLine("QUẢ: " + (fruitResult?.let { "${it.label} (${"%.0f".format(it.confidence * 100)}%)" }
                        ?: "chưa có model"))
                    if (lcd != null) appendLine("LCD: $lcd" + (sent?.let { " • $it" } ?: ""))
                    append("(${ms} ms)")
                }
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bmp)
                    tvResult.text = text
                    if (soil != null) tvSoil.text = soil
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvResult.text = "Lỗi: ${e.message}" }
            }
        }
    }

    /**
     * Mo ta ket qua model la, giong describe() trong predict.py, vd:
     *   CO DAU HIEU BENH - xac suat benh 99%
     *   Neu benh, kha nang nhat: Ca chua - dom vong (benh som) [Tomato_Early_blight] 98%
     */
    private fun describe(r: LeafResult): String {
        if (r.status == "too_dark") return "Anh qua toi, khong phan tich"
        if (r.status == "no_leaf") return "Khong thay la cay trong anh (camera lech huong?)"
        val verdict = when (r.level) {
            "diseased" -> "CO DAU HIEU BENH"
            "suspect" -> "NGHI NGO, nen ra xem cay"
            else -> "Khoe manh"
        }
        val c = r.diseaseClass
        // toInt() lam tron xuong giong int() trong predict.py: 89.7% (muc "Nghi") hien 89%, khong thanh 90%
        return "$verdict - xac suat benh ${(r.diseaseProb * 100).toInt()}%\n" +
            "Neu benh, kha nang nhat: ${c?.nameVi} [${c?.id}] ${"%.0f".format(r.diseaseClassProb * 100)}%"
    }

    override fun onDestroy() {
        super.onDestroy()
        leafModel?.close()
        fruitModel?.close()
    }
}
