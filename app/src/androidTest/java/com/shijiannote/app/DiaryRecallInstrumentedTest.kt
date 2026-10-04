package com.shijiannote.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DiaryRecallInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Exercise actual persistent acknowledgements, rather than only the pure date predicates. */
    @Test fun calendarAndRecallAcknowledgementsRemainIndependentAndSurviveDisabling() {
        val prefs = DiaryRecallReminder.preferences(context)
        val keys = listOf("diaryRecallEnabled", "diaryRecallAvailableDate", "diaryRecallCalendarReadDate", "diaryRecallReadDate", "diaryRecallNotifiedDate")
        val saved = prefs.all.filterKeys { it in keys }
        val today = LocalDate.now()
        try {
            prefs.edit().apply { keys.forEach { remove(it) }; putString("diaryRecallAvailableDate", today.toString()) }.commit()
            assertTrue(DiaryRecallReminder.enabled(context))
            assertTrue(DiaryRecallReminder.calendarUnread(context, today))
            assertTrue(DiaryRecallReminder.recallUnread(context, today))

            DiaryRecallReminder.markCalendarRead(context, today)
            assertFalse(DiaryRecallReminder.calendarUnread(context, today))
            assertTrue(DiaryRecallReminder.recallUnread(context, today))
            assertEquals(today.toString(), appPreferences(context).getString("diaryRecallCalendarReadDate", null))

            DiaryRecallReminder.setEnabled(context, false)
            assertFalse(DiaryRecallReminder.enabled(context))
            assertFalse(DiaryRecallReminder.calendarUnread(context, today))
            assertFalse(DiaryRecallReminder.recallUnread(context, today))
            assertEquals(today.toString(), prefs.getString("diaryRecallAvailableDate", null))
            // Re-enable without starting a database refresh: this test isolates persisted read state.
            prefs.edit().putBoolean("diaryRecallEnabled", true).commit()
            assertFalse(DiaryRecallReminder.calendarUnread(context, today))
            assertTrue(DiaryRecallReminder.recallUnread(context, today))

            DiaryRecallReminder.markRecallRead(context, today)
            assertFalse(DiaryRecallReminder.recallUnread(context, today))
            assertEquals(today.toString(), appPreferences(context).getString("diaryRecallReadDate", null))
            assertEquals(today.toString(), prefs.getString("diaryRecallCalendarReadDate", null))

            // A new day's availability creates two new badges while keeping yesterday's reads.
            val tomorrow = today.plusDays(1)
            prefs.edit().putString("diaryRecallAvailableDate", tomorrow.toString()).commit()
            assertTrue(DiaryRecallReminder.calendarUnread(context, tomorrow))
            assertTrue(DiaryRecallReminder.recallUnread(context, tomorrow))
            DiaryRecallReminder.markRecallRead(context, tomorrow)
            assertTrue(DiaryRecallReminder.calendarUnread(context, tomorrow))
            assertFalse(DiaryRecallReminder.recallUnread(context, tomorrow))
        } finally {
            prefs.edit().apply {
                keys.forEach { remove(it) }
                saved.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }.commit()
        }
    }
}
