package com.example.detectplantdiase

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 2 model AI (la + qua) dung chung cho ca app: service chup tu ESP32-CAM, man Camera va anh tu thu vien.
 * Nap 1 lan cho ca tien trinh: model qua nang 343 MB, moi man tu nap 1 ban thi vua cham vua ton RAM.
 */
object AiEngine {
    /** Ket qua phan tich xong va da luu vao lich su. lcdStatus != null khi da gui ket qua ve ESP32-CAM */
    data class Outcome(val record: DetectionRecord, val lcdStatus: String?)

    // Kem theo BROADCAST_DETECTION_UPDATED: ban ghi vua luu va nguon anh (vd GardenMonitorService.SOURCE_ESP_MANUAL)
    const val EXTRA_RECORD_ID = "record_id"
    const val EXTRA_SOURCE = "source"

    private val loadLock = Mutex()
    private val runLock = Mutex()  // Chay tung anh mot: service va man Camera co the goi cung luc
    private var leafModel: LeafDiseaseClassifier? = null
    private var fruitModel: FreshnessClassifier? = null

    @Volatile var isLoaded = false
        private set

    /** Loi khi nap model, vd thieu file detect_plant_disease.onnx trong assets. Rong = nap du 2 model */
    @Volatile var loadErrors: List<String> = emptyList()
        private set

    val hasAnyModel: Boolean get() = leafModel != null || fruitModel != null

    /** Nap model neu chua nap. Lan dau mo app phai chep model qua 343 MB ra bo nho trong nen mat vai giay */
    suspend fun load(context: Context) = loadLock.withLock {
        if (isLoaded) return@withLock
        withContext(Dispatchers.Default) {
            val app = context.applicationContext
            val errors = mutableListOf<String>()
            leafModel = try {
                LeafDiseaseClassifier(app)
            } catch (e: Exception) {
                errors += "Model lá: ${e.message}"
                null
            }
            fruitModel = try {
                FreshnessClassifier(app)
            } catch (e: Exception) {
                errors += "Model quả: chưa có file detect_plant_disease.onnx trong assets"
                null
            }
            loadErrors = errors
            isLoaded = true
        }
    }

    /**
     * Phan tich 1 anh, luu vao lich su va bao cho cac man hinh cap nhat.
     * sendToEsp: anh chup tu ESP32-CAM thi gui 2 dong ket qua ve cho Nano hien len LCD.
     */
    suspend fun analyzeAndSave(context: Context, bitmap: Bitmap, source: String, sendToEsp: Boolean): Outcome {
        load(context)
        check(hasAnyModel) { "Không có model AI nào dùng được" }
        val app = context.applicationContext
        return runLock.withLock {
            withContext(Dispatchers.Default) {
                val t0 = System.currentTimeMillis()
                val leafResult = leafModel?.analyze(bitmap)
                val fruitResult = fruitModel?.predict(bitmap)
                val ms = System.currentTimeMillis() - t0

                val lcd = LcdMessage.from(leafResult, fruitResult)
                val sent = if (sendToEsp) lcd.sendTo(app, ConfigManager.getBaseUrl(app)) else null
                val record = HistoryManager.saveDetection(
                    context = app,
                    bitmap = bitmap,
                    leafResult = leafResult,
                    fruitResult = fruitResult,
                    source = source,
                    inferenceTimeMs = ms,
                    lcdText = if (sendToEsp) lcd.lcdText() else null
                )
                app.sendBroadcast(
                    Intent(GardenMonitorService.BROADCAST_DETECTION_UPDATED).setPackage(app.packageName)
                        .putExtra(EXTRA_RECORD_ID, record.id)
                        .putExtra(EXTRA_SOURCE, source)
                )
                Outcome(record, sent)
            }
        }
    }
}
