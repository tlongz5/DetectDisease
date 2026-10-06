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
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var classifier: FreshnessClassifier
    private lateinit var imageView: ImageView
    private lateinit var tvResult: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { classify { loadBitmap(it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.imageView)
        tvResult = findViewById(R.id.tvResult)

        tvResult.text = "Đang tải model..."
        lifecycleScope.launch(Dispatchers.Default) {
            classifier = FreshnessClassifier(this@MainActivity)
            withContext(Dispatchers.Main) { tvResult.text = "Sẵn sàng" }
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnEsp).setOnClickListener {
            // Đổi IP theo ESP32-CAM của bạn; endpoint /capture là của ví dụ CameraWebServer
            classify { URL("http://192.168.1.50/capture").openStream().use { BitmapFactory.decodeStream(it) } }
        }
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

    private fun classify(getBitmap: () -> Bitmap) {
        tvResult.text = "Đang phân tích..."
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val bmp = getBitmap()
                val t0 = System.currentTimeMillis()
                val pred = classifier.predict(bmp)
                val ms = System.currentTimeMillis() - t0
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bmp)
                    tvResult.text = "${pred.label}\nĐộ tin cậy: ${"%.0f".format(pred.confidence * 100)}%  (${ms} ms)"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvResult.text = "Lỗi: ${e.message}" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::classifier.isInitialized) classifier.close()
    }
}