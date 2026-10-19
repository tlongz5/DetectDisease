package com.example.detectplantdiase

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.Locale

/**
 * App luon nen trang: thanh trang thai / dieu huong trong suot, icon mau toi.
 * targetSdk 36 thi Android 15+ bat buoc ve tran vien, nen moi man tu chua cho thanh he thong (applySystemBarPadding).
 */
fun ComponentActivity.setupEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    )
}

/**
 * Cong them chieu cao thanh trang thai (top) / thanh dieu huong (bottom) vao padding san co cua view.
 * bottom tinh ca ban phim: ve tran vien thi Android khong tu day noi dung len khi ban phim hien (adjustResize).
 */
fun View.applySystemBarPadding(top: Boolean = false, bottom: Boolean = false) {
    val startTop = paddingTop
    val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.updatePadding(
            top = if (top) startTop + bars.top else startTop,
            bottom = if (bottom) startBottom + maxOf(bars.bottom, ime.bottom) else startBottom
        )
        insets
    }
    if (isAttachedToWindow) ViewCompat.requestApplyInsets(this)
}

/** Mau + nhan + icon cho 1 tinh trang (tong the, la hoac qua) */
data class StatusStyle(
    val label: String,
    @param:ColorRes val color: Int,
    @param:ColorRes val container: Int,
    @param:DrawableRes val icon: Int,
)

private val NEUTRAL = StatusStyle("Chưa rõ", R.color.text_secondary, R.color.neutral_container, R.drawable.ic_info)

fun overallStyle(status: String): StatusStyle = when (status) {
    "DISEASED" -> StatusStyle("Có bệnh", R.color.color_danger, R.color.color_danger_light, R.drawable.ic_warning)
    "WARNING" -> StatusStyle("Cảnh báo", R.color.color_warning, R.color.color_warning_light, R.drawable.ic_warning)
    "HEALTHY" -> StatusStyle("Khỏe mạnh", R.color.color_healthy, R.color.color_healthy_light, R.drawable.ic_check_circle)
    else -> NEUTRAL
}

fun leafStyle(level: String): StatusStyle = when (level) {
    "diseased" -> StatusStyle("Nhiễm bệnh", R.color.color_danger, R.color.color_danger_light, R.drawable.ic_warning)
    "suspect" -> StatusStyle("Nghi ngờ", R.color.color_warning, R.color.color_warning_light, R.drawable.ic_warning)
    "healthy" -> StatusStyle("Khỏe mạnh", R.color.color_healthy, R.color.color_healthy_light, R.drawable.ic_check_circle)
    else -> NEUTRAL.copy(label = "Không rõ")
}

/** Nhan "Tomato_intermediate_fresh" cung chua "fresh" nen xet "rotten", "intermediate" truoc */
fun DetectionRecord.fruitStyle(): StatusStyle {
    val label = fruitLabel ?: ""
    return when {
        !isFruitKnown -> NEUTRAL.copy(label = "Không rõ")
        label.contains("rotten", ignoreCase = true) ->
            StatusStyle("Thối hỏng", R.color.color_danger, R.color.color_danger_light, R.drawable.ic_warning)
        label.contains("intermediate", ignoreCase = true) ->
            StatusStyle("Kém tươi", R.color.color_warning, R.color.color_warning_light, R.drawable.ic_warning)
        label.contains("fresh", ignoreCase = true) ->
            StatusStyle("Tươi tốt", R.color.color_healthy, R.color.color_healthy_light, R.drawable.ic_check_circle)
        else -> NEUTRAL.copy(label = fruitNameVi)
    }
}

/** Nhan nho bo tron: chu mau dam tren nen nhat cung tong mau */
fun TextView.applyBadge(style: StatusStyle) {
    text = style.label
    setTextColor(ContextCompat.getColor(context, style.color))
    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, style.container))
}

private fun pct(prob: Float) = (prob * 100).toInt()

/** Model qua < 50% thi coi nhu khong ro, giong LcdMessage / HistoryManager */
val DetectionRecord.isFruitKnown: Boolean
    get() = fruitConfidence >= 0.5f && fruitLabel != null && fruitLabel != "unknown" &&
        !fruitNameVi.contains("không rõ", ignoreCase = true)

/** Tom tat 1 dong ve la, vd "Bệnh 95%: Cà chua - mốc sương" */
val DetectionRecord.leafBrief: String
    get() = when (leafLevel) {
        "diseased" -> "Bệnh ${pct(leafDiseaseProb)}%: ${leafDiseaseNameVi.orEmpty()}"
        "suspect" -> "Nghi ngờ ${pct(leafDiseaseProb)}%: ${leafDiseaseNameVi.orEmpty()}"
        "healthy" -> "Khỏe mạnh ${pct(1 - leafDiseaseProb)}%"
        else -> when (leafStatus) {
            "too_dark" -> "Ảnh quá tối"
            "no_leaf" -> "Không thấy lá cà chua"
            "unknown" -> "Chưa có model lá"
            else -> leafStatus
        }
    }

/** Tom tat 1 dong ve qua, vd "Cà chua tươi (92%)" */
val DetectionRecord.fruitBrief: String
    get() = if (isFruitKnown) "$fruitNameVi (${pct(fruitConfidence)}%)" else "Không rõ"

/** "🚨 BÁO ĐỘNG ĐỎ: BỆNH HẠI CẢ LÁ & QUẢ" -> "Báo động đỏ: bệnh hại cả lá & quả" (icon/mau da co badge lo) */
val DetectionRecord.displayTitle: String
    get() {
        val vi = Locale.forLanguageTag("vi")
        return overallTitle.dropWhile { !it.isLetter() }.lowercase(vi).replaceFirstChar { it.titlecase(vi) }
    }
