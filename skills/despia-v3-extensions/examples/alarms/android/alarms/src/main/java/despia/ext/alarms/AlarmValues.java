package despia.ext.alarms;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

final class AlarmValues {
    private AlarmValues() {}

    static JSONObject parse(String source, long now) throws JSONException {
        JSONObject alarm = new JSONObject(source);
        String id = alarm.optString("id", UUID.randomUUID().toString());
        try { if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw new IllegalArgumentException(); } catch (IllegalArgumentException ex) { throw new JSONException("id must be a UUID"); }
        alarm.put("id", id.toLowerCase(java.util.Locale.ROOT));
        for (String flag : new String[]{"enabled", "vibrate", "full_screen", "loop"}) if (alarm.has(flag) && !(alarm.get(flag) instanceof Boolean)) throw new JSONException(flag + " must be a boolean");
        for (String field : new String[]{"label", "body", "sound", "group", "delivery", "color", "stop_label", "snooze_label", "open_label", "emergency_label"}) if (alarm.has(field) && !(alarm.get(field) instanceof String)) throw new JSONException(field + " must be a string");
        JSONObject schedule = alarm.getJSONObject("schedule");
        int forms = (schedule.has("at_ms") ? 1 : 0) + (schedule.has("countdown_seconds") ? 1 : 0) + (schedule.has("hour") ? 1 : 0);
        if (forms != 1) throw new JSONException("Choose at_ms, countdown_seconds or local hour and minute");
        if (schedule.has("weekdays") && !schedule.has("hour")) throw new JSONException("weekdays belongs to a local hour/minute schedule");
        long next = next(schedule, now);
        if (next <= now) throw new JSONException("The alarm must be in the future");
        if (alarm.has("challenge")) {
            JSONObject challenge = alarm.getJSONObject("challenge");
            if (challenge.optString("kind").isEmpty()) throw new JSONException("challenge.kind is required");
            double target = number(challenge, "target");
            double progress = challenge.has("progress") ? number(challenge, "progress") : 0;
            checkedProgress(progress, target);
            challenge.put("progress", progress);
        }
        if (alarm.has("snooze_seconds") && number(alarm, "snooze_seconds") <= 0) throw new JSONException("snooze_seconds must be positive");
        alarm.put("label", alarm.optString("label", "Alarm"));
        alarm.put("enabled", alarm.optBoolean("enabled", true));
        alarm.put("state", alarm.getBoolean("enabled") ? "scheduled" : "disabled");
        alarm.put("next_at_ms", next);
        return alarm;
    }

    static double number(JSONObject object, String key) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Number) || !Double.isFinite(((Number) raw).doubleValue())) throw new JSONException(key + " must be a finite number");
        return ((Number) raw).doubleValue();
    }

    static long next(JSONObject schedule, long now) throws JSONException {
        if (schedule.has("at_ms")) {
            double millis = number(schedule, "at_ms");
            if (millis != Math.rint(millis) || millis < 0 || millis > 9007199254740991L) throw new JSONException("at_ms must be an integer timestamp");
            return (long) millis;
        }
        if (schedule.has("countdown_seconds")) {
            double seconds = number(schedule, "countdown_seconds");
            if (seconds <= 0 || seconds > (9007199254740991L - now) / 1000.0) throw new JSONException("countdown_seconds is out of range");
            return now + Math.max(1, (long) (seconds * 1000));
        }
        int hour = schedule.getInt("hour"), minute = schedule.getInt("minute");
        if (hour != number(schedule, "hour") || minute != number(schedule, "minute") || hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new JSONException("Invalid local time");
        JSONArray weekdays = schedule.has("weekdays") ? schedule.getJSONArray("weekdays") : null;
        if (weekdays != null) for (int i = 0; i < weekdays.length(); i++) {
            Object raw = weekdays.get(i);
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() != weekdays.getInt(i) || weekdays.getInt(i) < 1 || weekdays.getInt(i) > 7) throw new JSONException("weekdays uses ISO Monday=1 through Sunday=7");
        }
        if (schedule.has("timezone") && !(schedule.get("timezone") instanceof String)) throw new JSONException("timezone must be a string");
        String zone = schedule.optString("timezone", TimeZone.getDefault().getID());
        boolean known = java.util.Arrays.asList(TimeZone.getAvailableIDs()).contains(zone);
        if (!known) throw new JSONException("Unknown timezone");
        Calendar day = Calendar.getInstance(TimeZone.getTimeZone(zone));
        day.setTimeInMillis(now);
        for (int offset = 0; offset < 8; offset++) {
            Calendar candidate = (Calendar) day.clone();
            candidate.set(Calendar.HOUR_OF_DAY, hour); candidate.set(Calendar.MINUTE, minute);
            candidate.set(Calendar.SECOND, 0); candidate.set(Calendar.MILLISECOND, 0);
            int iso = (candidate.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
            boolean selected = weekdays == null || weekdays.length() == 0;
            if (weekdays != null) for (int i = 0; i < weekdays.length(); i++) selected |= weekdays.getInt(i) == iso;
            long instant = candidate.getTimeInMillis();
            int offsetChange = candidate.getTimeZone().getOffset(instant) - candidate.getTimeZone().getOffset(instant - 3 * 3600000);
            if (offsetChange < 0) {
                Calendar first = (Calendar) candidate.clone(); first.setTimeInMillis(instant + offsetChange);
                if (first.get(Calendar.HOUR_OF_DAY) == candidate.get(Calendar.HOUR_OF_DAY) && first.get(Calendar.MINUTE) == candidate.get(Calendar.MINUTE) && first.get(Calendar.DATE) == candidate.get(Calendar.DATE)) instant = first.getTimeInMillis();
            }
            if (selected && instant > now) return instant;
            day.add(Calendar.DATE, 1);
        }
        throw new JSONException("No next occurrence");
    }

    static double progressText(String text) throws JSONException {
        if (text == null || !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw new JSONException("progress must be finite decimal numeric text");
        try {
            double progress = Double.parseDouble(text);
            if (!Double.isFinite(progress)) throw new JSONException("progress must be finite decimal numeric text");
            return progress;
        } catch (NumberFormatException ex) { throw new JSONException("progress must be finite decimal numeric text"); }
    }
    static double checkedProgress(double progress, double target) throws JSONException {
        if (!Double.isFinite(target) || target <= 0 || !Double.isFinite(progress) || progress < 0 || progress > target) throw new JSONException("progress must be between zero and target");
        return progress;
    }
    static boolean repeats(JSONObject alarm) {
        JSONArray days = alarm.optJSONObject("schedule").optJSONArray("weekdays");
        return days != null && days.length() > 0;
    }

    static boolean challengeComplete(JSONObject alarm) throws JSONException {
        JSONObject challenge = alarm.optJSONObject("challenge");
        return challenge == null || number(challenge, "progress") >= number(challenge, "target");
    }
}
