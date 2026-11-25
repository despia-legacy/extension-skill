package despia.ext.alarms;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

final class AlarmStore {
    static final String FIRE = "despia.ext.alarms.FIRE", STOP = "despia.ext.alarms.STOP", SNOOZE = "despia.ext.alarms.SNOOZE";
    private static com.despia.nativeext.ExtContext bridge;
    private AlarmStore() {}
    static synchronized void attach(com.despia.nativeext.ExtContext context) { bridge = context; }
    private static android.content.SharedPreferences prefs(Context context) { return context.getSharedPreferences("DESPIA_NX_ALARMS", Context.MODE_PRIVATE); }
    private static JSONObject records(Context context) throws JSONException { return new JSONObject(prefs(context).getString("alarms", "{}")); }
    private static void write(Context context, JSONObject records) {
        if (!prefs(context).edit().putString("alarms", records.toString()).commit()) throw new IllegalStateException("Alarm storage write failed");
    }
    static synchronized JSONObject get(Context context, String id) throws JSONException {
        JSONObject alarm = records(context).optJSONObject(id);
        if (alarm == null) throw new JSONException("Alarm not found");
        return alarm;
    }
    static synchronized JSONArray list(Context context, String group) throws JSONException {
        JSONObject records = records(context); JSONArray alarms = new JSONArray();
        java.util.Iterator<String> ids = records.keys();
        while (ids.hasNext()) {
            JSONObject alarm = records.getJSONObject(ids.next());
            if (group == null || group.equals(alarm.optString("group"))) alarms.put(alarm);
        }
        return alarms;
    }
    static synchronized void put(Context context, JSONObject alarm) throws JSONException {
        JSONObject records = records(context); records.put(alarm.getString("id"), alarm); write(context, records);
    }
    static synchronized JSONObject save(Context context, String source) throws JSONException {
        JSONObject alarm = AlarmValues.parse(source, System.currentTimeMillis());
        if (alarm.getBoolean("enabled")) requireExact(context);
        replace(context, alarm);
        AlarmAudioService.stop(context, alarm.getString("id"));
        event(context, "saved", alarm.getString("id"), new JSONObject());
        return alarm;
    }
    static void requireExact(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager.class).canScheduleExactAlarms()) throw new SecurityException("Exact alarm access is required");
    }
    private static PendingIntent pending(Context context, String id, String token, String action, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class).setAction(action).setData(android.net.Uri.parse("despia-nx-alarm:" + id + "/" + token));
        intent.putExtra("alarm_id", id).putExtra("native_key", token);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }
    static PendingIntent action(Context context, String id, String action) { return pending(context, id, "action", action, PendingIntent.FLAG_UPDATE_CURRENT); }
    static void disarm(Context context, JSONObject alarm) throws JSONException {
        PendingIntent scheduled = pending(context, alarm.getString("id"), alarm.getString("native_key"), FIRE, PendingIntent.FLAG_NO_CREATE);
        if (scheduled != null) { context.getSystemService(AlarmManager.class).cancel(scheduled); scheduled.cancel(); }
    }
    static void arm(Context context, JSONObject alarm) throws JSONException {
        String id = alarm.getString("id");
        if (!alarm.optBoolean("enabled") || "paused".equals(alarm.optString("state"))) return;
        requireExact(context);
        PendingIntent fire = pending(context, id, alarm.getString("native_key"), FIRE, PendingIntent.FLAG_UPDATE_CURRENT);
        Intent screen = new Intent(context, AlarmScreen.class).putExtra("alarm_id", id).setData(android.net.Uri.parse("despia-nx-alarm:" + id));
        PendingIntent show = PendingIntent.getActivity(context, 0, screen, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        context.getSystemService(AlarmManager.class).setAlarmClock(new AlarmManager.AlarmClockInfo(alarm.getLong("next_at_ms"), show), fire);
    }
    private static void replace(Context context, JSONObject alarm) throws JSONException {
        JSONObject previous = records(context).optJSONObject(alarm.getString("id"));
        alarm.put("native_key", UUID.randomUUID().toString());
        try { arm(context, alarm); put(context, alarm); }
        catch (JSONException | RuntimeException ex) {
            try { disarm(context, alarm); }
            catch (Exception cleanup) { android.util.Log.e("DespiaAlarms", "Cannot cancel failed replacement", cleanup); }
            throw ex;
        }
        if (previous != null) disarm(context, previous);
    }
    static synchronized JSONObject cancel(Context context, String id) throws JSONException {
        JSONObject alarm = get(context, id);
        JSONObject records = records(context); records.remove(id); write(context, records);
        disarm(context, alarm); AlarmAudioService.stop(context, id);
        event(context, "cancelled", id, new JSONObject()); return alarm;
    }
    static synchronized JSONObject stop(Context context, String id, boolean emergency) throws JSONException {
        JSONObject alarm = get(context, id);
        if (!emergency && !AlarmValues.challengeComplete(alarm)) throw new IllegalStateException("challenge_incomplete");
        if (AlarmValues.repeats(alarm) && alarm.optLong("next_at_ms") > System.currentTimeMillis()) alarm.put("state", "scheduled");
        else { alarm.put("state", "stopped").put("enabled", false); }
        put(context, alarm);
        if (!alarm.optBoolean("enabled")) disarm(context, alarm);
        AlarmAudioService.stop(context, id); event(context, emergency ? "emergency_stopped" : "stopped", id, new JSONObject()); return alarm;
    }
    static synchronized JSONObject snooze(Context context, String id, double seconds) throws JSONException {
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > (9007199254740991L - System.currentTimeMillis()) / 1000.0) throw new JSONException("seconds must be positive and in range");
        JSONObject alarm = get(context, id); requireExact(context);
        alarm.put("enabled", true).put("state", "scheduled").put("next_at_ms", System.currentTimeMillis() + Math.max(1, (long) (seconds * 1000)));
        replace(context, alarm); AlarmAudioService.stop(context, id);
        event(context, "snoozed", id, new JSONObject().put("next_at_ms", alarm.getLong("next_at_ms"))); return alarm;
    }
    static synchronized JSONObject pause(Context context, String id) throws JSONException {
        JSONObject alarm = get(context, id);
        if (!"scheduled".equals(alarm.optString("state"))) throw new IllegalStateException("Only a scheduled alarm can pause");
        long remaining = alarm.getLong("next_at_ms") - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("Alarm is already due");
        alarm.put("remaining_ms", remaining).put("state", "paused");
        put(context, alarm); disarm(context, alarm); event(context, "paused", id, new JSONObject()); return alarm;
    }
    static synchronized JSONObject resume(Context context, String id) throws JSONException {
        JSONObject alarm = get(context, id);
        if (!"paused".equals(alarm.optString("state"))) throw new IllegalStateException("Alarm is not paused");
        requireExact(context); alarm.put("state", "scheduled").put("next_at_ms", System.currentTimeMillis() + alarm.getLong("remaining_ms"));
        alarm.remove("remaining_ms"); replace(context, alarm); event(context, "resumed", id, new JSONObject()); return alarm;
    }
    static synchronized JSONObject challenge(Context context, String id, double progress) throws JSONException {
        JSONObject alarm = get(context, id); JSONObject challenge = alarm.getJSONObject("challenge");
        double target = AlarmValues.number(challenge, "target");
        AlarmValues.checkedProgress(progress, target);
        challenge.put("progress", progress); put(context, alarm);
        event(context, "challenge", id, new JSONObject().put("progress", progress).put("target", target));
        if (progress >= target) return stop(context, id, false);
        return alarm;
    }
    static synchronized JSONObject fire(Context context, String id, String token) throws JSONException {
        JSONObject alarm = get(context, id);
        if (!alarm.getString("native_key").equals(token)) throw new IllegalStateException("This native alarm has been replaced");
        if (!alarm.optBoolean("enabled") || "paused".equals(alarm.optString("state"))) throw new IllegalStateException("Alarm is not armed");
        // A replaced PendingIntent must not ring an alarm moved into the future.
        if (alarm.getLong("next_at_ms") > System.currentTimeMillis() + 1000) throw new IllegalStateException("Alarm is not due");
        alarm.put("state", "ringing");
        if (alarm.has("challenge")) alarm.getJSONObject("challenge").put("progress", 0);
        if (AlarmValues.repeats(alarm)) {
            alarm.put("next_at_ms", AlarmValues.next(alarm.getJSONObject("schedule"), System.currentTimeMillis() + 1000)); arm(context, alarm);
        }
        put(context, alarm); event(context, "ringing", id, new JSONObject()); return alarm;
    }
    static synchronized void restore(Context context, boolean clockChanged) throws JSONException {
        JSONArray alarms = list(context, null); long now = System.currentTimeMillis();
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.getJSONObject(i);
            if (!alarm.optBoolean("enabled") || "paused".equals(alarm.optString("state"))) continue;
            if ((clockChanged || alarm.optLong("next_at_ms") <= now) && alarm.getJSONObject("schedule").has("hour") && AlarmValues.repeats(alarm)) {
                alarm.put("next_at_ms", AlarmValues.next(alarm.getJSONObject("schedule"), now));
                alarm.put("state", "scheduled"); put(context, alarm);
            }
            if (alarm.optLong("next_at_ms") <= now) {
                alarm.put("state", "missed").put("enabled", false); put(context, alarm);
                event(context, "missed", alarm.getString("id"), new JSONObject().put("cause", "restart_after_due"));
            } else {
                try { arm(context, alarm); }
                catch (SecurityException ex) { event(context, "access_required", alarm.getString("id"), new JSONObject().put("message", ex.getMessage())); }
            }
        }
    }
    static synchronized void opened(Context context, String id, String token) throws JSONException {
        get(context, id);
        if (token == null || !UUID.fromString(token).toString().equalsIgnoreCase(token)) throw new JSONException("Invalid alarm open token");
        JSONArray previous = events(context);
        for (int i = 0; i < previous.length(); i++) if (token.equals(previous.getJSONObject(i).getString("id"))) return;
        recordEvent(context, "opened", id, new JSONObject().put("source", "native_screen"), token);
    }
    static synchronized JSONObject event(Context context, String type, String id, JSONObject fields) throws JSONException {
        return recordEvent(context, type, id, fields, UUID.randomUUID().toString());
    }
    private static JSONObject recordEvent(Context context, String type, String id, JSONObject fields, String receiptId) throws JSONException {
        JSONObject receipt = new JSONObject().put("id", receiptId).put("type", type)
            .put("alarm_id", id).put("at_ms", System.currentTimeMillis()).put("details", fields);
        JSONArray events = events(context); events.put(receipt);
        if (!prefs(context).edit().putString("events", events.toString()).commit()) throw new IllegalStateException("Event storage write failed");
        if (bridge != null) bridge.emit("changed", receipt);
        return receipt;
    }
    static synchronized JSONArray events(Context context) throws JSONException { return new JSONArray(prefs(context).getString("events", "[]")); }
    static synchronized int acknowledge(Context context, JSONArray ids) throws JSONException {
        JSONArray all = events(context), kept = new JSONArray(); int removed = 0;
        java.util.HashSet<String> selected = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) selected.add(ids.getString(i));
        for (int i = 0; i < all.length(); i++) {
            JSONObject event = all.getJSONObject(i);
            if (selected.contains(event.getString("id"))) removed++; else kept.put(event);
        }
        if (!prefs(context).edit().putString("events", kept.toString()).commit()) throw new IllegalStateException("Event storage write failed");
        return removed;
    }
}
