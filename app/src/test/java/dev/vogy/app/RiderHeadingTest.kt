package dev.vogy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderHeadingTest {

    private val compass = 200f
    private val course = 90f

    @Test
    fun stoppedFollowsThePhoneCompass() {
        val heading = RiderHeading()
        assertEquals(compass, heading.choose(compass, course, speedMps = 0.3f, fixAgeMs = 500), 0f)
        assertFalse(heading.usingCourse)
    }

    @Test
    fun ridingFollowsTheBikesCourse() {
        val heading = RiderHeading()
        assertEquals(course, heading.choose(compass, course, speedMps = 8f, fixAgeMs = 500), 0f)
        assertTrue(heading.usingCourse)
    }

    @Test
    fun slowingDownKeepsTheCourseUntilAlmostStopped() {
        val heading = RiderHeading()
        heading.choose(compass, course, speedMps = 8f, fixAgeMs = 500)
        // Between the two thresholds: stays on the course while slowing…
        assertEquals(course, heading.choose(compass, course, speedMps = 2f, fixAgeMs = 500), 0f)
        // …and a fresh start at the same speed doesn't switch to it yet
        assertEquals(compass, RiderHeading().choose(compass, course, speedMps = 2f, fixAgeMs = 500), 0f)
        // Nearly stopped: back to the compass
        assertEquals(compass, heading.choose(compass, course, speedMps = 1f, fixAgeMs = 500), 0f)
    }

    @Test
    fun staleOrIncompleteFixesFallBackToTheCompass() {
        assertEquals(compass, RiderHeading().choose(compass, course, speedMps = 8f, fixAgeMs = 9_000), 0f)
        assertEquals(compass, RiderHeading().choose(compass, course = null, speedMps = 8f, fixAgeMs = 500), 0f)
        assertEquals(compass, RiderHeading().choose(compass, course, speedMps = null, fixAgeMs = 500), 0f)
    }
}
