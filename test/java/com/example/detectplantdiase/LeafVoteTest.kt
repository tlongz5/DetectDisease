package com.example.detectplantdiase

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kiem tra bo phieu va vung cat la (LeafVote) cho ket qua giong vote() / leaf_crop_box() trong ai/ */
class LeafVoteTest {

    @Test
    fun oneDiseasedLeafAmongHealthy_isOnlySuspect() {
        // 1 la benh 99% giua 4 la khoe: chua du 2 phieu -> "Nghi", hien xac suat cua la benh do
        assertEquals(Pair("suspect", 0.99f), LeafVote.vote(listOf(0.1f, 0.99f, 0.05f, 0.2f, 0.3f)))
    }

    @Test
    fun twoDiseasedLeaves_isDiseased_withSecondHighestProb() {
        assertEquals(Pair("diseased", 0.93f), LeafVote.vote(listOf(0.1f, 0.99f, 0.93f, 0.2f)))
    }

    @Test
    fun oneDiseasedOneSuspect_isSuspect() {
        assertEquals(Pair("suspect", 0.7f), LeafVote.vote(listOf(0.95f, 0.7f, 0.1f)))
    }

    @Test
    fun allHealthy_isHealthy() {
        // Xac suat dai dien = la "benh" thu 2 -> LCD hien "Khoe 80%"
        assertEquals(Pair("healthy", 0.2f), LeafVote.vote(listOf(0.3f, 0.2f, 0.1f)))
    }

    @Test
    fun singleLeaf_decidesAlone() {
        assertEquals(Pair("diseased", 0.95f), LeafVote.vote(listOf(0.95f)))
        assertEquals(Pair("suspect", 0.65f), LeafVote.vote(listOf(0.65f)))
    }

    @Test
    fun cropBox_matchesPython() {
        // Gia tri lay tu ai/detector.py leaf_crop_box()
        assertEquals(LeafBox(0, 0, 120, 120), LeafVote.leafCropBox(LeafBox(10, 20, 110, 60), 640, 480))
        assertEquals(LeafBox(0, 0, 240, 240), LeafVote.leafCropBox(LeafBox(0, 0, 50, 300), 320, 240))
        assertEquals(LeafBox(545, 385, 640, 480), LeafVote.leafCropBox(LeafBox(600, 400, 639, 479), 640, 480))
        assertEquals(LeafBox(20, 13, 170, 163), LeafVote.leafCropBox(LeafBox(33, 47, 158, 129), 1600, 1200))
        assertEquals(LeafBox(200, 0, 1400, 1200), LeafVote.leafCropBox(LeafBox(5, 5, 1595, 1195), 1600, 1200))
    }
}
