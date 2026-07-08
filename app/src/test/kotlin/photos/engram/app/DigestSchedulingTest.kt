package photos.engram.app

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import photos.engram.app.work.DigestWorker
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DigestSchedulingTest {
    private fun at(
        hour: Int,
        minute: Int,
    ): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.JULY, 8, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun laterTodaySchedulesSameDay() {
        val now = at(9, 0)
        val delay = DigestWorker.delayToHour(20, now)
        assertEquals(11 * 60 * 60 * 1000L, delay)
    }

    @Test
    fun pastHourRollsToTomorrow() {
        val now = at(21, 30)
        val delay = DigestWorker.delayToHour(20, now)
        // 22h30m until 20:00 next day
        assertEquals((22 * 60 + 30) * 60 * 1000L, delay)
    }

    @Test
    fun exactlyNowRollsToTomorrow() {
        val now = at(20, 0)
        val delay = DigestWorker.delayToHour(20, now)
        assertEquals(24 * 60 * 60 * 1000L, delay)
    }
}
