package com.example.detectplantdiase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kiem tra 2 dong chu gui len LCD 1602 cua Nano (LcdMessage.from) */
class LcdMessageTest {

    private fun cls(id: String, lcdVi: String, healthy: Boolean = false) = LeafClass(id, "X", id, healthy, lcdVi)

    private val tomatoHealthy = cls("Tomato_healthy", "Ca chua khoe", healthy = true)
    private val tomatoLateBlight = cls("Tomato_Late_blight", "Moc suong")
    private val potatoLateBlight = cls("Potato_Late_blight", "Moc suong")
    private val pepperBacterialSpot = cls("Pepper_Bacterial_spot", "Dom vi khuan")

    private fun leaf(level: String, diseaseProb: Float, disease: LeafClass?, healthy: LeafClass?) =
        LeafResult("ok", level, diseaseProb, disease, 10, healthy)

    private fun fruit(label: String, conf: Float, nameVi: String = label) = Prediction(label, conf, nameVi = nameVi)

    @Test
    fun healthyLeaf_freshFruit() {
        val m = LcdMessage.from(leaf("healthy", 0.02f, tomatoLateBlight, tomatoHealthy), fruit("Tomato_fresh", 0.92f))
        assertEquals("La ca chua: Khoe 98%", m.leafLine)
        assertEquals("Qua ca chua: Tuoi 92%", m.fruitLine)
        assertEquals(0, m.leafLevel)
        assertEquals("La ca chua: Khoe 98%\nQua ca chua: Tuoi 92%\n0", m.body())
        assertEquals("La ca chua: Khoe 98%\nQua ca chua: Tuoi 92%", m.lcdText())
    }

    @Test
    fun diseasedLeaf_namesPlantOfThatDisease() {
        // La khoe kha nang nhat la ca chua, nhung benh la cua khoai tay: dong LCD phai noi khoai tay
        val m = LcdMessage.from(leaf("diseased", 0.976f, potatoLateBlight, tomatoHealthy), fruit("Potato_rotten", 0.955f))
        assertEquals("La khoai tay: Moc suong 97%", m.leafLine)
        assertEquals("Cu khoai tay: Thoi hong 95%", m.fruitLine)
        assertEquals(2, m.leafLevel)
    }

    @Test
    fun suspectLeaf_longestLineStillFits() {
        val m = LcdMessage.from(leaf("suspect", 0.75f, pepperBacterialSpot, tomatoHealthy),
            fruit("Bellpepper_intermediate_fresh", 0.8f))
        assertEquals("La ot chuong: Nghi Dom vi khuan 75%", m.leafLine)
        assertEquals("Qua ot chuong: Kem tuoi 80%", m.fruitLine)
        assertEquals(1, m.leafLevel)
    }

    @Test
    fun percentRoundsDownLikePredictPy() {
        // 89.7% la muc "Nghi" (duoi 90%), hien "90%" se de nham voi muc "Benh"
        val m = LcdMessage.from(leaf("suspect", 0.897f, tomatoLateBlight, tomatoHealthy), null)
        assertEquals("La ca chua: Nghi Moc suong 89%", m.leafLine)
    }

    @Test
    fun darkImage_noFruitGuess() {
        val dark = LeafResult("too_dark", "none", 0f, null, 0)
        val m = LcdMessage.from(dark, fruit("Tomato_fresh", 0.99f))
        assertEquals("Anh qua toi", m.leafLine)
        assertEquals("Khong ro qua", m.fruitLine)
        assertEquals(0, m.leafLevel)
    }

    @Test
    fun noLeaf_fruitStillShown() {
        val m = LcdMessage.from(LeafResult("no_leaf", "none", 0f, null, 1), fruit("Tomato_rotten", 0.88f))
        assertEquals("Khong thay la", m.leafLine)
        assertEquals("Qua ca chua: Thoi hong 88%", m.fruitLine)
    }

    @Test
    fun unsureFruit() {
        val m = LcdMessage.from(leaf("healthy", 0.1f, tomatoLateBlight, tomatoHealthy), fruit("unknown", 0.42f))
        assertEquals("Khong ro qua", m.fruitLine)
    }

    @Test
    fun missingModels() {
        val m = LcdMessage.from(null, null)
        assertEquals("Thieu model la", m.leafLine)
        assertEquals("Thieu model qua", m.fruitLine)
        assertEquals(0, m.leafLevel)
    }

    @Test
    fun unmappedFruitLabel_fallsBackToAsciiName() {
        val m = LcdMessage.from(null, fruit("Carrot_fresh", 0.9f, nameVi = "Cà rốt tươi đẹp"))
        assertEquals("Ca rot tuoi dep 90%", m.fruitLine)
    }

    /** Moi lop that trong leaf_labels.json: co ten cay, chi ASCII, khong qua MAX_LINE ky tu */
    @Test
    fun everyLeafClass_fitsAndNamesPlant() {
        val json = File("main/assets/leaf_labels.json").readText()
        val found = Regex("\"id\":\\s*\"([^\"]+)\"[^}]*?\"lcd_vi\":\\s*\"([^\"]+)\"[^}]*?\"is_healthy\":\\s*(true|false)")
            .findAll(json).map { cls(it.groupValues[1], it.groupValues[2], it.groupValues[3] == "true") }.toList()
        assertEquals(15, found.size)
        val healthy = found.filter { it.healthy }
        for (d in found.filter { !it.healthy }) {
            for ((level, p) in listOf("suspect" to 0.899f, "diseased" to 0.999f)) {
                val line = LcdMessage.from(leaf(level, p, d, healthy.first()), null).leafLine
                checkLine(line)
                assertTrue("Thieu ten cay: $line", !line.startsWith("La:"))
            }
        }
        for (h in healthy) {
            val line = LcdMessage.from(leaf("healthy", 0f, found.first { !it.healthy }, h), null).leafLine
            checkLine(line)
            assertTrue("Sai dinh dang: $line", Regex("La [a-z ]+: Khoe 100%").matches(line))
        }
    }

    /** Moi nhan ca chua / khoai tay / ot chuong ma model qua co the tra ve deu co ten tieng Viet */
    @Test
    fun everyFruitLabel_fitsAndIsMapped() {
        val json = File("main/assets/labels.json").readText()
        val labels = Regex("\"((?:Tomato|Potato|Bellpepper)_[a-z_]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        assertEquals(9, labels.size)
        for (label in labels) {
            val line = LcdMessage.from(null, fruit(label, 1f)).fruitLine
            checkLine(line)
            assertTrue("Chua co ten tieng Viet: $line",
                Regex("(Qua ca chua|Qua ot chuong|Cu khoai tay): (Tuoi|Kem tuoi|Thoi hong) 100%").matches(line))
        }
    }

    private fun checkLine(line: String) {
        assertTrue("Qua ${LcdMessage.MAX_LINE} ky tu: $line", line.length <= LcdMessage.MAX_LINE)
        assertTrue("Co ky tu ngoai ASCII: $line", line.all { it in ' '..'~' })
    }
}
