package com.example.detectplantdiase

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Tieng "bip" bao ESP32-CAM vua chup xong 1 anh. Phat theo am luong thong bao:
 * dien thoai de im lang / rung thi khong keu.
 */
object CaptureSound {
    private const val BEEP_MS = 250

    /** Keu neu nguoi dung dang bat am bao trong Cai dat */
    fun playIfEnabled(context: Context) {
        if (ConfigManager.isCaptureSoundEnabled(context)) play()
    }

    fun play() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            // Giai phong sau khi keu xong, khong giu loa
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, BEEP_MS + 200L)
        } catch (e: RuntimeException) {
            e.printStackTrace()  // May khong tao duoc bo phat am (dang goi dien...): bo qua, khong lam hong lan chup
        }
    }
}
