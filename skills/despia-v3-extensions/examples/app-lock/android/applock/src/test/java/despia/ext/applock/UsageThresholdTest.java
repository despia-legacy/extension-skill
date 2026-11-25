package despia.ext.applock;

import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
import static despia.ext.applock.UsageThreshold.*;

public final class UsageThresholdTest {
    private static Event e(long time, String app, String activity, int type) { return new Event(time, app, activity, type); }
    @Test public void countsSelectedForegroundTimeInsideTheWindowOnly() {
        List<Event> events = Arrays.asList(
            e(0, "a.app", "Main", RESUMED), e(100, "a.app", "Main", PAUSED),      // 50 of it after from=50
            e(200, "b.app", "Main", RESUMED), e(300, "b.app", "Main", PAUSED),    // not selected
            e(400, "a.app", "Main", RESUMED), e(450, "a.app", "Detail", RESUMED), // two activities: one span
            e(500, "a.app", "Main", STOPPED), e(600, "a.app", "Detail", PAUSED),  // 400..600
            e(900, "a.app", "Main", RESUMED));                                   // still open at to=1000
        assertEquals(50 + 200 + 100, foregroundMs(events, "a.app"::equals, 50, 1000));
        assertEquals(0, foregroundMs(events, "c.app"::equals, 0, 1000));
    }
    @Test public void windowStartFollowsTheStartDayAcrossMidnight() {
        Set<Integer> every = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        assertEquals(Long.valueOf(Instant.parse("2026-11-23T22:00:00Z").toEpochMilli()), intervalStart(ZoneOffset.UTC, every, 22, 0, 6, 0, Instant.parse("2026-11-24T02:00:00Z")));
        assertEquals(Long.valueOf(Instant.parse("2026-11-24T22:00:00Z").toEpochMilli()), intervalStart(ZoneOffset.UTC, every, 22, 0, 6, 0, Instant.parse("2026-11-24T23:00:00Z")));
        assertNull(intervalStart(ZoneOffset.UTC, every, 22, 0, 6, 0, Instant.parse("2026-11-24T12:00:00Z")));
    }
    @Test public void pastActivityCountsFromTheWindowStartOnlyWhenAsked() {
        assertEquals(100, countFrom(100, 500, true));
        assertEquals(500, countFrom(100, 500, false));
        assertEquals(100, countFrom(100, 50, false));
    }
}
