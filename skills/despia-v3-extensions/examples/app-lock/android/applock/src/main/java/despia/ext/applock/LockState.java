package despia.ext.applock;

import org.json.*;
import java.time.*;
import java.util.*;

final class LockState {
    private LockState() {}
    static JSONObject group(JSONObject state, String id) throws JSONException {
        JSONArray groups = state.getJSONArray("groups");
        for (int index = 0; index < groups.length(); index++) if (id.equals(groups.getJSONObject(index).getString("id"))) return groups.getJSONObject(index);
        throw new NoSuchElementException("Group not found");
    }
    static void replace(JSONArray rows, JSONObject replacement) throws JSONException {
        String id = replacement.getString("id");
        for (int index = 0; index < rows.length(); index++) if (id.equals(rows.getJSONObject(index).getString("id"))) { rows.put(index, replacement); return; }
        rows.put(replacement);
    }
    static boolean remove(JSONArray rows, String id) throws JSONException {
        for (int index = rows.length() - 1; index >= 0; index--) if (id.equals(rows.getJSONObject(index).getString("id"))) { rows.remove(index); return true; }
        return false;
    }
    static void record(JSONObject state, String kind, String group, String detail) throws JSONException {
        JSONObject event = new JSONObject().put("id", UUID.randomUUID().toString()).put("timestamp_ms", System.currentTimeMillis()).put("kind", kind);
        if (group != null) event.put("group_id", group); if (detail != null) event.put("detail", detail);
        state.getJSONArray("events").put(event);
    }
    static List<JSONObject> activeGroups(JSONObject state, Instant now) throws JSONException {
        Set<String> scheduled = new HashSet<>(); JSONArray schedules = state.getJSONArray("schedules");
        for (int index = 0; index < schedules.length(); index++) {
            JSONObject schedule = schedules.getJSONObject(index);
            if (schedule.optBoolean("enabled", true) && active(schedule, now)) scheduled.add(schedule.getString("group_id"));
        }
        List<JSONObject> active = new ArrayList<>(); JSONArray groups = state.getJSONArray("groups");
        for (int index = 0; index < groups.length(); index++) { JSONObject group = groups.getJSONObject(index); if (group.optBoolean("manual_enabled") || scheduled.contains(group.getString("id"))) active.add(group); }
        return active;
    }
    static boolean active(JSONObject schedule, Instant now) throws JSONException {
        Set<Integer> days = new HashSet<>(); JSONArray weekdays = schedule.getJSONArray("weekdays");
        for (int index = 0; index < weekdays.length(); index++) days.add(weekdays.getInt(index));
        return LockRules.inWindow(now, ZoneId.of(schedule.getString("time_zone")), days, schedule.getInt("start_hour"), schedule.getInt("start_minute"), schedule.getInt("end_hour"), schedule.getInt("end_minute"));
    }
    static Set<String> domains(JSONObject state) throws JSONException {
        Set<String> domains = new HashSet<>();
        for (JSONObject group : activeGroups(state, Instant.now())) { JSONArray names = group.optJSONArray("domains"); if (names != null) for (int index = 0; index < names.length(); index++) domains.add(names.getString(index)); }
        return domains;
    }
    static JSONObject normalizeGroup(JSONObject input, JSONObject previous) throws JSONException {
        JSONObject group = previous == null ? new JSONObject().put("id", input.optString("id", UUID.randomUUID().toString())).put("manual_enabled", false).put("domains", new JSONArray()).put("android", new JSONObject().put("packages", new JSONArray()).put("categories", new JSONArray())).put("shield", new JSONObject()) : new JSONObject(previous.toString());
        LockRules.identifier(group.getString("id"));
        String name = input.getString("name"); if (name.trim().isEmpty() || group.getString("id").isEmpty()) throw new IllegalArgumentException("Group id and name must not be empty"); group.put("name", name);
        if (input.has("domains")) { Set<String> normalized = new TreeSet<>(); JSONArray domains = input.getJSONArray("domains"); for (int index = 0; index < domains.length(); index++) normalized.add(LockRules.domain(domains.getString(index))); group.put("domains", new JSONArray(normalized)); }
        if (input.has("android")) {
            JSONObject android = input.getJSONObject("android"), normalized = new JSONObject(); JSONArray packages = android.optJSONArray("packages"), categories = android.optJSONArray("categories");
            Set<String> names = new TreeSet<>(); if (packages != null) for (int index = 0; index < packages.length(); index++) { String nameOfPackage = packages.getString(index); if (!nameOfPackage.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")) throw new IllegalArgumentException("Invalid package name"); names.add(nameOfPackage); }
            Set<Integer> categoryIds = new TreeSet<>(); if (categories != null) for (int index = 0; index < categories.length(); index++) categoryIds.add(categories.getInt(index));
            normalized.put("packages", new JSONArray(names)); normalized.put("categories", new JSONArray(categoryIds)); group.put("android", normalized);
        }
        if (input.has("shield")) group.put("shield", input.getJSONObject("shield"));
        return group;
    }
}
