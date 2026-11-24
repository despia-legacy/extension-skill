package despia.ext.calendar;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import com.despia.nativeext.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class CalendarExtension implements NativeExtension {
    private ExtContext ctx;
    private ContentResolver resolver;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Handler main;
    private ComponentActivity activity;
    private ActivityResultLauncher<String[]> permissions;
    private boolean permissionPending;
    private ContentObserver observer;

    @Override public void onCreate(ExtContext context) {
        ctx = context; resolver = ctx.app().getContentResolver(); main = new Handler(Looper.getMainLooper());
    }
    @Override public void onActivityEvent(Activity value, ActivityEvent event) {
        if (!(value instanceof ComponentActivity)) return;
        ComponentActivity host = (ComponentActivity)value;
        if (event == ActivityEvent.CREATED) {
            if (permissions != null) permissions.unregister();
            activity = host;
            permissions = host.getActivityResultRegistry().register(ctx.resultKey("permissions"), new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                permissionPending = false;
                try { ctx.emit("permission", permissionState()); } catch (JSONException ignored) {}
                observeIfAllowed();
            });
        } else if (event == ActivityEvent.DESTROYED && activity == host) {
            if (permissions != null) permissions.unregister();
            permissions = null; activity = null;
            if (host.isFinishing() && permissionPending) {
                permissionPending = false;
                try { ctx.emit("permission", permissionState().put("error", "activity_destroyed")); } catch (JSONException ignored) {}
            }
        }
    }
    private boolean granted(String name) { return ContextCompat.checkSelfPermission(ctx.app(), name) == PackageManager.PERMISSION_GRANTED; }
    private JSONObject permissionState() throws JSONException {
        return new JSONObject().put("events", granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR) ? "full" : granted(Manifest.permission.WRITE_CALENDAR) ? "write_only" : granted(Manifest.permission.READ_CALENDAR) ? "read_only" : "not_granted")
            .put("read", granted(Manifest.permission.READ_CALENDAR)).put("write", granted(Manifest.permission.WRITE_CALENDAR)).put("reminders", "unsupported").put("pending", permissionPending);
    }
    private void observeIfAllowed() {
        if (observer != null || !granted(Manifest.permission.READ_CALENDAR)) return;
        observer = new ContentObserver(main) { @Override public void onChange(boolean selfChange) {
            try { ctx.emit("changed", new JSONObject().put("changed", true)); } catch (JSONException ignored) {}
        }};
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer);
    }
    @Override public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            switch (call.route) {
                case "capabilities": reply.reply(ExtResult.ok(new JSONObject().put("provider", "calendar_provider").put("events", true).put("calendars", true).put("recurrence", true).put("alarms", true).put("native_editor", true).put("ios_reminders", false).put("android_table", true).put("google_cloud_api", false))); return;
                case "permissions":
                    if ("reminders".equals(call.params.get("access"))) { reply.reply(ExtResult.error("unsupported", "iOS Reminders has no Android Calendar Provider equivalent")); return; }
                    if (Boolean.TRUE.equals(call.params.get("request"))) {
                        if (permissionPending) { reply.reply(ExtResult.error("busy", "A calendar permission request is pending")); return; }
                        if (permissions == null) { reply.reply(ExtResult.error("unavailable", "No host Activity")); return; }
                        List<String> missing = new ArrayList<>();
                        if (!"write_only".equals(call.params.get("access")) && !granted(Manifest.permission.READ_CALENDAR)) missing.add(Manifest.permission.READ_CALENDAR);
                        if (!granted(Manifest.permission.WRITE_CALENDAR)) missing.add(Manifest.permission.WRITE_CALENDAR);
                        if (!missing.isEmpty()) { permissionPending = true; permissions.launch(missing.toArray(new String[0])); }
                    }
                    reply.reply(ExtResult.ok(permissionState())); observeIfAllowed(); return;
                case "present": reply.reply(present(call.params)); return;
                case "ios_sources": case "ios_reminders": case "ios_reminder_save": case "ios_reminder_delete":
                    reply.reply(ExtResult.error("unsupported", "This route uses iOS EventKit")); return;
                default: break;
            }
            observeIfAllowed();
            worker.execute(() -> {
                ExtResult result;
                try {
                    // Read the bridge's lifetime on its owning main thread before queued work starts.
                    FutureTask<Boolean> active = new FutureTask<>(call::isActive); main.post(active);
                    if (!active.get()) { main.post(() -> reply.reply(ExtResult.error("timeout", "Calendar call expired"))); return; }
                    result = execute(call);
                } catch (SecurityException ex) { result = ExtResult.error("permission_denied", "Calendar permission is not granted"); }
                catch (IllegalArgumentException | JSONException ex) { result = ExtResult.error("bad_request", ex.getMessage()); }
                catch (NoSuchElementException ex) { result = ExtResult.error("not_found", "Calendar item not found"); }
                catch (Exception ex) { result = ExtResult.error("calendar_error", "Calendar Provider could not complete the operation"); }
                ExtResult answer = result; main.post(() -> reply.reply(answer));
            });
        } catch (SecurityException ex) { reply.reply(ExtResult.error("permission_denied", "Calendar permission is not granted")); }
        catch (JSONException | IllegalArgumentException ex) { reply.reply(ExtResult.error("bad_request", ex.getMessage())); }
    }
    private ExtResult execute(ExtCall call) throws Exception {
        Map<String,Object> p = call.params;
        if ("reminders".equals(p.get("entity"))) return ExtResult.error("unsupported", "Reminder lists use iOS EventKit; Android Calendar Provider has event alarm reminders only");
        switch (call.route) {
            case "calendars": {
                JSONObject answer = query(CalendarContract.Calendars.CONTENT_URI, new String[]{"_id", "calendar_displayName", "calendar_access_level", "account_name", "account_type", "calendar_timezone", "calendar_color"}, null, null, "_id ASC", 0, 100, "calendars");
                JSONArray calendars = answer.getJSONArray("calendars");
                for (int n = 0; n < calendars.length(); n++) calendars.put(n, normalizedCalendar(calendars.getJSONObject(n)));
                return ExtResult.ok(answer);
            }
            case "calendar_save": {
                JSONObject data = object(p.get("data")); String id = data.optString("id", "");
                ContentValues values = new ContentValues(); values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, data.getString("title"));
                if (id.isEmpty()) {
                    // Ordinary apps can create local calendars. Remote account/sync ownership stays with its sync adapter.
                    String account = data.optString("account_name", "Despia calendar");
                    values.put(CalendarContract.Calendars.ACCOUNT_NAME, account); values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
                    values.put(CalendarContract.Calendars.NAME, data.optString("name", UUID.randomUUID().toString()));
                    values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account); values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
                    values.put(CalendarContract.Calendars.VISIBLE, 1); values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
                    values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff3f51b5);
                    Uri localCalendars = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
                    Uri uri = resolver.insert(localCalendars, values);
                    if (uri == null) throw new IllegalStateException(); id = uri.getLastPathSegment();
                } else if (resolver.update(item(CalendarContract.Calendars.CONTENT_URI, id), values, null, null) == 0) throw new NoSuchElementException();
                return ExtResult.ok(new JSONObject().put("id", id).put("saved", true));
            }
            case "calendar_delete": return deleted(CalendarContract.Calendars.CONTENT_URI, p.get("id"));
            case "events": {
                long start = integer(p.get("start_ms")), end = integer(p.get("end_ms")); if (end <= start) throw new IllegalArgumentException("end_ms must be after start_ms");
                Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(builder, start); ContentUris.appendId(builder, end);
                String selection = p.containsKey("calendar_id") ? "calendar_id = ?" : null;
                String[] args = selection == null ? null : new String[]{String.valueOf(integer(p.get("calendar_id")))};
                JSONObject raw = query(builder.build(), eventColumns(true), selection, args, "begin ASC, event_id ASC", offset(p), limit(p), "events");
                JSONArray items = raw.getJSONArray("events"); for (int n=0;n<items.length();n++) items.put(n, normalizedEvent(items.getJSONObject(n), true));
                return ExtResult.ok(raw);
            }
            case "event_get": {
                JSONObject raw = query(item(CalendarContract.Events.CONTENT_URI, p.get("id")), eventColumns(false), null, null, null, 0, 1, "events");
                JSONArray rows = raw.getJSONArray("events"); if (rows.length() == 0) throw new NoSuchElementException();
                JSONObject event = normalizedEvent(rows.getJSONObject(0), false);
                event.put("alarms", query(CalendarContract.Reminders.CONTENT_URI, null, "event_id = ?", new String[]{String.valueOf(integer(p.get("id")))}, "_id ASC", 0, 100, "rows").getJSONArray("rows"));
                event.put("attendees", query(CalendarContract.Attendees.CONTENT_URI, null, "event_id = ?", new String[]{String.valueOf(integer(p.get("id")))}, "_id ASC", 0, 100, "rows").getJSONArray("rows"));
                return ExtResult.ok(event);
            }
            case "event_save":
                if ("future".equals(p.get("span"))) return ExtResult.error("unsupported", "Android series splitting requires explicit provider operations through android_table");
                return saveEvent(object(p.get("data")));
            case "event_delete":
                if ("future".equals(p.get("span"))) return ExtResult.error("unsupported", "For Android recurrence exceptions or series splitting use android_table with the provider's original-instance fields");
                JSONObject before = query(item(CalendarContract.Events.CONTENT_URI, p.get("id")), new String[]{"rrule", "rdate", "original_id"}, null, null, null, 0, 1, "rows");
                if (before.getJSONArray("rows").length() == 0) throw new NoSuchElementException();
                JSONObject series = before.getJSONArray("rows").getJSONObject(0);
                if (!series.isNull("rrule") && !series.optString("rrule").isEmpty() || !series.isNull("rdate") && !series.optString("rdate").isEmpty()) return ExtResult.error("unsupported", "Choose the explicit series or occurrence operation with android_table; this route will not silently delete a recurring series");
                return deleted(CalendarContract.Events.CONTENT_URI, p.get("id"));
            case "android_table": return table(p);
            default: return ExtResult.error("not_registered", call.route);
        }
    }
    private static JSONObject normalizedCalendar(JSONObject row) throws JSONException {
        return new JSONObject().put("id", String.valueOf(row.getLong("_id")))
            .put("title", row.optString("calendar_displayName", ""))
            .put("writable", row.optInt("calendar_access_level") >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            .put("android", row);
    }
    private String[] eventColumns(boolean instances) {
        return new String[]{instances ? "event_id" : "_id", "calendar_id", "title", instances ? "begin" : "dtstart", instances ? "end" : "dtend", "allDay", "eventTimezone", "eventLocation", "description", "rrule", "rdate", "exrule", "exdate", "availability", "accessLevel"};
    }
    private JSONObject normalizedEvent(JSONObject row, boolean instances) throws JSONException {
        return new JSONObject().put("id", String.valueOf(row.getLong(instances ? "event_id" : "_id"))).put("calendar_id", String.valueOf(row.getLong("calendar_id"))).put("title", row.optString("title", ""))
            .put("start_ms", row.opt(instances ? "begin" : "dtstart")).put("end_ms", row.opt(instances ? "end" : "dtend")).put("all_day", row.optInt("allDay") != 0)
            .put("timezone", row.optString("eventTimezone", "")).put("location", row.optString("eventLocation", "")).put("notes", row.optString("description", ""))
            .put("android", row);
    }
    private ExtResult saveEvent(JSONObject data) throws Exception {
        ContentValues values = new ContentValues(); String id = data.optString("id", "");
        if (data.has("calendar_id")) values.put("calendar_id", integer(data.get("calendar_id")));
        if (id.isEmpty() && !data.has("calendar_id")) throw new IllegalArgumentException("calendar_id is required for a new Android event");
        copy(data, values, "title", "title"); copy(data, values, "notes", "description"); copy(data, values, "location", "eventLocation");
        if (data.has("start_ms")) values.put("dtstart", integer(data.get("start_ms")));
        if (data.has("end_ms")) values.put("dtend", integer(data.get("end_ms")));
        if (id.isEmpty() && (!data.has("start_ms") || !data.has("end_ms"))) throw new IllegalArgumentException("start_ms and end_ms are required");
        if (data.has("start_ms") && data.has("end_ms") && integer(data.get("end_ms")) <= integer(data.get("start_ms"))) throw new IllegalArgumentException("end_ms must be after start_ms");
        if (data.has("all_day")) values.put("allDay", data.getBoolean("all_day") ? 1 : 0);
        if (data.has("timezone")) { String zone = data.getString("timezone"); if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(zone)) throw new IllegalArgumentException("Unknown timezone"); values.put("eventTimezone", zone); }
        else if (id.isEmpty()) values.put("eventTimezone", TimeZone.getDefault().getID());
        JSONObject android = data.optJSONObject("android"); if (android != null) values.putAll(content(android));
        if (data.has("recurrence")) {
            JSONArray rules = data.getJSONArray("recurrence"); if (rules.length() > 1) throw new IllegalArgumentException("Android supports one RRULE; use android rdate/exdate for additional dates");
            values.put("rrule", rules.length() == 0 ? "" : recurrence(rules.getJSONObject(0)));
        }
        if (values.containsKey("rrule") && !values.getAsString("rrule").isEmpty()) {
            if (!data.has("start_ms") || !data.has("end_ms")) throw new IllegalArgumentException("Recurring event update needs start_ms and end_ms");
            values.remove("dtend"); values.put("duration", "P" + ((integer(data.get("end_ms")) - integer(data.get("start_ms"))) / 1000) + "S");
        }
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        if (id.isEmpty()) operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build());
        else operations.add(ContentProviderOperation.newUpdate(item(CalendarContract.Events.CONTENT_URI, id)).withValues(values).withExpectedCount(1).build());
        JSONArray alarms = data.optJSONArray("alarms");
        if (alarms != null) {
            if (!id.isEmpty()) operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id = ?", new String[]{id}).build());
            for (int n=0;n<alarms.length();n++) {
                JSONObject alarm = alarms.getJSONObject(n); long minutes = integer(alarm.get("minutes")); if (minutes < 0) throw new IllegalArgumentException("Android reminders must be before the event");
                ContentProviderOperation.Builder insert = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("minutes", minutes).withValue("method", CalendarContract.Reminders.METHOD_ALERT);
                if (id.isEmpty()) insert.withValueBackReference("event_id", 0); else insert.withValue("event_id", Long.parseLong(id));
                operations.add(insert.build());
            }
        }
        ContentProviderResult[] result = resolver.applyBatch(CalendarContract.AUTHORITY, operations);
        if (id.isEmpty()) { if (result[0].uri == null) throw new IllegalStateException(); id = result[0].uri.getLastPathSegment(); }
        return ExtResult.ok(new JSONObject().put("id", id).put("saved", true));
    }
    private ExtResult table(Map<String,Object> p) throws Exception {
        String name = (String)p.get("table"), operation = (String)p.get("operation");
        Uri uri;
        switch (name) {
            case "events": uri = CalendarContract.Events.CONTENT_URI; break;
            case "instances": uri = CalendarContract.Instances.CONTENT_URI; break;
            case "attendees": uri = CalendarContract.Attendees.CONTENT_URI; break;
            case "reminders": uri = CalendarContract.Reminders.CONTENT_URI; break;
            case "calendars": uri = CalendarContract.Calendars.CONTENT_URI; break;
            default: throw new IllegalArgumentException("Unknown Calendar Provider table");
        }
        if (name.equals("instances")) {
            if (!operation.equals("query")) throw new IllegalArgumentException("Instances are generated by Android and are read only");
            long start = integer(p.get("start_ms")), end = integer(p.get("end_ms")); if (end <= start) throw new IllegalArgumentException("Invalid instance range");
            Uri.Builder builder = uri.buildUpon(); ContentUris.appendId(builder, start); ContentUris.appendId(builder, end); uri = builder.build();
        } else if (p.containsKey("id")) uri = item(uri, p.get("id"));
        String selection = p.containsKey("event_id") ? "event_id = ?" : null;
        String[] args = selection == null ? null : new String[]{String.valueOf(integer(p.get("event_id")))};
        if (selection != null && !Arrays.asList("attendees", "reminders", "instances").contains(name)) throw new IllegalArgumentException("event_id filter belongs to attendees, reminders or instances");
        switch (operation) {
            case "query": return ExtResult.ok(query(uri, null, selection, args, null, offset(p), limit(p), "rows"));
            case "insert":
                if (p.containsKey("id")) throw new IllegalArgumentException("Insert cannot name an existing id");
                Uri inserted = resolver.insert(uri, content(object(p.get("data")))); if (inserted == null) throw new IllegalStateException();
                return ExtResult.ok(new JSONObject().put("id", inserted.getLastPathSegment()));
            case "update": case "delete":
                if (!p.containsKey("id")) throw new IllegalArgumentException("Update and delete require a specific id");
                int count = operation.equals("delete") ? resolver.delete(uri, null, null) : resolver.update(uri, content(object(p.get("data"))), null, null);
                return ExtResult.ok(new JSONObject().put("changed", count));
            default: throw new IllegalArgumentException("Unknown operation");
        }
    }
    private JSONObject query(Uri uri, String[] columns, String selection, String[] args, String sort, int offset, int limit, String field) throws JSONException {
        JSONArray rows = new JSONArray(); boolean more = false;
        try (Cursor cursor = resolver.query(uri, columns, selection, args, sort)) {
            if (cursor == null) throw new IllegalStateException();
            if (cursor.moveToPosition(offset)) {
                do {
                    if (rows.length() == limit) { more = true; break; }
                    JSONObject row = new JSONObject();
                    for (int i=0;i<cursor.getColumnCount();i++) {
                        Object value;
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: value = JSONObject.NULL; break;
                            case Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(i); break;
                            case Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(i); break;
                            case Cursor.FIELD_TYPE_BLOB: throw new IllegalArgumentException("Binary provider columns are not a bridge result");
                            default: value = cursor.getString(i);
                        }
                        row.put(cursor.getColumnName(i), value);
                    }
                    rows.put(row);
                } while (cursor.moveToNext());
            }
        }
        return new JSONObject().put(field, rows).put("next_offset", offset + rows.length()).put("has_more", more);
    }
    private ExtResult deleted(Uri base, Object id) throws JSONException {
        if (resolver.delete(item(base, id), null, null) == 0) throw new NoSuchElementException();
        return ExtResult.ok(new JSONObject().put("deleted", true));
    }
    private ExtResult present(Map<String,Object> p) throws JSONException {
        if (activity == null) return ExtResult.error("unavailable", "No foreground Activity");
        String mode = (String)p.getOrDefault("mode", p.containsKey("id") ? "edit" : "create");
        Intent intent;
        if (mode.equals("create")) {
            JSONObject data = object(p.getOrDefault("data", "{}"));
            intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI);
            if (data.has("title")) intent.putExtra(CalendarContract.Events.TITLE, data.getString("title"));
            if (data.has("start_ms")) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, integer(data.get("start_ms")));
            if (data.has("end_ms")) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, integer(data.get("end_ms")));
            if (data.has("all_day")) intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, data.getBoolean("all_day"));
            if (data.has("location")) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, data.getString("location"));
            if (data.has("notes")) intent.putExtra(CalendarContract.Events.DESCRIPTION, data.getString("notes"));
        } else intent = new Intent(mode.equals("view") ? Intent.ACTION_VIEW : Intent.ACTION_EDIT).setData(item(CalendarContract.Events.CONTENT_URI, p.get("id")));
        try { activity.startActivity(intent); return ExtResult.ok(new JSONObject().put("presented", true).put("save_confirmation", false)); }
        catch (ActivityNotFoundException ex) { return ExtResult.error("unavailable", "No installed calendar app handles this action"); }
    }
    private static JSONObject object(Object raw) throws JSONException { if (!(raw instanceof String)) throw new IllegalArgumentException("data must be a JSON object"); return new JSONObject((String)raw); }
    private static int offset(Map<String,Object> p) { return ((Number)p.getOrDefault("offset", 0L)).intValue(); }
    private static int limit(Map<String,Object> p) { return ((Number)p.getOrDefault("limit", 25L)).intValue(); }
    private static Uri item(Uri base, Object raw) { long id = integer(raw); if (id < 0) throw new IllegalArgumentException("Invalid item id"); return ContentUris.withAppendedId(base, id); }
    private static long integer(Object raw) {
        try {
            if (raw instanceof Float || raw instanceof Double) {
                double value = ((Number)raw).doubleValue();
                if (!Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > 9007199254740991d) throw new IllegalArgumentException("Expected an exact integer");
                return (long)value;
            }
            return raw instanceof Number ? ((Number)raw).longValue() : Long.parseLong(String.valueOf(raw));
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Expected an integer"); }
    }
    private static void copy(JSONObject data, ContentValues values, String source, String target) throws JSONException { if (data.has(source)) values.put(target, data.getString(source)); }
    private static ContentValues content(JSONObject data) throws JSONException {
        ContentValues out = new ContentValues(); Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("[A-Za-z][A-Za-z0-9_]*") || key.equals("_id") || key.startsWith("sync_data") || key.equals("dirty") || key.equals("deleted") || key.equals("_sync_id")) throw new IllegalArgumentException("Provider-owned or invalid column: " + key);
            Object value = data.get(key);
            if (value == JSONObject.NULL) out.putNull(key);
            else if (value instanceof String) out.put(key, (String)value);
            else if (value instanceof Boolean) out.put(key, (Boolean)value ? 1 : 0);
            else if (value instanceof Float || value instanceof Double) { double number=((Number)value).doubleValue(); if(!Double.isFinite(number))throw new IllegalArgumentException("Finite provider value required");out.put(key,number); }
            else if (value instanceof Number) out.put(key, ((Number)value).longValue());
            else throw new IllegalArgumentException("Provider values must be scalar");
        }
        return out;
    }
    private static String recurrence(JSONObject rule) throws JSONException {
        String[] frequency = {"DAILY", "WEEKLY", "MONTHLY", "YEARLY"}; int kind = rule.getInt("frequency"), interval = rule.getInt("interval");
        if (kind < 0 || kind > 3 || interval < 1) throw new IllegalArgumentException("Invalid recurrence frequency or interval");
        StringBuilder text = new StringBuilder("FREQ=").append(frequency[kind]).append(";INTERVAL=").append(interval);
        String[][] fields = {{"days_of_month","BYMONTHDAY"},{"months_of_year","BYMONTH"},{"weeks_of_year","BYWEEKNO"},{"days_of_year","BYYEARDAY"},{"set_positions","BYSETPOS"}};
        for (String[] field : fields) { JSONArray values = rule.optJSONArray(field[0]); if (values != null && values.length() > 0) { text.append(';').append(field[1]).append('='); for (int n=0;n<values.length();n++) { if (n>0) text.append(','); text.append(values.getInt(n)); } } }
        JSONArray days = rule.optJSONArray("days_of_week");
        if (days != null && days.length()>0) { String[] names = {"SU","MO","TU","WE","TH","FR","SA"}; text.append(";BYDAY="); for (int n=0;n<days.length();n++) { if(n>0)text.append(','); JSONObject day=days.getJSONObject(n); int value=day.getInt("day"); if(value<1||value>7)throw new IllegalArgumentException("Weekday must be1..7"); int week=day.optInt("week"); if(week!=0)text.append(week);text.append(names[value-1]); } }
        if (rule.optInt("end_count")>0) text.append(";COUNT=").append(rule.getInt("end_count"));
        else if(rule.has("end_ms")) { java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'",Locale.ROOT);format.setTimeZone(TimeZone.getTimeZone("UTC"));text.append(";UNTIL=").append(format.format(new Date(integer(rule.get("end_ms"))))); }
        return text.toString();
    }
}
