package com.roadw2k.minimallauncher

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyUsageTest {
    private fun event(time: Long, transition: UsageTransition, id: String = "a", pkg: String = "app") =
        DailyUsageEvent(time, pkg, id, transition)

    @Test fun overlappingActivitiesAndDuplicateResumesCountOnce() {
        val events = listOf(
            event(10, UsageTransition.RESUME), event(15, UsageTransition.RESUME),
            event(20, UsageTransition.RESUME, "b"), event(30, UsageTransition.PAUSE),
            event(35, UsageTransition.STOP), event(40, UsageTransition.PAUSE, "b")
        )
        assertEquals(mapOf("app" to 30L), aggregateDailyUsage(events, 0, 100))
    }

    @Test fun midnightClipsPriorSessionAndIncludesOngoingSession() {
        assertEquals(mapOf("app" to 50L), aggregateDailyUsage(listOf(
            event(10, UsageTransition.RESUME)
        ), 50, 100))
        assertEquals(mapOf("app" to 20L), aggregateDailyUsage(listOf(
            event(10, UsageTransition.RESUME), event(70, UsageTransition.PAUSE)
        ), 50, 100))
    }

    @Test fun completedPriorSessionsAndFutureEventsDoNotCount() {
        assertEquals(emptyMap<String, Long>(), aggregateDailyUsage(listOf(
            event(10, UsageTransition.RESUME), event(20, UsageTransition.PAUSE),
            event(110, UsageTransition.RESUME)
        ), 50, 100))
    }

    @Test fun screenOffOrShutdownClosesAllPackagesUntilNextResume() {
        val events = listOf(
            event(0, UsageTransition.RESUME), event(5, UsageTransition.RESUME, pkg = "other"),
            event(20, UsageTransition.END_ALL), event(30, UsageTransition.RESUME),
            event(40, UsageTransition.END_ALL), event(50, UsageTransition.PAUSE)
        )
        assertEquals(mapOf("app" to 15L, "other" to 15L), aggregateDailyUsage(events, 0, 100))
    }

    @Test fun sortsEventsAndKeepsSeparateSessions() {
        assertEquals(mapOf("app" to 30L), aggregateDailyUsage(listOf(
            event(40, UsageTransition.PAUSE), event(0, UsageTransition.RESUME),
            event(30, UsageTransition.RESUME), event(20, UsageTransition.PAUSE)
        ), 0, 100))
        assertEquals(emptyMap<String, Long>(), aggregateDailyUsage(emptyList(), 100, 100))
    }

    @Test fun missingPauseDoesNotCarryOldAppIntoToday() {
        val totals = aggregateDailyUsage(listOf(
            event(10, UsageTransition.RESUME, pkg = "chrome"),
            event(20, UsageTransition.RESUME, pkg = "launcher")
        ), 50, 100)
        assertEquals(mapOf("launcher" to 50L), totals)
    }

    @Test fun appSwitchClosesOldSessionWithoutPause() {
        assertEquals(mapOf("app" to 10L, "other" to 20L), aggregateDailyUsage(listOf(
            event(10, UsageTransition.RESUME),
            event(20, UsageTransition.RESUME, pkg = "other"),
            event(40, UsageTransition.PAUSE, pkg = "other")
        ), 0, 100))
    }

    @Test fun sortingUsesDurationThenLabelThenPackage() {
        val apps = listOf(
            DailyUsageApp("z", "Beta", 10), DailyUsageApp("b", "Alpha", 10),
            DailyUsageApp("a", "Alpha", 10), DailyUsageApp("top", "Zed", 20)
        )
        assertEquals(listOf("top", "a", "b", "z"), sortDailyUsage(apps).map { it.packageName })
    }

    @Test fun durationsAreNonnegativeAndReadable() {
        assertEquals("<1m", formatUsageDuration(-1))
        assertEquals("<1m", formatUsageDuration(0))
        assertEquals("<1m", formatUsageDuration(59_999))
        assertEquals("<1m", formatUsageDuration(59_999, includeHours = true))
        assertEquals("0h 1m", formatUsageDuration(60_000, includeHours = true))
        assertEquals("2h 15m", formatUsageDuration(8_100_000, includeHours = true))
        assertEquals("1m", formatUsageDuration(60_000))
        assertEquals("1h 1m", formatUsageDuration(3_660_000))
        assertEquals("25h 0m", formatUsageDuration(90_000_000))
    }

    @Test fun localMidnightUsesCalendarDaysAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        val spring = usageDayBounds(Instant.parse("2026-03-08T16:00:00Z").toEpochMilli(), zone)
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), spring.first)
        assertEquals(23 * 3_600_000L, spring.second - spring.first)
        val autumn = usageDayBounds(Instant.parse("2026-11-01T16:00:00Z").toEpochMilli(), zone)
        assertEquals(25 * 3_600_000L, autumn.second - autumn.first)
    }

    @Test fun refreshTargetsMidnightWithinOneSecond() {
        val zone = ZoneId.of("Asia/Kolkata")
        val midnight = Instant.parse("2026-11-06T18:30:00Z").toEpochMilli()
        assertEquals(500L, usageRefreshDelay(midnight - 500, zone))
        assertEquals(15_000L, usageRefreshDelay(midnight - 60_000, zone))
        assertEquals(midnight, usageDayBounds(midnight, zone).first)
    }
}
