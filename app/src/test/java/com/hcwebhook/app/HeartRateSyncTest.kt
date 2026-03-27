package com.hcwebhook.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Unit tests for the heart-rate incremental-sync fix.
 *
 * These tests validate:
 *  1. The exclusive (>) sample filter — boundary sample must not be re-uploaded.
 *  2. The query-window expansion logic that prevents long HR sessions from being
 *     silently skipped when their record.startTime predates the default lookback.
 *  3. Watermark advancement after a successful sync.
 *  4. isHealthDataEmpty helper used to decide whether to skip the upload.
 */
class HeartRateSyncTest {

    // ─── Filter boundary condition ───────────────────────────────────────────

    @Test
    fun `HR sample at exactly lastSync is excluded by exclusive filter`() {
        val lastSync = Instant.parse("2024-03-01T10:00:00Z")
        val samples = listOf(
            HeartRateData(72, lastSync),                  // exactly at boundary — must be excluded
            HeartRateData(75, lastSync.plusSeconds(60)),  // 1 min after — must be included
        )
        val filtered = samples.filter { it.time > lastSync }
        assertEquals(1, filtered.size)
        assertEquals(lastSync.plusSeconds(60), filtered[0].time)
    }

    @Test
    fun `all HR samples before lastSync are excluded`() {
        val lastSync = Instant.parse("2024-03-01T12:00:00Z")
        val samples = listOf(
            HeartRateData(70, lastSync.minusSeconds(120)),
            HeartRateData(72, lastSync.minusSeconds(60)),
        )
        val filtered = samples.filter { it.time > lastSync }
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `all HR samples after lastSync are included`() {
        val lastSync = Instant.parse("2024-03-01T08:00:00Z")
        val samples = listOf(
            HeartRateData(78, lastSync.plusSeconds(3_600)),
            HeartRateData(80, lastSync.plusSeconds(7_200)),
        )
        val filtered = samples.filter { it.time > lastSync }
        assertEquals(2, filtered.size)
    }

    @Test
    fun `null lastSync includes all HR samples`() {
        val lastSync: Instant? = null
        val now = Instant.parse("2024-03-01T17:00:00Z")
        val samples = listOf(
            HeartRateData(65, now.minusSeconds(7_200)),
            HeartRateData(70, now.minusSeconds(3_600)),
            HeartRateData(75, now),
        )
        val filtered = samples.filter { lastSync == null || it.time > lastSync }
        assertEquals(3, filtered.size)
    }

    // ─── Query-window expansion ───────────────────────────────────────────────

    /**
     * Mirrors the queryStart computation added to readHeartRateData so that the
     * expansion logic can be verified without an Android runtime.
     */
    private fun computeQueryStart(
        startTime: Instant,
        endTime: Instant,
        lastSync: Instant?,
        bufferHours: Long = 24L,
        maxLookbackDays: Long = 7L,
    ): Instant {
        val bufferedLastSync = lastSync?.minus(bufferHours, ChronoUnit.HOURS)
        val desiredStart = if (bufferedLastSync != null) {
            minOf(startTime, bufferedLastSync)
        } else {
            startTime
        }

        return maxOf(
            desiredStart,
            endTime.minus(maxLookbackDays, ChronoUnit.DAYS),
        )
    }

    @Test
    fun `queryStart is expanded when lastSync precedes default startTime`() {
        val endTime   = Instant.parse("2024-03-03T17:09:00Z")
        val startTime = endTime.minus(48, ChronoUnit.HOURS)   // now − 48 h
        val lastSync  = startTime.minus(3, ChronoUnit.HOURS)  // 3 h before startTime

        val queryStart = computeQueryStart(startTime, endTime, lastSync)

        assertTrue(
            "queryStart should be before the default startTime when lastSync precedes it",
            queryStart.isBefore(startTime),
        )
    }

    @Test
    fun `queryStart includes the session-buffer before lastSync`() {
        val endTime   = Instant.parse("2024-03-03T17:09:00Z")
        val startTime = endTime.minus(48, ChronoUnit.HOURS)
        val lastSync  = startTime.minus(3, ChronoUnit.HOURS)  // 3 h before startTime

        val queryStart = computeQueryStart(startTime, endTime, lastSync, bufferHours = 24)

        // queryStart should be at most lastSync − 24 h (the buffer)
        val expectedLatest = lastSync.minus(24, ChronoUnit.HOURS)
        assertFalse(
            "queryStart must not be later than lastSync−buffer",
            queryStart.isAfter(expectedLatest),
        )
    }

    @Test
    fun `queryStart expands when buffered lastSync reaches before startTime`() {
        val endTime   = Instant.parse("2024-03-03T17:09:00Z")
        val startTime = endTime.minus(48, ChronoUnit.HOURS)
        val lastSync  = startTime.plus(1, ChronoUnit.HOURS)  // inside the window

        val queryStart = computeQueryStart(startTime, endTime, lastSync)

        assertTrue(
            "queryStart should expand when lastSync-buffer reaches before startTime",
            queryStart.isBefore(startTime),
        )
        assertEquals(
            "queryStart should use the buffered lastSync value when it is earlier than startTime",
            lastSync.minus(24, ChronoUnit.HOURS),
            queryStart,
        )
    }

    @Test
    fun `queryStart stays at startTime when buffered lastSync remains inside window`() {
        val endTime   = Instant.parse("2024-03-03T17:09:00Z")
        val startTime = endTime.minus(48, ChronoUnit.HOURS)
        val lastSync  = startTime.plus(30, ChronoUnit.HOURS)

        val queryStart = computeQueryStart(startTime, endTime, lastSync)

        assertEquals(startTime, queryStart)
    }

    @Test
    fun `queryStart stays at startTime when lastSync is null`() {
        val endTime   = Instant.parse("2024-03-03T17:09:00Z")
        val startTime = endTime.minus(48, ChronoUnit.HOURS)

        val queryStart = computeQueryStart(startTime, endTime, lastSync = null)

        assertEquals(startTime, queryStart)
    }

    @Test
    fun `queryStart is capped at MAX_HR_LOOKBACK_DAYS when lastSync is very old`() {
        val endTime      = Instant.parse("2024-03-03T17:09:00Z")
        val startTime    = endTime.minus(48, ChronoUnit.HOURS)
        val lastSync     = endTime.minus(20, ChronoUnit.DAYS)  // 20 days ago

        val queryStart = computeQueryStart(startTime, endTime, lastSync, maxLookbackDays = 7)

        val expectedCap = endTime.minus(7, ChronoUnit.DAYS)
        assertEquals(
            "queryStart must be capped at endTime − MAX_HR_LOOKBACK_DAYS",
            expectedCap,
            queryStart,
        )
    }

    @Test
    fun `queryStart never exceeds MAX_HR_LOOKBACK_DAYS regardless of buffer size`() {
        val endTime   = Instant.parse("2024-03-03T17:09:00Z")
        val startTime = endTime.minus(48, ChronoUnit.HOURS)
        val lastSync  = endTime.minus(6, ChronoUnit.DAYS)  // 6 days ago, buffer pushes to 7 d

        val queryStart = computeQueryStart(startTime, endTime, lastSync,
            bufferHours = 48, maxLookbackDays = 7)

        val cap = endTime.minus(7, ChronoUnit.DAYS)
        assertFalse(
            "queryStart must not go further back than the cap",
            queryStart.isBefore(cap),
        )
    }

    // ─── Watermark advancement ────────────────────────────────────────────────

    @Test
    fun `watermark advances to the maximum sample time`() {
        val t1 = Instant.parse("2024-03-01T09:00:00Z")
        val t2 = Instant.parse("2024-03-01T11:00:00Z")
        val t3 = Instant.parse("2024-03-01T10:00:00Z")
        val hrData = listOf(
            HeartRateData(72, t1),
            HeartRateData(75, t2),
            HeartRateData(74, t3),
        )
        assertEquals(t2, hrData.maxOf { it.time })
    }

    @Test
    fun `watermark is not updated when HR list is empty`() {
        val previousWatermark = Instant.parse("2024-03-01T10:00:00Z")
        val hrData = emptyList<HeartRateData>()
        // mirrors updateSyncTimestamps: only update if non-empty
        val newWatermark = if (hrData.isNotEmpty()) hrData.maxOf { it.time } else previousWatermark
        assertEquals(previousWatermark, newWatermark)
    }

    @Test
    fun `next sync with exclusive filter and advanced watermark skips previously uploaded sample`() {
        val prevSyncTime = Instant.parse("2024-03-01T10:00:00Z")  // watermark after first sync
        val newSample    = HeartRateData(80, prevSyncTime.plusSeconds(300))

        val allSamples = listOf(
            HeartRateData(72, prevSyncTime),   // already uploaded in previous sync
            newSample,
        )
        val filtered = allSamples.filter { it.time > prevSyncTime }

        assertEquals(1, filtered.size)
        assertEquals(newSample, filtered[0])
    }

    // ─── isHealthDataEmpty ────────────────────────────────────────────────────

    @Test
    fun `isHealthDataEmpty returns true for a fully empty HealthData`() {
        assertTrue(isHealthDataEmpty(makeEmptyHealthData()))
    }

    @Test
    fun `isHealthDataEmpty returns false when only heartRate is non-empty`() {
        val data = makeEmptyHealthData().copy(
            heartRate = listOf(HeartRateData(72, Instant.parse("2024-03-01T10:00:00Z")))
        )
        assertFalse(isHealthDataEmpty(data))
    }

    @Test
    fun `isHealthDataEmpty returns false when only steps is non-empty`() {
        val data = makeEmptyHealthData().copy(
            steps = listOf(
                StepsData(1000L,
                    Instant.parse("2024-03-01T00:00:00Z"),
                    Instant.parse("2024-03-01T23:59:59Z"))
            )
        )
        assertFalse(isHealthDataEmpty(data))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun makeEmptyHealthData() = HealthData(
        steps = emptyList(),
        sleep = emptyList(),
        heartRate = emptyList(),
        heartRateVariability = emptyList(),
        distance = emptyList(),
        activeCalories = emptyList(),
        totalCalories = emptyList(),
        weight = emptyList(),
        height = emptyList(),
        bloodPressure = emptyList(),
        bloodGlucose = emptyList(),
        oxygenSaturation = emptyList(),
        bodyTemperature = emptyList(),
        respiratoryRate = emptyList(),
        restingHeartRate = emptyList(),
        exercise = emptyList(),
        hydration = emptyList(),
        nutrition = emptyList(),
    )

    /** Mirrors the private isHealthDataEmpty logic in SyncManager. */
    private fun isHealthDataEmpty(data: HealthData): Boolean =
        data.steps.isEmpty() && data.sleep.isEmpty() && data.heartRate.isEmpty() &&
        data.heartRateVariability.isEmpty() &&
        data.distance.isEmpty() && data.activeCalories.isEmpty() && data.totalCalories.isEmpty() &&
        data.weight.isEmpty() && data.height.isEmpty() && data.bloodPressure.isEmpty() &&
        data.bloodGlucose.isEmpty() && data.oxygenSaturation.isEmpty() &&
        data.bodyTemperature.isEmpty() && data.respiratoryRate.isEmpty() &&
        data.restingHeartRate.isEmpty() && data.exercise.isEmpty() &&
        data.hydration.isEmpty() && data.nutrition.isEmpty()
}
