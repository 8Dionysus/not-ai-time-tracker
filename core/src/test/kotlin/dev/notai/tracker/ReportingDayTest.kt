package dev.notai.tracker

import java.time.*
import org.junit.Test
import kotlin.test.*

class ReportingDayTest {
    @Test fun dstBoundariesResolveByInstantAndKeepStoredTimestamps() {
        val p = ReportingDayPolicy("Europe/Berlin",240)
        assertEquals(23*3_600_000L,p.bounds(LocalDate.parse("2026-03-28"),1).let { it.second-it.first })
        assertEquals(25*3_600_000L,p.bounds(LocalDate.parse("2026-10-24"),1).let { it.second-it.first })
        val gap = ReportingDayPolicy("Europe/Berlin",150)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(),gap.boundary(LocalDate.parse("2026-03-29")))
        val fold = gap.boundary(LocalDate.parse("2026-10-25"))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),fold)
        assertEquals(LocalDate.parse("2026-10-25"),gap.dateAt(fold+3_600_000))
        assertEquals(LocalDate.parse("2026-10-24"),gap.dateAt(fold-1))
        assertNotEquals(ReportingDayPolicy("UTC").dateAt(0),ReportingDayPolicy("America/New_York").dateAt(0))
    }
    @Test fun zeroRoundedContributionDoesNotCreateARecordedDayAndIndexAgrees() {
        val end = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
        val session = Session("rounding",SessionStatus.STOPPED)
        val segment = Segment("rounding-segment",session.id,end-1,end+999,1)
        val d = Dataset(sessions = listOf(session),segments = listOf(segment))
        val now = Moment(end+2000,1,"boot")
        val r = Reports.report(d,now,end-1,end+999)
        assertEquals(1,r.recordedMs);assertEquals(setOf(LocalDate.parse("2026-10-16")),r.recordedDays)
        assertEquals(r,CheckedState.validate(d).report(now,end-1,end+999,Filter(),"UTC"))
        assertTrue(Reports.report(d,now,end-1,end).recordedDays.isEmpty())
    }
}
