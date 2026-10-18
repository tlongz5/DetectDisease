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
    private val tomatoBacterialSpot = cls("Tomato_Bacterial_spot", "Dom vi khuan")

    private fun leaf(level: String, diseaseProb: Float, disease: LeafClass?, healthy: LeafClass?) =
        LeafResult("ok", level, diseaseProb, disease, 10, healthy)

    private fun fruit(label: String, conf: Float, nameVi: String = label, status: String = "") =
        Prediction(label, conf, status = status, nameVi = nameVi)

    @Test
    fun healthyLeaf_freshFruit() {
        val m = LcdMessage.from(leaf("healthy", 0.02f, tomatoLateBlight, tomatoHealthy), fruit("Tomato_fresh", 0.92f))
        assertEquals("La ca chua: Khoe 98%", m.leafLine)
        assertEquals("Qua ca chua: Tuoi 92%", m.fruitLine)
        assertEquals(LcdMessage.MIST_NORMAL, m.mistMode)
        assertEquals("La ca chua: Khoe 98%\nQua ca chua: Tuoi 92%\n1\n1760000000", m.body(1760000000))
        assertEquals("La ca chua: Khoe 98%\nQua ca chua: Tuoi 92%", m.lcdText())
    }

    @Test
    fun diseasedLeaf_rottenFruit() {
        val m = LcdMessage.from(leaf("diseased", 0.976f, tomatoLateBlight, tomatoHealthy), fruit("Tomato_rotten", 0.955f))
        assertEquals("La ca chua: Moc suong 97%", m.leafLine)
        assertEquals("Qua ca chua: Thoi hong 95%", m.fruitLine)
        assertEquals(LcdMessage.MIST_STOP, m.mistMode)
    }

    @Test
    fun suspectLeaf_longestLineStillFits() {
        val m = LcdMessage.from(leaf("suspect", 0.75f, tomatoBacterialSpot, tomatoHealthy),
            fruit("Tomato_intermediate_fresh", 0.8f))
        assertEquals("La ca chua: Nghi Dom vi khuan 75%", m.leafLine)
        assertEquals("Qua ca chua: Kem tuoi 80%", m.fruitLine)
        assertEquals(LcdMessage.MIST_STOP, m.mistMode)
    }

    @Test
    fun noTomatoFruit() {
        val m = LcdMessage.from(leaf("healthy", 0.1f, tomatoLateBlight, tomatoHealthy),
            fruit("unknown", 0.07f, status = FreshnessClassifier.NO_TOMATO))
        assertEquals("Khong thay qua ca chua", m.fruitLine)
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
        assertEquals(LcdMessage.MIST_KEEP, m.mistMode)
    }

    @Test
    fun noLeaf_fruitStillShown() {
        val m = LcdMessage.from(LeafResult("no_leaf", "none", 0f, null, 1), fruit("Tomato_rotten", 0.88f))
        assertEquals("Khong thay la ca chua", m.leafLine)
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
        assertEquals(LcdMessage.MIST_KEEP, m.mistMode)
    }

    @Test
    fun unmappedFruitLabel_fallsBackToAsciiName() {
        val m = LcdMessage.from(null, fruit("Tomato_overripe", 0.9f, nameVi = "Cà chua chín nẫu"))
        assertEquals("Ca chua chin nau 90%", m.fruitLine)
    }

    @Test
    fun mistMode_followsLeafHealth() {
        val spiderMites = cls("Tomato_Spider_mites", "Nhen do")
        val mosaic = cls("Tomato_Mosaic", "Kham la")
        fun mode(level: String, p: Float, d: LeafClass) = LcdMessage.from(leaf(level, p, d, tomatoHealthy), null).mistMode

        assertEquals(LcdMessage.MIST_BOOST, mode("diseased", 0.95f, spiderMites))  // Nhen do so am
        assertEquals(LcdMessage.MIST_STOP, mode("suspect", 0.7f, spiderMites))     // Chua chac: dung
        assertEquals(LcdMessage.MIST_NORMAL, mode("diseased", 0.95f, mosaic))      // Virus: phun binh thuong
        assertEquals(LcdMessage.MIST_STOP, mode("diseased", 0.95f, tomatoLateBlight))
        assertEquals(LcdMessage.MIST_NORMAL, mode("healthy", 0.1f, tomatoLateBlight))
        assertEquals(LcdMessage.MIST_KEEP, LcdMessage.from(LeafResult("no_leaf", "none", 0f, null, 1), null).mistMode)
    }

    /** Ten lop nhen do / virus trong LcdMessage phai dung ten trong leaf_labels.json, doi ten model thi test bao */
    @Test
    fun mistClassIds_existInLabels() {
        val json = File("main/assets/leaf_labels.json").readText()
        for (id in listOf("Tomato_Spider_mites", "Tomato_Mosaic", "Tomato_Yellow_Curl")) {
            assertTrue("leaf_labels.json khong co lop $id", json.contains("\"id\": \"$id\""))
        }
    }

    /** Moi lop that trong leaf_labels.json: co ten cay, chi ASCII, khong qua MAX_LINE ky tu */
    @Test
    fun everyLeafClass_fitsAndNamesPlant() {
        val json = File("main/assets/leaf_labels.json").readText()
        val found = Regex("\"id\":\\s*\"([^\"]+)\"[^}]*?\"lcd_vi\":\\s*\"([^\"]+)\"[^}]*?\"is_healthy\":\\s*(true|false)")
            .findAll(json).map { cls(it.groupValues[1], it.groupValues[2], it.groupValues[3] == "true") }.toList()
        assertEquals(10, found.size)
        assertTrue("Con lop khong phai ca chua", found.all { it.id.startsWith("Tomato_") })
        val healthy = found.filter { it.healthy }
        for (d in found.filter { !it.healthy }) {
            for ((level, p) in listOf("suspect" to 0.899f, "diseased" to 0.999f)) {
                val line = LcdMessage.from(leaf(level, p, d, healthy.first()), null).leafLine
                checkLine(line)
                assertTrue("Thieu ten cay: $line", line.startsWith("La ca chua: "))
            }
        }
        for (h in healthy) {
            val line = LcdMessage.from(leaf("healthy", 0f, found.first { !it.healthy }, h), null).leafLine
            checkLine(line)
            assertEquals("La ca chua: Khoe 100%", line)
        }
    }

    /** Moi nhan ca chua ma model qua co the tra ve deu co ten tieng Viet */
    @Test
    fun everyFruitLabel_fitsAndIsMapped() {
        val json = File("main/assets/labels.json").readText()
        val labels = Regex("\"(Tomato_[a-z_]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        assertEquals(3, labels.size)
        for (label in labels) {
            val line = LcdMessage.from(null, fruit(label, 1f)).fruitLine
            checkLine(line)
            assertTrue("Chua co ten tieng Viet: $line", Regex("Qua ca chua: (Tuoi|Kem tuoi|Thoi hong) 100%").matches(line))
        }
    }

    private fun checkLine(line: String) {
        assertTrue("Qua ${LcdMessage.MAX_LINE} ky tu: $line", line.length <= LcdMessage.MAX_LINE)
        assertTrue("Co ky tu ngoai ASCII: $line", line.all { it in ' '..'~' })
    }
}
