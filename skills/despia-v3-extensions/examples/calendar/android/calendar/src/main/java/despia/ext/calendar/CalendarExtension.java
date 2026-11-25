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
            case "event_save": return saveSpan(p, object(p.get("data")));
            case "event_delete": return deleteSpan(p);
            case "android_table": return table(p);
            default: return ExtResult.error("not_registered", call.route);
        }
    }
    private static JSONObject normalizedCalendar(JSONObject row) throws JSONException {
        return new JSONObject().put("id", String.valueOf(row.getLong("_id")))
            .put("title", text(row, "calendar_displayName"))
            .put("writable", row.optInt("calendar_access_level") >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            .put("android", row);
    }
    private String[] eventColumns(boolean instances) {
        return new String[]{instances ? "event_id" : "_id", "calendar_id", "title", instances ? "begin" : "dtstart", instances ? "end" : "dtend", "allDay", "eventTimezone", "eventLocation", "description", "rrule", "rdate", "exrule", "exdate", "availability", "accessLevel"};
    }
    private JSONObject normalizedEvent(JSONObject row, boolean instances) throws JSONException {
        return new JSONObject().put("id", String.valueOf(row.getLong(instances ? "event_id" : "_id"))).put("calendar_id", String.valueOf(row.getLong("calendar_id"))).put("title", text(row, "title"))
            .put("start_ms", row.opt(instances ? "begin" : "dtstart")).put("end_ms", row.opt(instances ? "end" : "dtend")).put("all_day", row.optInt("allDay") != 0)
            .put("timezone", text(row, "eventTimezone")).put("location", text(row, "eventLocation")).put("notes", text(row, "description"))
            .put("android", row);
    }
    /** A provider text column; a NULL column is "" (JSONObject.optString would return the word null). */
    private static String text(JSONObject row, String key) { return row.isNull(key) ? "" : row.optString(key, ""); }
    private static final String[] SERIES = {"_id", "calendar_id", "title", "description", "eventLocation", "dtstart", "dtend", "duration", "allDay", "eventTimezone", "rrule", "rdate", "exdate", "original_id", "originalInstanceTime", "availability", "accessLevel", "_sync_id"};
    private JSONObject series(Object id) throws JSONException {
        JSONArray rows = query(item(CalendarContract.Events.CONTENT_URI, id), SERIES, null, null, null, 0, 1, "rows").getJSONArray("rows");
        if (rows.length() == 0) throw new NoSuchElementException();
        return rows.getJSONObject(0);
    }
    /** A series a sync adapter owns (it has a _sync_id). Every exception is written through CONTENT_EXCEPTION_URI, so
     *  the provider links it to its series by original_id on any calendar. Instance expansion removes the replaced
     *  occurrence only by sync id (AOSP CalendarInstancesHelper.performInstanceExpansion: instances are keyed by
     *  calendar id and _sync_id, exceptions found by original_sync_id), so for a local, never-synced series the
     *  replaced occurrence also goes into the series EXDATE, or it would show twice. */
    private static boolean synced(JSONObject row) { return !row.isNull("_sync_id") && !row.optString("_sync_id", "").isEmpty(); }
    /** The series EXDATE list with one more occurrence start (RFC 5545 3.8.5.1; a date for an all-day series). */
    private static String withExdate(JSONObject row, long at) {
        boolean allDay = row.optInt("allDay") != 0;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(allDay ? "yyyyMMdd" : "yyyyMMdd'T'HHmmss'Z'", Locale.ROOT); format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String old = row.isNull("exdate") ? "" : row.optString("exdate", "");
        return (old.isEmpty() ? "" : old + ",") + format.format(new Date(at));
    }
    /** An occurrence already detached from its series: an exception row, which Instances (and so the events route)
     *  returns under its own id. */
    static boolean detached(JSONObject row) { return !row.isNull("original_id") && !row.optString("original_id", "").isEmpty(); }
    /** The series a future span acts on, and the occurrence start it acts from. For a detached occurrence that is its
     *  series from the slot it replaced (originalInstanceTime), as EventKit's futureEvents does for a detached EKEvent;
     *  otherwise the row itself from occurrence_ms, or from its start. Returns {series id, occurrence start}. */
    static long[] futureFrom(JSONObject row, Long occurrence) throws JSONException {
        if (detached(row)) return new long[]{Long.parseLong(String.valueOf(row.get("original_id"))), row.getLong("originalInstanceTime")};
        return new long[]{row.getLong("_id"), occurrence == null ? row.getLong("dtstart") : occurrence};
    }
    /** An update of a series row that carries its unchanged DTSTART, RRULE and RDATE. The provider recomputes the
     *  series instances only when the update's own values hold DTSTART and the recurrence (AOSP CalendarProvider2
     *  handleUpdateEvents passes the changed values to CalendarInstancesHelper.updateInstancesLocked, which returns
     *  early without DTSTART), so an RRULE or EXDATE change alone left the old occurrences in Instances. */
    private ContentProviderOperation.Builder seriesUpdate(String id, JSONObject row) throws JSONException {
        ContentProviderOperation.Builder update = ContentProviderOperation.newUpdate(item(CalendarContract.Events.CONTENT_URI, id)).withValue("dtstart", row.getLong("dtstart"));
        if (!row.isNull("rrule")) update.withValue("rrule", row.getString("rrule"));
        if (!row.isNull("rdate")) update.withValue("rdate", row.getString("rdate"));
        return update;
    }
    /** Puts a local series' exceptions back into Instances, appended to {@code operations} after the series write. For a
     *  series with no sync id the provider re-expands only the row it just wrote: it deletes the instances of the series
     *  and of every exception (by _id or original_id), then expands that one row, so a series update drops its
     *  exceptions' instances and an exception insert drops the series' instances (AOSP CalendarInstancesHelper
     *  updateRecurrenceInstancesLocked and getRelevantRecurrenceEntries, read 24 November 2026). An update that carries
     *  DTSTART and DTEND but not ORIGINAL_ID takes the provider's single-event path and inserts that exception's instance
     *  again. Exceptions replacing a slot at or after {@code before} are skipped: the same batch deletes them. */
    private void refreshExceptions(String seriesId, Long before, ArrayList<ContentProviderOperation> operations) throws JSONException {
        JSONArray rows = query(CalendarContract.Events.CONTENT_URI, new String[]{"_id", "dtstart", "dtend", "duration", "allDay", "originalInstanceTime", "eventStatus"},
            "original_id = ? AND deleted = 0", new String[]{seriesId}, null, 0, 1000, "rows").getJSONArray("rows");
        for (int n = 0; n < rows.length(); n++) {
            JSONObject r = rows.getJSONObject(n);
            if (before != null && !r.isNull("originalInstanceTime") && r.getLong("originalInstanceTime") >= before) continue;
            if (!r.isNull("eventStatus") && r.optInt("eventStatus") == CalendarContract.Events.STATUS_CANCELED) continue;
            long start = r.getLong("dtstart");
            operations.add(ContentProviderOperation.newUpdate(item(CalendarContract.Events.CONTENT_URI, r.getString("_id")))
                .withValue("dtstart", start).withValue("dtend", start + durationMs(r)).withValue("allDay", r.optInt("allDay")).build());
        }
    }
    private static boolean recurring(JSONObject row) { return !row.optString("rrule", "").isEmpty() && !row.isNull("rrule") || !row.optString("rdate", "").isEmpty() && !row.isNull("rdate"); }
    /** The occurrence start a span acts from: occurrence_ms (it must be an instance of this event), else the series start. */
    private long occurrence(Map<String,Object> p, JSONObject row) throws JSONException {
        long start = row.getLong("dtstart");
        if (!p.containsKey("occurrence_ms")) return start;
        long at = integer(p.get("occurrence_ms"));
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(builder, at); ContentUris.appendId(builder, at + 1);
        JSONArray hits = query(builder.build(), new String[]{"event_id", "begin"}, "event_id = ? AND begin = ?", new String[]{String.valueOf(row.getLong("_id")), String.valueOf(at)}, null, 0, 1, "rows").getJSONArray("rows");
        if (hits.length() == 0) throw new NoSuchElementException();
        return at;
    }
    private static String until(String rrule, long occurrence, boolean allDay) {
        // End the series before the occurrence: drop COUNT and UNTIL, then add UNTIL (RFC 5545 3.3.10; a date for all-day series).
        StringBuilder out = new StringBuilder();
        for (String part : rrule.split(";")) { String key = part.split("=", 2)[0].trim().toUpperCase(Locale.ROOT); if (key.equals("COUNT") || key.equals("UNTIL") || part.isEmpty()) continue; if (out.length() > 0) out.append(';'); out.append(part); }
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(allDay ? "yyyyMMdd" : "yyyyMMdd'T'HHmmss'Z'", Locale.ROOT); format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return out.append(";UNTIL=").append(format.format(new Date(occurrence - (allDay ? 86400000L : 1000L)))).toString();
    }
    /** Occurrences of the series that start before {@code before}, to carry a COUNT over a split. */
    private int countBefore(JSONObject row, long before) throws Exception {
        boolean local = !synced(row);
        if (local) {
            // The series' own instances may be missing after an exception write (see refreshExceptions): expand it again.
            ArrayList<ContentProviderOperation> refresh = new ArrayList<>();
            String id = String.valueOf(row.getLong("_id"));
            refresh.add(seriesUpdate(id, row).withExpectedCount(1).build());
            refreshExceptions(id, null, refresh);
            resolver.applyBatch(CalendarContract.AUTHORITY, refresh);
        }
        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(builder, row.getLong("dtstart")); ContentUris.appendId(builder, before);
        int total = 0, offset = 0;
        while (true) {
            JSONObject page = query(builder.build(), new String[]{"begin"}, "event_id = ? AND begin < ?", new String[]{String.valueOf(row.getLong("_id")), String.valueOf(before)}, "begin ASC", offset, 500, "rows");
            total += page.getJSONArray("rows").length(); offset += page.getJSONArray("rows").length();
            if (!page.getBoolean("has_more")) break;
        }
        // RFC 5545 COUNT counts the occurrences the rule generates before EXDATE removes any, and an exception
        // replaces its occurrence in the series' instances, so both are added back.
        for (long excluded : exdates(row)) if (excluded >= row.getLong("dtstart") && excluded < before) total++;
        java.util.List<Long> excluded = exdates(row);
        JSONArray detached = query(CalendarContract.Events.CONTENT_URI, new String[]{"_id", "originalInstanceTime"}, "original_id = ? AND originalInstanceTime < ?", new String[]{String.valueOf(row.getLong("_id")), String.valueOf(before)}, null, 0, 1000, "rows").getJSONArray("rows");
        // A synced exception cancels its slot in the series' instances. A local one does not (its slot is in EXDATE when
        // this extension wrote it, otherwise the series instance is still there), so it is never added again.
        if (!local) for (int n = 0; n < detached.length(); n++) if (!excluded.contains(detached.getJSONObject(n).getLong("originalInstanceTime"))) total++;
        return total;
    }
    /** The series EXDATE entries as Unix ms: "yyyyMMdd'T'HHmmss'Z'", a local time with an optional TZID, or a date. */
    private static java.util.List<Long> exdates(JSONObject row) {
        java.util.List<Long> out = new ArrayList<>();
        String all = row.isNull("exdate") ? "" : row.optString("exdate", "");
        if (all.isEmpty()) return out;
        String zone = row.isNull("eventTimezone") ? "UTC" : row.optString("eventTimezone", "UTC");
        for (String line : all.split("\\n")) {
            String values = line;
            int semi = line.lastIndexOf(';');
            if (semi >= 0) { java.util.regex.Matcher tz = java.util.regex.Pattern.compile("TZID=([^;:]+)").matcher(line); if (tz.find()) zone = tz.group(1); values = line.substring(semi + 1); }
            for (String v : values.split(",")) {
                v = v.trim(); if (v.isEmpty()) continue;
                try {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(v.length() == 8 ? "yyyyMMdd" : "yyyyMMdd'T'HHmmss", Locale.ROOT);
                    f.setTimeZone(TimeZone.getTimeZone(v.endsWith("Z") || v.length() == 8 ? "UTC" : zone));
                    out.add(f.parse(v.endsWith("Z") ? v.substring(0, v.length() - 1) : v).getTime());
                } catch (java.text.ParseException ignored) { }
            }
        }
        return out;
    }
    private static long durationMs(JSONObject row) {
        if (!row.isNull("dtend") && row.optLong("dtend", 0) > 0) return row.optLong("dtend") - row.optLong("dtstart");
        String d = row.optString("duration", "");
        // The provider writes RFC 2445 durations without the T for a time part ("P1800S"), so T is optional.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^P(?:(\\d+)W)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").matcher(d);
        if (!m.matches()) throw new IllegalArgumentException("Unreadable event duration");
        long[] unit = {604800000L, 86400000L, 3600000L, 60000L, 1000L}; long total = 0;
        for (int n = 0; n < 5; n++) if (m.group(n + 1) != null) total += Long.parseLong(m.group(n + 1)) * unit[n];
        return total;
    }
    private ExtResult saveSpan(Map<String,Object> p, JSONObject data) throws Exception {
        String id = data.optString("id", "");
        if (id.isEmpty()) return saveEvent(data);
        JSONObject row = series(id);
        if ("future".equals(p.get("span")) && detached(row)) {
            if (p.containsKey("occurrence_ms")) occurrence(p, row);   // it must be this detached occurrence
            long[] target = futureFrom(row, null);
            JSONObject changed = row;   // the occurrence's own values are what the new series starts from
            id = String.valueOf(target[0]); row = series(id);
            data = new JSONObject(data.toString()).put("id", id);
            return saveFuture(p, data, row, id, target[1], changed);
        }
        if (!recurring(row)) return saveEvent(data);
        long at = occurrence(p, row);
        if ("future".equals(p.get("span"))) return saveFuture(p, data, row, id, at, row);
        return saveThis(data, row, id, at);
    }
    /** The values a future span writes: the caller's changes over the occurrence's own values. For an occurrence
     *  still inside its series that is the series fields from {@code at}; for a detached (edited) occurrence it is
     *  that row's start, length, title, notes, location, time zone and all-day, as EventKit's futureEvents keeps
     *  the edited occurrence's values for the events after it. */
    static JSONObject futureValues(JSONObject data, JSONObject occurrence, long at) throws JSONException {
        JSONObject next = new JSONObject(data.toString());
        String[][] carry = {{"title", "title"}, {"notes", "description"}, {"location", "eventLocation"}, {"timezone", "eventTimezone"}};
        for (String[] c : carry) if (!next.has(c[0]) && !occurrence.isNull(c[1])) next.put(c[0], occurrence.getString(c[1]));
        if (!next.has("all_day")) next.put("all_day", occurrence.optInt("allDay") != 0);
        if (!next.has("start_ms")) next.put("start_ms", detached(occurrence) ? occurrence.getLong("dtstart") : at);
        if (!next.has("end_ms")) next.put("end_ms", integer(next.get("start_ms")) + durationMs(occurrence));
        return next;
    }
    private ExtResult saveFuture(Map<String,Object> p, JSONObject data, JSONObject row, String id, long at, JSONObject occurrence) throws Exception {
        {
            boolean whole = at <= row.getLong("dtstart");
            if (whole && !detached(occurrence)) return saveEvent(data);
            // Split: the original series ends before the occurrence; a new series starts there with the changes.
            // From the first occurrence nothing is left before it, so the series itself takes the changes.
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();
            boolean allDay = row.optInt("allDay") != 0;
            String rule = row.optString("rrule", "");
            if (!whole) {
                operations.add(seriesUpdate(id, row).withValue("rrule", until(rule, at, allDay)).withExpectedCount(1).build());
                if (!synced(row)) refreshExceptions(id, at, operations);
            }
            java.util.Set<Long> replaced = new java.util.HashSet<>();
            JSONArray later = query(CalendarContract.Events.CONTENT_URI, new String[]{"originalInstanceTime"}, "original_id = ? AND originalInstanceTime >= ?", new String[]{id, String.valueOf(at)}, null, 0, 1000, "rows").getJSONArray("rows");
            for (int n = 0; n < later.length(); n++) replaced.add(later.getJSONObject(n).getLong("originalInstanceTime"));
            JSONArray oldAlarms = query(CalendarContract.Reminders.CONTENT_URI, new String[]{"minutes"}, "event_id = ?", new String[]{String.valueOf(occurrence.getLong("_id"))}, "_id ASC", 0, 100, "rows").getJSONArray("rows");
            operations.add(ContentProviderOperation.newDelete(CalendarContract.Events.CONTENT_URI).withSelection("original_id = ? AND originalInstanceTime >= ?", new String[]{id, String.valueOf(at)}).build());
            JSONObject next = futureValues(data, occurrence, at);
            if (whole) next.put("id", id);
            else { next.remove("id"); if (!next.has("calendar_id")) next.put("calendar_id", row.getLong("calendar_id")); }
            if (!next.has("recurrence")) {
                String carried = rule;
                java.util.regex.Matcher count = java.util.regex.Pattern.compile("(?i)(^|;)COUNT=(\\d+)").matcher(rule);
                if (count.find()) {
                    int left = Integer.parseInt(count.group(2)) - (whole ? 0 : countBefore(row, at));
                    if (left < 1) throw new NoSuchElementException();
                    carried = rule.replaceFirst("(?i)(^|;)COUNT=\\d+", "$1COUNT=" + left);
                }
                JSONObject android = next.optJSONObject("android"); if (android == null) android = new JSONObject();
                android.put("rrule", carried);
                StringBuilder kept = new StringBuilder();
                java.text.SimpleDateFormat utc = new java.text.SimpleDateFormat(allDay ? "yyyyMMdd" : "yyyyMMdd'T'HHmmss'Z'", Locale.ROOT); utc.setTimeZone(TimeZone.getTimeZone("UTC"));
                for (long excluded : exdates(row)) if (excluded >= at && !replaced.contains(excluded)) kept.append(kept.length() > 0 ? "," : "").append(utc.format(new Date(excluded)));
                if (kept.length() > 0 || whole) android.put("exdate", kept.toString());   // the series keeps only the gaps no exception filled
                next.put("android", android);
            }
            if (!next.has("alarms")) {
                JSONArray copied = new JSONArray();
                for (int n = 0; n < oldAlarms.length(); n++) copied.put(new JSONObject().put("minutes", oldAlarms.getJSONObject(n).getLong("minutes")));
                next.put("alarms", copied);
            }
            int first = operations.size();
            eventOperations(next, operations);
            ContentProviderResult[] result = resolver.applyBatch(CalendarContract.AUTHORITY, operations);
            if (whole) return ExtResult.ok(new JSONObject().put("id", id).put("saved", true).put("series_id", id));
            if (result[first].uri == null) throw new IllegalStateException();
            return ExtResult.ok(new JSONObject().put("id", result[first].uri.getLastPathSegment()).put("saved", true).put("series_id", id).put("split_ms", at));
        }
    }
    private ExtResult saveThis(JSONObject data, JSONObject row, String id, long at) throws Exception {
        // "this": one occurrence becomes an exception of the series (the provider copies the series fields).
        if (data.has("recurrence")) throw new IllegalArgumentException("One occurrence cannot carry a recurrence");
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, at);
        copy(data, values, "title", "title"); copy(data, values, "notes", "description"); copy(data, values, "location", "eventLocation");
        long start = data.has("start_ms") ? integer(data.get("start_ms")) : at;
        long end = data.has("end_ms") ? integer(data.get("end_ms")) : start + durationMs(row);
        if (end <= start) throw new IllegalArgumentException("end_ms must be after start_ms");
        // An exception takes DTSTART and DURATION; the provider computes DTEND and refuses a DTEND value.
        values.put("dtstart", start); values.put("duration", "P" + ((end - start) / 1000) + "S");
        if (data.has("all_day")) values.put("allDay", data.getBoolean("all_day") ? 1 : 0);
        if (data.has("timezone")) { String zone = data.getString("timezone"); if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(zone)) throw new IllegalArgumentException("Unknown timezone"); values.put("eventTimezone", zone); }
        JSONObject android = data.optJSONObject("android"); if (android != null) values.putAll(content(android));
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        int insert = operations.size();
        operations.add(ContentProviderOperation.newInsert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, Long.parseLong(id))).withValues(values).build());
        JSONArray alarms = data.optJSONArray("alarms");
        // The provider copies the series reminders to the exception; given alarms replace them.
        if (alarms != null) operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id = ?", new String[]{""}).withSelectionBackReference(0, insert).build());
        if (alarms != null) for (int n = 0; n < alarms.length(); n++) {
            long minutes = integer(alarms.getJSONObject(n).get("minutes")); if (minutes < 0) throw new IllegalArgumentException("Android reminders must be before the event");
            operations.add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("minutes", minutes).withValue("method", CalendarContract.Reminders.METHOD_ALERT).withValueBackReference("event_id", insert).build());
        }
        if (!synced(row)) {
            // Local: the replaced slot goes into EXDATE, then the series and every exception, this one too, are expanded again.
            operations.add(seriesUpdate(id, row).withValue("exdate", withExdate(row, at)).withExpectedCount(1).build());
            refreshExceptions(id, null, operations);
            operations.add(ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI).withSelection("_id = ?", new String[]{""}).withSelectionBackReference(0, insert)
                .withValue("dtstart", start).withValue("dtend", end).withValue("allDay", values.containsKey("allDay") ? values.getAsInteger("allDay") : row.optInt("allDay")).build());
        }
        ContentProviderResult[] result = resolver.applyBatch(CalendarContract.AUTHORITY, operations);
        if (result[insert].uri == null) throw new IllegalStateException();
        return ExtResult.ok(new JSONObject().put("id", result[insert].uri.getLastPathSegment()).put("saved", true).put("series_id", id).put("occurrence_ms", at));
    }
    private ExtResult deleteSpan(Map<String,Object> p) throws Exception {
        JSONObject row = series(p.get("id"));
        String id = String.valueOf(row.getLong("_id"));
        long at;
        if (detached(row)) {
            if (p.containsKey("occurrence_ms")) occurrence(p, row);   // it must be this detached occurrence
            if (!"future".equals(p.get("span"))) {
                // Synced: cancel the detached occurrence so the series instance does not come back. Local: the replaced
                // slot is already in the series EXDATE, and expansion ignores the status of a local exception, so delete it.
                if (!synced(series(String.valueOf(row.get("original_id"))))) return deleted(CalendarContract.Events.CONTENT_URI, id);
                int changed = resolver.update(item(CalendarContract.Events.CONTENT_URI, id), statusCanceled(), null, null);
                if (changed == 0) throw new NoSuchElementException();
                return ExtResult.ok(new JSONObject().put("deleted", true));
            }
            // Future: the series ends before the slot this occurrence replaced, and its later exceptions (this one too) go.
            long[] target = futureFrom(row, null);
            id = String.valueOf(target[0]); row = series(id); at = target[1];
        } else {
            if (!recurring(row)) return deleted(CalendarContract.Events.CONTENT_URI, id);
            at = occurrence(p, row);
        }
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        if ("future".equals(p.get("span"))) {
            if (at <= row.getLong("dtstart")) {
                operations.add(ContentProviderOperation.newDelete(CalendarContract.Events.CONTENT_URI).withSelection("original_id = ?", new String[]{id}).build());
                operations.add(ContentProviderOperation.newDelete(item(CalendarContract.Events.CONTENT_URI, id)).withExpectedCount(1).build());
            } else {
                operations.add(seriesUpdate(id, row).withValue("rrule", until(row.optString("rrule", ""), at, row.optInt("allDay") != 0)).withExpectedCount(1).build());
                operations.add(ContentProviderOperation.newDelete(CalendarContract.Events.CONTENT_URI).withSelection("original_id = ? AND originalInstanceTime >= ?", new String[]{id, String.valueOf(at)}).build());
                if (!synced(row)) refreshExceptions(id, at, operations);
            }
        } else if (synced(row)) {
            operations.add(ContentProviderOperation.newInsert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, Long.parseLong(id)))
                .withValue(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, at).withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED).build());
        } else {
            operations.add(seriesUpdate(id, row).withValue("exdate", withExdate(row, at)).withExpectedCount(1).build());
            refreshExceptions(id, null, operations);
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, operations);
        return ExtResult.ok(new JSONObject().put("deleted", true));
    }
    private static ContentValues statusCanceled() { ContentValues v = new ContentValues(); v.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED); return v; }
    private ExtResult saveEvent(JSONObject data) throws Exception {
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        String id = data.optString("id", "");
        eventOperations(data, operations);
        if (!id.isEmpty() && (data.has("start_ms") || data.has("recurrence"))) {
            // A local series written with its start is expanded again alone (see refreshExceptions).
            JSONObject row = series(id);
            if (recurring(row) && !synced(row)) refreshExceptions(id, null, operations);
        }
        ContentProviderResult[] result = resolver.applyBatch(CalendarContract.AUTHORITY, operations);
        if (id.isEmpty()) { if (result[0].uri == null) throw new IllegalStateException(); id = result[0].uri.getLastPathSegment(); }
        return ExtResult.ok(new JSONObject().put("id", id).put("saved", true));
    }
    /** The provider operations that insert or update one event row and replace its alarms, appended to {@code operations}. */
    private void eventOperations(JSONObject data, ArrayList<ContentProviderOperation> operations) throws Exception {
        int base = operations.size();
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
        if (id.isEmpty()) operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build());
        else operations.add(ContentProviderOperation.newUpdate(item(CalendarContract.Events.CONTENT_URI, id)).withValues(values).withExpectedCount(1).build());
        JSONArray alarms = data.optJSONArray("alarms");
        if (alarms != null) {
            if (!id.isEmpty()) operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id = ?", new String[]{id}).build());
            for (int n=0;n<alarms.length();n++) {
                JSONObject alarm = alarms.getJSONObject(n); long minutes = integer(alarm.get("minutes")); if (minutes < 0) throw new IllegalArgumentException("Android reminders must be before the event");
                ContentProviderOperation.Builder insert = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("minutes", minutes).withValue("method", CalendarContract.Reminders.METHOD_ALERT);
                if (id.isEmpty()) insert.withValueBackReference("event_id", base); else insert.withValue("event_id", Long.parseLong(id));
                operations.add(insert.build());
            }
        }
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
