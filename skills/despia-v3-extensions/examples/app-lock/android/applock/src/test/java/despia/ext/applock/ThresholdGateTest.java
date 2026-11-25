package despia.ext.applock;

import org.json.*;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

/** A schedule with threshold_minutes activates its group only once the threshold is reached in the open window,
 *  as the iOS Device Activity threshold event does; a mark from an earlier window does not count. */
public final class ThresholdGateTest {
    private static JSONObject state(Long reachedInterval) throws JSONException {
        JSONObject schedule = new JSONObject().put("id", "s").put("group_id", "g").put("enabled", true).put("weekdays", new JSONArray("[1,2,3,4,5,6,7]"))
            .put("time_zone", "UTC").put("start_hour", 9).put("start_minute", 0).put("end_hour", 17).put("end_minute", 0).put("threshold_minutes", 30);
        if (reachedInterval != null) schedule.put("threshold_interval_ms", reachedInterval);
        JSONObject group = new JSONObject().put("id", "g").put("name", "Focus").put("manual_enabled", false);
        return new JSONObject().put("groups", new JSONArray().put(group)).put("schedules", new JSONArray().put(schedule)).put("events", new JSONArray());
    }
    @Test public void thresholdScheduleActivatesItsGroupOnlyAfterTheThresholdInTheOpenWindow() throws Exception {
        Instant now = Instant.parse("2026-11-24T10:00:00Z"); long windowStart = Instant.parse("2026-11-24T09:00:00Z").toEpochMilli();
        assertTrue("not reached: inactive", LockState.activeGroups(state(null), now).isEmpty());
        assertEquals("reached in this window: active", 1, LockState.activeGroups(state(windowStart), now).size());
        assertTrue("reached in yesterday's window: inactive", LockState.activeGroups(state(windowStart), Instant.parse("2026-11-25T10:00:00Z")).isEmpty());
        assertTrue("outside the window: inactive", LockState.activeGroups(state(windowStart), Instant.parse("2026-11-24T18:00:00Z")).isEmpty());
    }
}
