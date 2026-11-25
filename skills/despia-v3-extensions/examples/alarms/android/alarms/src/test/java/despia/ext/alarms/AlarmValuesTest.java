package despia.ext.alarms;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.Instant;

public final class AlarmValuesTest {
    private long date(String iso) { return Instant.parse(iso).toEpochMilli(); }
    @Test public void absoluteAndCountdownAreExact() throws Exception {
        long now = date("2026-11-21T00:00:00Z");
        assertEquals(now + 12345, AlarmValues.next(new JSONObject().put("at_ms", now + 12345), now));
        assertEquals(now + 1500, AlarmValues.next(new JSONObject().put("countdown_seconds", 1.5), now));
    }
    @Test public void mondayAndSundayUseIsoNumbers() throws Exception {
        JSONObject weekly = new JSONObject("{\"hour\":7,\"minute\":30,\"weekdays\":[1],\"timezone\":\"UTC\"}");
        assertEquals(date("2026-11-23T07:30:00Z"), AlarmValues.next(weekly, date("2026-11-21T00:00:00Z")));
        weekly.put("weekdays", new org.json.JSONArray("[7]"));
        assertEquals(date("2026-11-22T07:30:00Z"), AlarmValues.next(weekly, date("2026-11-21T00:00:00Z")));
    }
    @Test public void dstGapPreservesMinutesAndFoldUsesFirstOccurrence() throws Exception {
        JSONObject local = new JSONObject("{\"hour\":2,\"minute\":30,\"weekdays\":[7],\"timezone\":\"America/New_York\"}");
        assertEquals(date("2026-03-08T07:30:00Z"), AlarmValues.next(local, date("2026-03-08T00:00:00Z")));
        local.put("hour", 1);
        assertEquals(date("2026-11-01T05:30:00Z"), AlarmValues.next(local, date("2026-11-01T00:00:00Z")));
    }
    @Test public void preservesMetadataAndChallengeGate() throws Exception {
        JSONObject alarm = AlarmValues.parse("{\"label\":\"Exercise\",\"metadata\":{\"customerKey\":42},\"schedule\":{\"countdown_seconds\":60},\"challenge\":{\"kind\":\"pushups\",\"target\":10}}", 1000);
        assertEquals(42, alarm.getJSONObject("metadata").getInt("customerKey"));
        assertFalse(AlarmValues.challengeComplete(alarm));
        alarm.getJSONObject("challenge").put("progress", 10);
        assertTrue(AlarmValues.challengeComplete(alarm));
    }
    @Test public void fractionalChallengeCompletesWithoutLosingCustomConfiguration() throws Exception {
        JSONObject alarm = AlarmValues.parse("{\"schedule\":{\"countdown_seconds\":60},\"challenge\":{\"kind\":\"distance\",\"target\":0.5,\"configuration\":{\"unit\":\"km\"}}}", 1000);
        double progress = AlarmValues.checkedProgress(AlarmValues.progressText("5e-1"), alarm.getJSONObject("challenge").getDouble("target"));
        alarm.getJSONObject("challenge").put("progress", progress);
        assertTrue(AlarmValues.challengeComplete(alarm));
        assertEquals("km", alarm.getJSONObject("challenge").getJSONObject("configuration").getString("unit"));
    }
    @Test public void numericProgressRejectsNonfiniteTextAndOutOfBounds() throws Exception {
        for (String text : new String[]{"NaN", "nan", "Inf", "Infinity", "-Infinity", "1e309", "words", "", " 0.5", "0x1.0p0"}) {
            try { AlarmValues.progressText(text); fail("Accepted " + text); }
            catch (JSONException expected) { assertNotNull(expected.getMessage()); }
        }
        for (double progress : new double[]{-0.1, 0.6, Double.NaN, Double.POSITIVE_INFINITY}) {
            try { AlarmValues.checkedProgress(progress, 0.5); fail("Accepted out of bounds"); }
            catch (JSONException expected) { assertNotNull(expected.getMessage()); }
        }
        assertEquals(0.0, AlarmValues.checkedProgress(AlarmValues.progressText("0"), 0.5), 0.0);
    }
    @Test public void rejectsMalformedSchedulesAndProgress() {
        String[] invalid = {
            "{\"schedule\":{\"countdown_seconds\":0}}", "{\"schedule\":{\"countdown_seconds\":1,\"at_ms\":999999}}",
            "{\"schedule\":{\"hour\":24,\"minute\":0}}", "{\"schedule\":{\"hour\":1.5,\"minute\":0}}",
            "{\"schedule\":{\"hour\":1,\"minute\":0,\"weekdays\":[0]}}", "{\"schedule\":{\"at_ms\":true}}",
            "{\"enabled\":\"true\",\"schedule\":{\"countdown_seconds\":1}}", "{\"id\":\"1-1-1-1-1\",\"schedule\":{\"countdown_seconds\":1}}",
            "{\"schedule\":{\"countdown_seconds\":1},\"challenge\":{\"kind\":\"any\",\"target\":10,\"progress\":11}}",
            "{\"delivery\":true,\"schedule\":{\"countdown_seconds\":1}}", "{\"color\":42,\"schedule\":{\"countdown_seconds\":1}}",
            "{\"schedule\":{\"hour\":1,\"minute\":0,\"timezone\":42}}", "{\"snooze_label\":{},\"schedule\":{\"countdown_seconds\":1}}"
        };
        for (String source : invalid) {
            try { AlarmValues.parse(source, 1000); fail("Accepted " + source); }
            catch (JSONException expected) { assertNotNull(expected.getMessage()); }
        }
    }
}
