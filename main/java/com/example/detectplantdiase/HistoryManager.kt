package com.example.detectplantdiase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object HistoryManager {
    private const val FILE_NAME = "detection_history.json"
    private const val MAX_HISTORY_SIZE = 50

    @Synchronized
    fun getHistory(context: Context): List<DetectionRecord> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            val jsonStr = file.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<DetectionRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(fromJson(obj))
            }
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    fun getLatest(context: Context): DetectionRecord? {
        val list = getHistory(context)
        return list.firstOrNull()
    }

    @Synchronized
    fun getRecordById(context: Context, id: String): DetectionRecord? {
        return getHistory(context).firstOrNull { it.id == id }
    }

    @Synchronized
    fun saveDetection(
        context: Context,
        bitmap: Bitmap,
        leafResult: LeafResult?,
        fruitResult: Prediction?,
        source: String = "ESP32-CAM",
        inferenceTimeMs: Long = 0L,
        lcdText: String? = null
    ): DetectionRecord {
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // 1. Lưu ảnh vào file nội bộ
        val imageDir = File(context.filesDir, "detections")
        if (!imageDir.exists()) imageDir.mkdirs()
        val imageFile = File(imageDir, "$id.jpg")
        try {
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Phân tích Dấu hiệu lá
        val (leafStatus, leafLevel, leafProb, leafCode, leafNameVi, leafSymptoms) = parseLeafInfo(leafResult)

        // 3. Phân tích Dấu hiệu quả
        val (fruitLabel, fruitConf, fruitNameVi, fruitSymptoms) = parseFruitInfo(fruitResult)

        // 4. Tổng kết tình trạng & Lời khuyên
        val (overallStatus, overallTitle, overallSummary, advice) = evaluateOverall(
            leafLevel = leafLevel,
            leafNameVi = leafNameVi,
            leafProb = leafProb,
            fruitLabel = fruitLabel,
            fruitNameVi = fruitNameVi,
            fruitSymptoms = fruitSymptoms
        )

        val record = DetectionRecord(
            id = id,
            timestamp = timestamp,
            imagePath = imageFile.absolutePath,
            source = source,
            leafStatus = leafStatus,
            leafLevel = leafLevel,
            leafDiseaseProb = leafProb,
            leafDiseaseCode = leafCode,
            leafDiseaseNameVi = leafNameVi,
            leafSymptoms = leafSymptoms,
            fruitLabel = fruitLabel,
            fruitConfidence = fruitConf,
            fruitNameVi = fruitNameVi,
            fruitSymptoms = fruitSymptoms,
            overallStatus = overallStatus,
            overallTitle = overallTitle,
            overallSummary = overallSummary,
            advice = advice,
            inferenceTimeMs = inferenceTimeMs,
            lcdText = lcdText
        )

        // 5. Cập nhật danh sách lưu trữ
        val currentList = getHistory(context).toMutableList()
        currentList.add(0, record)

        // Dọn bớt các bản ghi quá giới hạn
        if (currentList.size > MAX_HISTORY_SIZE) {
            val removed = currentList.removeAt(currentList.lastIndex)
            try {
                File(removed.imagePath).delete()
            } catch (_: Exception) {}
        }

        saveListToFile(context, currentList)
        return record
    }

    private fun parseLeafInfo(leaf: LeafResult?): LeafParsed {
        if (leaf == null) {
            return LeafParsed(
                status = "unknown",
                level = "none",
                prob = 0f,
                code = null,
                nameVi = null,
                symptoms = "Chưa kích hoạt hoặc không tìm thấy model nhận diện bệnh lá."
            )
        }

        val code = leaf.diseaseClass?.code
        val nameVi = leaf.diseaseClass?.nameVi

        val symptoms = when {
            leaf.status == "too_dark" ->
                "Ảnh quá tối, độ sáng không đủ để phân tích sắc tố và vết đốm trên lá cây."
            leaf.status == "no_leaf" ->
                "Không nhận diện được lá cà chua trong khung hình (camera có thể bị lệch hướng hoặc bị che khuất)."
            leaf.level == "diseased" ->
                "Cảnh báo bệnh hại: Tán lá có tổn thương rõ rệt với xác suất nhiễm ${(leaf.diseaseProb * 100).toInt()}%. Chẩn đoán: $nameVi. Các vùng đốm lá bị hoại tử hoặc biến màu bất thường."
            leaf.level == "suspect" ->
                "Nghi ngờ mầm bệnh: Phát hiện dấu hiệu bất thường tiềm ẩn (${(leaf.diseaseProb * 100).toInt()}% nghi ngờ $nameVi). Cần quan sát thêm mặt dưới của lá."
            else ->
                "Lá xanh tươi khỏe mạnh: Cấu trúc phiến lá nguyên vẹn, màu sắc đồng đều, không phát hiện vết bệnh (Độ an toàn: ${((1 - leaf.diseaseProb) * 100).toInt()}%)."
        }

        return LeafParsed(
            status = leaf.status,
            level = leaf.level,
            prob = leaf.diseaseProb,
            code = code,
            nameVi = nameVi,
            symptoms = symptoms
        )
    }

    private data class LeafParsed(
        val status: String,
        val level: String,
        val prob: Float,
        val code: String?,
        val nameVi: String?,
        val symptoms: String
    )

    private fun parseFruitInfo(fruit: Prediction?): FruitParsed {
        if (fruit == null) {
            return FruitParsed(
                label = null,
                confidence = 0f,
                nameVi = "Chưa có dữ liệu quả",
                symptoms = "Chưa kích hoạt model nhận diện quả hoặc không phát hiện quả trong ảnh."
            )
        }

        val label = fruit.label
        val conf = fruit.confidence
        val pct = (conf * 100).toInt()

        // Nhận diện quả tỉ lệ nhỏ hơn 50% (< 0.5) => không rõ
        if (conf < 0.5f || label == "unknown" || fruit.nameVi.contains("không rõ", ignoreCase = true)) {
            return FruitParsed(
                label = "unknown",
                confidence = conf,
                nameVi = "Không rõ",
                symptoms = "Tỉ lệ nhận diện quả chỉ đạt $pct% (< 50%): Chưa đủ cơ sở kết luận hoặc không phát hiện quả rõ nét trong ảnh."
            )
        }

        val nameVi: String
        val symptoms: String

        val isRotten = label.contains("rotten", ignoreCase = true)
        val isFresh = label.contains("fresh", ignoreCase = true)
        val isInter = label.contains("intermediate", ignoreCase = true)

        val plantName = "Cà chua"  // FreshnessClassifier chỉ trả về nhãn cà chua

        val baseNameVi = fruit.nameVi.ifEmpty { null }
        when {
            isRotten -> {
                nameVi = baseNameVi ?: "$plantName - Bị thối hỏng"
                symptoms = "Quả có dấu hiệu bị hư hỏng, thâm nhũn hoặc nhiễm nấm mốc (Độ tin cậy: $pct%). Nguy cơ lây nhiễm sang các quả lân cận."
            }
            isInter -> {
                nameVi = baseNameVi ?: "$plantName - Kém tươi"
                symptoms = "Quả có dấu hiệu giảm độ ẩm và kém tươi (Độ tin cậy: $pct%). Nên thu hoạch hoặc sử dụng sớm."
            }
            isFresh -> {
                nameVi = baseNameVi ?: "$plantName - Tươi tốt"
                symptoms = "Quả căng mọng, bề mặt nhẵn bóng, không có vết thâm nấm (Độ tin cậy: $pct%). Phát triển rất tốt."
            }
            else -> {
                nameVi = baseNameVi ?: label
                symptoms = "Nhận diện quả: $nameVi với độ tin cậy $pct%."
            }
        }

        return FruitParsed(
            label = label,
            confidence = conf,
            nameVi = nameVi,
            symptoms = symptoms
        )
    }

    private data class FruitParsed(
        val label: String?,
        val confidence: Float,
        val nameVi: String,
        val symptoms: String
    )

    private fun evaluateOverall(
        leafLevel: String,
        leafNameVi: String?,
        leafProb: Float,
        fruitLabel: String?,
        fruitNameVi: String,
        fruitSymptoms: String
    ): OverallEvaluation {
        val isFruitKnown = fruitLabel != null && fruitLabel != "unknown" && !fruitNameVi.contains("không rõ", ignoreCase = true)
        val isFruitRotten = isFruitKnown && fruitLabel.contains("rotten", ignoreCase = true)
        val isFruitInter = isFruitKnown && fruitLabel.contains("intermediate", ignoreCase = true)
        val isLeafDiseased = leafLevel == "diseased"
        val isLeafSuspect = leafLevel == "suspect"

        return when {
            isLeafDiseased && isFruitRotten -> {
                OverallEvaluation(
                    status = "DISEASED",
                    title = "🚨 BÁO ĐỘNG ĐỎ: BỆNH HẠI CẢ LÁ & QUẢ",
                    summary = "Cây đang gặp tình trạng nghiêm trọng: Xuất hiện bệnh lá ($leafNameVi) đồng thời quả có dấu hiệu thối hỏng.",
                    advice = "1. Cắt bỏ ngay lập tức các lá bệnh và thu gom quả thối tiêu hủy xa vườn.\n2. Phun thuốc đặc trị bảo vệ thực vật tương ứng với bệnh $leafNameVi.\n3. Vệ sinh khử khuẩn khu vực trồng, giảm độ ẩm đọng trên tán cây."
                )
            }
            isLeafDiseased -> {
                val pct = (leafProb * 100).toInt()
                OverallEvaluation(
                    status = "DISEASED",
                    title = "⚠️ PHÁT HIỆN BỆNH LÁ CÂY",
                    summary = if (!isFruitKnown) "Phát hiện dấu hiệu bệnh lá: $leafNameVi (Độ tin cậy $pct%). Quả: Chưa rõ." else "Phát hiện dấu hiệu bệnh lá: $leafNameVi (Độ tin cậy $pct%). Tình trạng quả hiện tại: $fruitNameVi.",
                    advice = "1. Cắt tỉa các lá có vết bệnh để cô lập nguồn nấm/vi khuẩn.\n2. Phun thuốc trừ bệnh chuyên dụng cho $leafNameVi vào sáng sớm hoặc chiều mát.\n3. Hạn chế tưới ướt sũng bề mặt lá trong những ngày tới."
                )
            }
            isFruitRotten -> {
                OverallEvaluation(
                    status = "DISEASED",
                    title = "⚠️ PHÁT HIỆN QUẢ BỊ THỐI HỎNG",
                    summary = "Quả có hiện tượng thối rữa, hư hại. Lá cây chưa có biểu hiện bệnh rõ rệt.",
                    advice = "1. Thu hoạch và loại bỏ ngay các quả hỏng để ngăn ruồi vàng và nấm bệnh.\n2. Kiểm tra lại hệ thống tưới tiêu và độ thông thoáng của giàn cây.\n3. Bổ sung Canxi-Bo để vỏ quả săn chắc chống nứt quả."
                )
            }
            isLeafSuspect || isFruitInter -> {
                OverallEvaluation(
                    status = "WARNING",
                    title = "⚡ CẢNH BÁO: CẦN THEO DÕI THÊM",
                    summary = if (isLeafSuspect) "Lá cây có dấu hiệu bất thường nghi ngờ $leafNameVi." else "Quả có dấu hiệu kém tươi, cần lưu ý.",
                    advice = "1. Theo dõi sát diễn biến qua camera hoặc đến tận luống kiểm tra trực tiếp.\n2. Duy trì chế độ tưới nước đều đặn, không tưới quá đẫm vào ban đêm.\n3. Chuẩn bị sẵn chế phẩm sinh học phòng ngừa nếu triệu chứng rõ dần."
                )
            }
            leafLevel == "healthy" -> {
                OverallEvaluation(
                    status = "HEALTHY",
                    title = "🌿 CÂY PHÁT TRIỂN KHỎE MẠNH",
                    summary = if (!isFruitKnown) "Tán lá xanh tươi không bệnh, hình ảnh quả chưa rõ nét." else "Tán lá xanh tươi không bệnh, quả phát triển thuận lợi ($fruitNameVi).",
                    advice = "1. Tiếp tục duy trì chế độ chăm sóc và tưới tiêu hiện tại.\n2. Bón phân định kỳ theo từng giai đoạn phát triển của cây.\n3. Định kỳ theo dõi hình ảnh gửi về từ camera ESP32-CAM."
                )
            }
            else -> {
                OverallEvaluation(
                    status = "UNKNOWN",
                    title = "ℹ️ KẾT QUẢ CHƯA ĐỦ ĐIỀU KIỆN",
                    summary = "Hình ảnh có thể bị tối hoặc camera lệch hướng chưa ghi nhận rõ chi tiết tán lá/quả.",
                    advice = "1. Kiểm tra lại góc quay và khoảng cách của ESP32-CAM đến cây.\n2. Đảm bảo khu vực chụp có đủ ánh sáng tự nhiên.\n3. Thử chụp lại một ảnh cận cảnh hơn."
                )
            }
        }
    }

    private data class OverallEvaluation(
        val status: String,
        val title: String,
        val summary: String,
        val advice: String
    )

    private fun toJson(r: DetectionRecord): JSONObject {
        return JSONObject().apply {
            put("id", r.id)
            put("timestamp", r.timestamp)
            put("imagePath", r.imagePath)
            put("source", r.source)
            put("leafStatus", r.leafStatus)
            put("leafLevel", r.leafLevel)
            put("leafDiseaseProb", r.leafDiseaseProb.toDouble())
            put("leafDiseaseCode", r.leafDiseaseCode ?: "")
            put("leafDiseaseNameVi", r.leafDiseaseNameVi ?: "")
            put("leafSymptoms", r.leafSymptoms)
            put("fruitLabel", r.fruitLabel ?: "")
            put("fruitConfidence", r.fruitConfidence.toDouble())
            put("fruitNameVi", r.fruitNameVi)
            put("fruitSymptoms", r.fruitSymptoms)
            put("overallStatus", r.overallStatus)
            put("overallTitle", r.overallTitle)
            put("overallSummary", r.overallSummary)
            put("advice", r.advice)
            put("inferenceTimeMs", r.inferenceTimeMs)
            put("lcdText", r.lcdText ?: "")
        }
    }

    private fun fromJson(obj: JSONObject): DetectionRecord {
        return DetectionRecord(
            id = obj.getString("id"),
            timestamp = obj.getLong("timestamp"),
            imagePath = obj.getString("imagePath"),
            source = obj.optString("source", "ESP32-CAM"),
            leafStatus = obj.optString("leafStatus", "ok"),
            leafLevel = obj.optString("leafLevel", "none"),
            leafDiseaseProb = obj.optDouble("leafDiseaseProb", 0.0).toFloat(),
            leafDiseaseCode = obj.optString("leafDiseaseCode").ifEmpty { null },
            leafDiseaseNameVi = obj.optString("leafDiseaseNameVi").ifEmpty { null },
            leafSymptoms = obj.optString("leafSymptoms", ""),
            fruitLabel = obj.optString("fruitLabel").ifEmpty { null },
            fruitConfidence = obj.optDouble("fruitConfidence", 0.0).toFloat(),
            fruitNameVi = obj.optString("fruitNameVi", "Quả"),
            fruitSymptoms = obj.optString("fruitSymptoms", ""),
            overallStatus = obj.optString("overallStatus", "UNKNOWN"),
            overallTitle = obj.optString("overallTitle", "Kết quả kiểm tra"),
            overallSummary = obj.optString("overallSummary", ""),
            advice = obj.optString("advice", ""),
            inferenceTimeMs = obj.optLong("inferenceTimeMs", 0L),
            lcdText = obj.optString("lcdText").ifEmpty { null }
        )
    }

    private fun saveListToFile(context: Context, list: List<DetectionRecord>) {
        try {
            val array = JSONArray()
            list.forEach { array.put(toJson(it)) }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss - dd/MM/yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatTimeShort(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm • dd/MM", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun loadBitmapFromFile(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
