package com.fundocareer.app

import com.fundocareer.app.core.jobalerts.JobAlertStatus
import com.fundocareer.app.core.jobalerts.JobAlertRunEntity
import com.fundocareer.app.core.jobalerts.JobAlertPreferenceEntity
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun jobAlertStatus_constants_areCorrect() {
        assertEquals("SUCCESS_NO_NEW_JOBS", JobAlertStatus.SUCCESS_NO_NEW_JOBS)
        assertEquals("SUCCESS_EMAIL_SENT", JobAlertStatus.SUCCESS_EMAIL_SENT)
        assertEquals("FAILED", JobAlertStatus.FAILED)
        assertEquals("SKIPPED", JobAlertStatus.SKIPPED)
        assertEquals("PAUSED", JobAlertStatus.PAUSED)
    }

    @Test
    fun jobAlertRunEntity_createsCorrectly() {
        val run = JobAlertRunEntity(
            id = "test-id",
            userEmail = "user@test.com",
            preferenceSetId = "pref-id",
            startedAt = 1000L,
            completedAt = 2000L,
            status = "SUCCESS_EMAIL_SENT",
            jobsFound = 10,
            jobsNew = 3,
            errorMessage = null,
            deviceId = "device-a",
        )
        assertEquals("test-id", run.id)
        assertEquals("SUCCESS_EMAIL_SENT", run.status)
        assertEquals(3, run.jobsNew)
        assertEquals(10, run.jobsFound)
        assertEquals("device-a", run.deviceId)
    }

    @Test
    fun jobAlertPreferenceEntity_defaultsAreSane() {
        val pref = JobAlertPreferenceEntity(
            id = "pref-1",
            userEmail = "user@test.com",
            role = "Android Developer",
            location = "Remote",
            experience = "Mid-Level",
        )
        assertEquals("Android Developer", pref.role)
        assertEquals("Remote", pref.location)
        assertEquals("pref-1", pref.id)
        assertNull(pref.company)
        assertNull(pref.salaryMin)
        assertEquals(15L, pref.intervalValue)
        assertEquals("minutes", pref.intervalUnit)
        assertEquals("html", pref.reportFormat)
        assertTrue(pref.active)
    }

    @Test
    fun jobAlertPreferenceEntity_withOptionalFields() {
        val pref = JobAlertPreferenceEntity(
            id = "pref-2",
            userEmail = "user@test.com",
            role = "Senior Dev",
            location = "NYC",
            experience = "Senior",
            remote = true,
            salaryMin = 120000L,
            salaryMax = 180000L,
            skills = "Kotlin, Compose",
            company = "Google",
            intervalValue = 30L,
            intervalUnit = "minutes",
            reportFormat = "html",
        )
        assertEquals(true, pref.remote)
        assertEquals(120000L, pref.salaryMin)
        assertEquals("Kotlin, Compose", pref.skills)
        assertEquals("Google", pref.company)
    }

    @Test
    fun jobAlertPreferenceEntity_inactive_preference() {
        val pref = JobAlertPreferenceEntity(
            id = "pref-3",
            userEmail = "user@test.com",
            role = "Data Scientist",
            location = "Remote",
            experience = "Entry-Level",
            active = false,
            pausedDueToLogout = true,
        )
        assertFalse(pref.active)
        assertTrue(pref.pausedDueToLogout)
    }
}
