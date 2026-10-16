package com.example.detectplantdiase

import java.io.Serializable

/**
 * Bản ghi lưu trữ kết quả của một lần kiểm tra cây trồng
 */
data class DetectionRecord(
    val id: String,
    val timestamp: Long,
    val imagePath: String,
    val source: String,             // "ESP32-CAM" hoặc "Thư viện ảnh"

    // --- Dấu hiệu lá ---
    val leafStatus: String,         // "ok", "too_dark", "no_leaf"
    val leafLevel: String,          // "healthy", "suspect", "diseased", "none"
    val leafDiseaseProb: Float,     // Xác suất bệnh (0.0 .. 1.0)
    val leafDiseaseCode: String?,   // Mã viết tắt (vd: "TLB", "PBS")
    val leafDiseaseNameVi: String?, // Tên bệnh tiếng Việt (vd: "Cà chua - mốc sương")
    val leafSymptoms: String,       // Mô tả chi tiết dấu hiệu lá

    // --- Dấu hiệu quả ---
    val fruitLabel: String?,        // Nhãn gốc (vd: "Tomato_fresh", "Tomato_rotten")
    val fruitConfidence: Float,     // Độ tin cậy (0.0 .. 1.0)
    val fruitNameVi: String,        // Tên tiếng Việt (vd: "Cà chua tươi", "Quả bị thối")
    val fruitSymptoms: String,      // Mô tả chi tiết dấu hiệu quả

    // --- Tổng kết tình trạng ---
    val overallStatus: String,      // "HEALTHY", "WARNING", "DISEASED", "UNKNOWN"
    val overallTitle: String,       // Tóm tắt tình trạng (vd: "Cây khỏe mạnh", "Cảnh báo sâu bệnh")
    val overallSummary: String,     // Đánh giá tổng quát
    val advice: String,             // Biện pháp xử lý / khuyến nghị chăm sóc

    // Thông số kỹ thuật
    val inferenceTimeMs: Long,
    val lcdText: String? = null     // 2 dòng đã gửi lên LCD (lá, quả), cách nhau bằng "\n"
) : Serializable
