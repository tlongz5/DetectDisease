package com.example.detectplantdiase

import android.content.Context
import java.text.Normalizer

/**
 * Ket qua AI gui cho ESP32-CAM (POST /result) de Nano hien len LCD 1602 trong 20 giay:
 * dong 1 = tinh trang la, dong 2 = tinh trang qua. Ca 2 model chi dung cho ca chua. Vd:
 *   "La ca chua: Moc suong 97%"
 *   "Qua ca chua: Tuoi 92%"
 * LCD khong hien duoc dau tieng Viet nen chi dung ky tu ASCII. Dong dai hon 16 ky tu thi Nano cho chay chu.
 *
 * mistMode: che do phun suong theo tinh trang la (xem MIST_*). La uot lam benh nam, vi khuan lan nhanh
 * nen la benh thi dung phun; nhen do so am nen phun day hon.
 */
data class LcdMessage(val leafLine: String, val fruitLine: String, val mistMode: Int) {

    /**
     * Than POST /result, dang text 4 dong: "<dong la>\n<dong qua>\n<che do phun>\n<gio Unix, giay>".
     * Gio cua dien thoai giup ESP32-CAM biet gio ca khi khong co Internet (chi phat WiFi TuoiCay-CAM).
     */
    fun body(nowSec: Long = System.currentTimeMillis() / 1000): String =
        "$leafLine\n$fruitLine\n$mistMode\n$nowSec"

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

        // Che do phun suong, khop MIST_* trong src/main.cpp cua Nano
        const val MIST_KEEP = 0    // Khong xem duoc la (anh toi, khong thay la): giu che do dang chay
        const val MIST_NORMAL = 1  // 30 giay moi 30 phut, 8h-16h
        const val MIST_STOP = 2    // Dung phun: la benh nam / vi khuan, hoac nghi benh
        const val MIST_BOOST = 3   // 30 giay moi 15 phut, 8h-16h: chac chan nhen do

        private const val SPIDER_MITES = "Tomato_Spider_mites"
        // Benh virus lay qua con trung / tiep xuc, do am la khong lam benh nang them
        private val VIRUS = setOf("Tomato_Mosaic", "Tomato_Yellow_Curl")

        // Model la chi con 10 lop ca chua; model qua chi lay 3 nhan "Tomato_<tinh trang>"
        private const val LEAF_HEAD = "La ca chua"
        private const val FRUIT_HEAD = "Qua ca chua"
        private val FRUIT_STATE = mapOf(
            "fresh" to "Tuoi",
            "intermediate_fresh" to "Kem tuoi",
            "rotten" to "Thoi hong",
        )

        /** Tao 2 dong LCD tu ket qua 2 model. Model nao chua co (null) thi dong do bao thieu model. */
        fun from(leaf: LeafResult?, fruit: Prediction?): LcdMessage =
            LcdMessage(clean(leafLine(leaf)), clean(fruitLine(fruit, leaf)), mistMode(leaf))

        private fun mistMode(r: LeafResult?): Int {
            if (r == null || r.status != "ok") return MIST_KEEP
            return when (r.level) {
                "healthy" -> MIST_NORMAL
                "suspect" -> MIST_STOP  // Chua chac benh gi: dung cho an toan
                else -> when (r.diseaseClass?.id) {  // "diseased": P(benh) >= 90%
                    SPIDER_MITES -> MIST_BOOST
                    in VIRUS -> MIST_NORMAL
                    else -> MIST_STOP
                }
            }
        }

        private fun leafLine(r: LeafResult?): String {
            if (r == null) return "Thieu model la"
            if (r.status == "too_dark") return "Anh qua toi"
            if (r.status == "no_leaf") return "Khong thay la ca chua"

            // Lam tron xuong giong predict.py: 89.7% (muc "Nghi") hien "89%", khong thanh "90%" de gay nham
            return when (r.level) {
                "diseased" -> "$LEAF_HEAD: ${r.diseaseClass?.lcdVi ?: "Benh"} ${pct(r.diseaseProb, 99)}%"
                "suspect" -> "$LEAF_HEAD: Nghi ${r.diseaseClass?.lcdVi ?: "benh"} ${pct(r.diseaseProb, 99)}%"
                else -> "$LEAF_HEAD: Khoe ${pct(1 - r.diseaseProb, 100)}%"
            }
        }

        private fun fruitLine(f: Prediction?, leaf: LeafResult?): String {
            if (f == null) return "Thieu model qua"
            // Anh qua toi thi model qua cung chi doan bua
            if (leaf?.status == "too_dark") return "Khong ro qua"
            if (f.status == FreshnessClassifier.NO_TOMATO) return "Khong thay qua ca chua"
            if (f.label == "unknown" || f.confidence < FreshnessClassifier.CONFIDENCE_THRESHOLD) return "Khong ro qua"

            val state = FRUIT_STATE[f.label.removePrefix("Tomato_")]
            val p = pct(f.confidence, 100)
            return if (f.label.startsWith("Tomato_") && state != null) "$FRUIT_HEAD: $state $p%" else "${f.nameVi} $p%"
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
