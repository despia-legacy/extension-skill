package despia.ext.applock;

import org.json.*;
import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public final class LockRulesTest {
    @Test public void identifiersUseTheSameCodePointBoundAsBridgeRoutes() {
        String emoji = new String(Character.toChars(0x1f600));
        assertEquals(128, LockRules.identifier(emoji.repeat(128)).codePointCount(0, emoji.repeat(128).length()));
        assertThrows(IllegalArgumentException.class, () -> LockRules.identifier(emoji.repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> LockRules.identifier(""));
    }
    @Test public void savedGroupsCannotCreateUnaddressableIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> LockState.normalizeGroup(new JSONObject().put("id", "a".repeat(129)).put("name", "Focus"), null));
    }
    @Test public void normalizesDomainsWithoutAcceptingUrlsOrWildcards() {
        assertEquals("example.com", LockRules.domain(" EXAMPLE.com. "));
        for (String invalid : Arrays.asList("https://example.com", "*.example.com", "example.com/path", "example.com:443", "a..example.com", "-a.example.com", "example.com?x=1")) assertThrows(IllegalArgumentException.class, () -> LockRules.domain(invalid));
    }
    @Test public void suffixMatchingDoesNotBlockUnrelatedNamesOrSingleLabelQueries() {
        Set<String> blocked = Collections.singleton("example.com");
        assertTrue(LockRules.matchesDomain("A.Example.com.", blocked)); assertTrue(LockRules.matchesDomain("example.com", blocked));
        assertFalse(LockRules.matchesDomain("notexample.com", blocked)); assertFalse(LockRules.matchesDomain("example.com.evil.test", blocked)); assertFalse(LockRules.matchesDomain("localhost", blocked));
    }
    @Test public void overnightWindowUsesStartDaysAndExclusiveEnd() {
        Set<Integer> sunday = Collections.singleton(1); ZoneId utc = ZoneId.of("UTC");
        assertTrue(LockRules.inWindow(Instant.parse("2026-11-22T23:00:00Z"), utc, sunday, 22, 0, 6, 0));
        assertTrue(LockRules.inWindow(Instant.parse("2026-11-23T05:59:59Z"), utc, sunday, 22, 0, 6, 0));
        assertFalse(LockRules.inWindow(Instant.parse("2026-11-23T06:00:00Z"), utc, sunday, 22, 0, 6, 0));
        assertFalse(LockRules.inWindow(Instant.parse("2026-11-23T23:00:00Z"), utc, sunday, 22, 0, 6, 0));
    }
    @Test public void dstRepeatedHourHasSameWallClockPolicy() {
        Set<Integer> sunday = Collections.singleton(1); ZoneId newYork = ZoneId.of("America/New_York");
        assertTrue(LockRules.inWindow(Instant.parse("2026-11-01T05:30:00Z"), newYork, sunday, 1, 0, 2, 0));
        assertTrue(LockRules.inWindow(Instant.parse("2026-11-01T06:30:00Z"), newYork, sunday, 1, 0, 2, 0));
        assertFalse(LockRules.inWindow(Instant.parse("2026-11-01T07:00:00Z"), newYork, sunday, 1, 0, 2, 0));
    }
    @Test public void dstSkippedHourDoesNotCreateAnExtraWindow() {
        Set<Integer> sunday = Collections.singleton(1); ZoneId newYork = ZoneId.of("America/New_York");
        assertFalse(LockRules.inWindow(Instant.parse("2026-03-08T06:59:00Z"), newYork, sunday, 2, 0, 3, 0));
        assertFalse(LockRules.inWindow(Instant.parse("2026-03-08T07:00:00Z"), newYork, sunday, 2, 0, 3, 0));
    }
    @Test public void releaseOfOneGroupKeepsOverlappingDomainBlocked() throws Exception {
        JSONObject state = state();
        JSONObject first = LockState.normalizeGroup(new JSONObject("{\"id\":\"prayer\",\"name\":\"Prayer\",\"domains\":[\"example.com\"]}"), null).put("manual_enabled", true);
        JSONObject second = LockState.normalizeGroup(new JSONObject("{\"id\":\"focus\",\"name\":\"Focus\",\"domains\":[\"example.com\"]}"), null).put("manual_enabled", true);
        state.getJSONArray("groups").put(first).put(second);
        first.put("manual_enabled", false);
        assertEquals(Collections.singleton("example.com"), LockState.domains(state));
        second.put("manual_enabled", false); assertTrue(LockState.domains(state).isEmpty());
    }
    @Test public void scheduledGroupSurvivesManualRelease() throws Exception {
        JSONObject state = state(); JSONObject group = LockState.normalizeGroup(new JSONObject("{\"id\":\"focus\",\"name\":\"Focus\"}"), null); state.getJSONArray("groups").put(group);
        state.getJSONArray("schedules").put(new JSONObject("{\"id\":\"morning\",\"group_id\":\"focus\",\"enabled\":true,\"start_hour\":8,\"start_minute\":0,\"end_hour\":10,\"end_minute\":0,\"weekdays\":[2],\"time_zone\":\"UTC\"}"));
        assertEquals(1, LockState.activeGroups(state, Instant.parse("2026-11-23T09:00:00Z")).size());
        assertEquals(0, LockState.activeGroups(state, Instant.parse("2026-11-23T10:00:00Z")).size());
    }
    @Test public void groupUpdatePreservesSelectionAndCannotEnableItThroughJson() throws Exception {
        JSONObject previous = LockState.normalizeGroup(new JSONObject("{\"id\":\"focus\",\"name\":\"Focus\",\"android\":{\"packages\":[\"com.example.game\"],\"categories\":[0]}}"), null);
        JSONObject renamed = LockState.normalizeGroup(new JSONObject("{\"name\":\"Study\",\"manual_enabled\":true}"), previous);
        assertEquals("focus", renamed.getString("id")); assertEquals("Study", renamed.getString("name")); assertFalse(renamed.getBoolean("manual_enabled")); assertEquals("com.example.game", renamed.getJSONObject("android").getJSONArray("packages").getString(0));
    }
    @Test public void jsonRoundTripRetainsGroupsAndEvents() throws Exception {
        JSONObject state = state(); state.getJSONArray("groups").put(LockState.normalizeGroup(new JSONObject("{\"id\":\"focus\",\"name\":\"Focus\",\"domains\":[\"EXAMPLE.com\",\"example.com\"]}"), null)); LockState.record(state, "group_saved", "focus", null);
        JSONObject restored = new JSONObject(state.toString()); assertEquals(1, LockState.group(restored, "focus").getJSONArray("domains").length()); assertEquals("group_saved", restored.getJSONArray("events").getJSONObject(0).getString("kind"));
    }
    private static JSONObject state() throws JSONException { return new JSONObject().put("groups", new JSONArray()).put("schedules", new JSONArray()).put("events", new JSONArray()); }
}
