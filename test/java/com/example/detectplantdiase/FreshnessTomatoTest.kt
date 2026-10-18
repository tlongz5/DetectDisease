package com.example.detectplantdiase

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

/** Kiem tra FreshnessClassifier.tomatoScores: chi lay nhan ca chua, tinh tong P(ca chua) tren ca 30 nhan */
class FreshnessTomatoTest {

    private val labels = mapOf(0 to "Bellpepper_fresh", 1 to "Tomato_fresh", 2 to "Tomato_intermediate_fresh",
        3 to "Tomato_rotten", 4 to "ripe_apple")

    /** Logit = ln(xac suat) -> softmax tra lai dung xac suat do */
    private fun logits(vararg p: Double) = FloatArray(p.size) { ln(p[it]).toFloat() }

    @Test
    fun clearTomato() {
        val (best, conf, tomato) = FreshnessClassifier.tomatoScores(logits(0.02, 0.81, 0.05, 0.04, 0.08), labels)
        assertEquals(1, best)
        assertEquals(0.81f / 0.90f, conf, 1e-4f)
        assertEquals(0.90f, tomato, 1e-4f)
    }

    @Test
    fun noTomato_lowTomatoProbEvenIfTomatoWinsAmongTomatoLabels() {
        // Anh chi co la: model nghieng ve tao; trong 3 nhan ca chua "rotten" cao nhat nhung tong P(ca chua) chi 0.15
        val (best, conf, tomato) = FreshnessClassifier.tomatoScores(logits(0.05, 0.03, 0.02, 0.10, 0.80), labels)
        assertEquals(3, best)
        assertEquals(0.10f / 0.15f, conf, 1e-4f)
        assertEquals(0.15f, tomato, 1e-4f)
        assert(tomato < FreshnessClassifier.MIN_TOMATO_PROB)
    }
}
