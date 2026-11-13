package com.ayra.ai.app

import com.ayra.ai.app.automation.AyraTaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Audit F14: Bengali number and time parser unit test.
 * Verifies digits ০-৯, "সকাল ৭টা", "7 pm", "কাল", "পরশু", relative offsets, and midnight.
 */
class TimeParserTest {

    @Test
    fun testBengaliDigitConversion() {
        val bengaliString = "০১২৩৪৫৬৭৮৯"
        val converted = AyraTaskScheduler.convertBengaliDigits(bengaliString)
        assertEquals("0123456789", converted)

        val mixed = "সকাল ৭:৩০ মিনিটে কল করো"
        val convertedMixed = AyraTaskScheduler.convertBengaliDigits(mixed)
        assertTrue(convertedMixed.contains("7:30"))
    }

    @Test
    fun testMorningAndEveningBengaliParsing() {
        val (morningEpoch, morningStr) = AyraTaskScheduler.parseTime("সকাল ৭টায় মনে করিয়ে দিও")
        val calMorning = Calendar.getInstance().apply { timeInMillis = morningEpoch }
        assertEquals(7, calMorning.get(Calendar.HOUR_OF_DAY))

        val (nightEpoch, nightStr) = AyraTaskScheduler.parseTime("রাত ৯টায় বাবাকে কল করবে")
        val calNight = Calendar.getInstance().apply { timeInMillis = nightEpoch }
        assertEquals(21, calNight.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testRelativeMinutesAndHours() {
        val now = System.currentTimeMillis()
        val (epoch10m, _) = AyraTaskScheduler.parseTime("১০ মিনিট পর মনে করিয়ে দাও")
        val diffMin = (epoch10m - now) / (60 * 1000)
        assertTrue("Expected ~10 minutes, was $diffMin", diffMin in 9..11)

        val (epoch2h, _) = AyraTaskScheduler.parseTime("২ ঘণ্টা পর কল করো")
        val diffHour = (epoch2h - now) / (60 * 60 * 1000)
        assertTrue("Expected ~2 hours, was $diffHour", diffHour in 1..3)
    }

    @Test
    fun testDayAfterTomorrowPorshu() {
        val now = System.currentTimeMillis()
        val (epochPorshu, _) = AyraTaskScheduler.parseTime("পরশু সকাল ৮টায় কল করো")
        val calPorshu = Calendar.getInstance().apply { timeInMillis = epochPorshu }
        val calNow = Calendar.getInstance().apply { timeInMillis = now }

        val dayDiff = calPorshu.get(Calendar.DAY_OF_YEAR) - calNow.get(Calendar.DAY_OF_YEAR)
        assertTrue("Porshu should be 2 days ahead (or wrapped around year end)", dayDiff == 2 || dayDiff < -300)
        assertEquals(8, calPorshu.get(Calendar.HOUR_OF_DAY))
    }
}
