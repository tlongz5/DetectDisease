package com.example.detectplantdiase

import android.content.Context
import java.text.Normalizer

/**
 * Ket qua AI gui cho ESP32-CAM (POST /result) de Nano hien len LCD 1602 trong 20 giay:
 * dong 1 = tinh trang la, dong 2 = tinh trang qua, ca 2 dong deu ghi ro la cay gi. Vd:
 *   "La ca chua: Moc suong 97%"
 *   "Qua ca chua: Tuoi 92%"
 * LCD khong hien duoc dau tieng Viet nen chi dung ky tu ASCII. Dong dai hon 16 ky tu thi Nano cho chay chu.
 *
 * leafLevel: 0 = khong thay benh (la khoe hoac khong xem duoc la), 1 = nghi benh, 2 = benh.
 * Nano dung muc nay de dung phun suong khi la benh (la uot lam benh nam lan nhanh hon).
 */
data class LcdMessage(val leafLine: String, val fruitLine: String, val leafLevel: Int) {

    /** Than POST /result, dang text 3 dong: "<dong la>\n<dong qua>\n<muc 0/1/2>" */
    fun body(): String = "$leafLine\n$fruitLine\n$leafLevel"

    /** 2 dong hien tren LCD, de luu vao lich su */
    fun lcdText(): String = "$leafLine\n$fruitLine"

    /**
     * Gui cho ESP32-CAM, ESP32-CAM chuyen tiep xuong Nano qua UART. Tra ve trang thai de hien len man hinh.
     * Co dung mang nen phai goi o luong nen (Dispatchers.Default / IO).
     */
    fun sendTo(context: Context, baseUrl: String): String = try {
        val conn = ConfigManager.openEsp(context, "$baseUrl/result")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body().toByteArray(Charsets.UTF_8)) }
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

    companion object {
        /** Moi dong toi da 40 ky tu, khop RESULT_MAX_LEN cua firmware ESP32-CAM (RAM Nano co han) */
        const val MAX_LINE = 40

        // Ten cay theo phan dau id lop cua model la (leaf_labels.json), vd "Tomato_Late_blight" -> "ca chua"
        private val LEAF_PLANT = mapOf(
            "Tomato" to "ca chua",
            "Potato" to "khoai tay",
            "Pepper" to "ot chuong",  // PlantVillage "Pepper,_bell" la ot chuong
        )

        // Nhan cua model qua (labels.json) dang "<Loai>_<tinh trang>", vd "Tomato_intermediate_fresh"
        private val FRUIT_PART = mapOf(
            "Tomato" to "Qua ca chua",
            "Bellpepper" to "Qua ot chuong",
            "Potato" to "Cu khoai tay",  // Khoai tay la cu, khong phai qua
        )
        private val FRUIT_STATE = mapOf(
            "fresh" to "Tuoi",
            "intermediate_fresh" to "Kem tuoi",
            "rotten" to "Thoi hong",
        )

        /** Tao 2 dong LCD tu ket qua 2 model. Model nao chua co (null) thi dong do bao thieu model. */
        fun from(leaf: LeafResult?, fruit: Prediction?): LcdMessage {
            val level = when (leaf?.takeIf { it.status == "ok" }?.level) {
                "diseased" -> 2
                "suspect" -> 1
                else -> 0
            }
            return LcdMessage(clean(leafLine(leaf)), clean(fruitLine(fruit, leaf)), level)
        }

        private fun leafLine(r: LeafResult?): String {
            if (r == null) return "Thieu model la"
            if (r.status == "too_dark") return "Anh qua toi"
            if (r.status == "no_leaf") return "Khong thay la"

            // Ten cay lay theo dung lop dang noi toi: la benh gi thi la benh cua cay do
            val cls = if (r.level == "healthy") r.healthyClass else r.diseaseClass
            val plant = cls?.id?.substringBefore('_')?.let { LEAF_PLANT[it] }
            val head = if (plant != null) "La $plant" else "La"
            // Lam tron xuong giong predict.py: 89.7% (muc "Nghi") hien "89%", khong thanh "90%" de gay nham
            return when (r.level) {
                "diseased" -> "$head: ${r.diseaseClass?.lcdVi ?: "Benh"} ${pct(r.diseaseProb, 99)}%"
                "suspect" -> "$head: Nghi ${r.diseaseClass?.lcdVi ?: "benh"} ${pct(r.diseaseProb, 99)}%"
                else -> "$head: Khoe ${pct(1 - r.diseaseProb, 100)}%"
            }
        }

        private fun fruitLine(f: Prediction?, leaf: LeafResult?): String {
            if (f == null) return "Thieu model qua"
            // Anh qua toi thi model qua cung chi doan bua
            if (leaf?.status == "too_dark") return "Khong ro qua"
            if (f.label == "unknown" || f.confidence < FreshnessClassifier.CONFIDENCE_THRESHOLD) return "Khong ro qua"

            val part = FRUIT_PART[f.label.substringBefore('_')]
            val state = FRUIT_STATE[f.label.substringAfter('_')]
            val p = pct(f.confidence, 100)
            return if (part != null && state != null) "$part: $state $p%" else "${f.nameVi} $p%"
        }

        private fun pct(prob: Float, max: Int): Int = (prob * 100).toInt().coerceIn(1, max)

        /** Bo dau tieng Viet, chi giu ky tu ASCII in duoc, cat con toi da MAX_LINE ky tu */
        private fun clean(text: String): String =
            Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .filter { it in ' '..'~' }
                .trim()
                .take(MAX_LINE)
    }
}
