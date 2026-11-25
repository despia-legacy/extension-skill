package despia.ext.applock;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import org.json.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Android usage thresholds: the group's apps and categories, measured with UsageStatsManager while a schedule's
 *  window is open. Usage access (PACKAGE_USAGE_STATS) is granted by the user on the system Usage access screen
 *  (Settings.ACTION_USAGE_ACCESS_SETTINGS); the app reads the grant through AppOpsManager OPSTR_GET_USAGE_STATS
 *  (developer.android.com/reference/android/app/usage/UsageStatsManager, read 24 November 2026). */
final class Thresholds {
    private Thresholds() {}
    private static final long MIN_INTERVAL_MS = 30000, LOOKBACK_MS = 6 * 3600000L;
    private static long lastRun;

    @SuppressWarnings("deprecation")
    static boolean granted(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class); if (ops == null) return false;
        int mode = Build.VERSION.SDK_INT >= 29 ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
            : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) return context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
        return mode == AppOpsManager.MODE_ALLOWED;
    }
    static Intent settingsIntent() { return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }

    /** Marks every enabled threshold schedule whose selected usage inside its open window reached threshold_minutes,
     *  once per window, and records a threshold_reached event. Runs at most every 30 seconds unless {@code force}.
     *  Answers whether a schedule was marked. Without usage access nothing is measured and nothing is marked. */
    static synchronized boolean update(Context context, boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && now - lastRun < MIN_INTERVAL_MS) return false;
        lastRun = now;
        if (!granted(context)) return false;
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class); if (usage == null) return false;
        LockStore store = new LockStore(context); JSONObject state = store.read(); Instant instant = Instant.ofEpochMilli(now);
        Map<String, Long> reached = new HashMap<>(); List<Object[]> open = new ArrayList<>(); long earliest = now;
        JSONArray schedules = state.getJSONArray("schedules");
        for (int index = 0; index < schedules.length(); index++) {
            JSONObject schedule = schedules.getJSONObject(index);
            if (!schedule.optBoolean("enabled", true) || !LockState.hasThreshold(schedule) || LockState.thresholdReached(schedule, instant)) continue;
            Long start = LockState.intervalStart(schedule, instant); if (start == null) continue;
            JSONObject group; try { group = LockState.group(state, schedule.getString("group_id")); } catch (NoSuchElementException missing) { continue; }
            long from = Math.min(now, UsageThreshold.countFrom(start, schedule.optLong("monitoring_since_ms", start), schedule.optBoolean("includes_past_activity")));
            open.add(new Object[]{schedule, group, start, from}); earliest = Math.min(earliest, from);
        }
        if (open.isEmpty()) return false;
        // Resumed events before the counting start tell which app was already in the foreground then.
        List<UsageThreshold.Event> events = events(usage, earliest - LOOKBACK_MS, now);
        for (Object[] entry : open) {
            JSONObject schedule = (JSONObject)entry[0];
            if (UsageThreshold.foregroundMs(events, selection(context, (JSONObject)entry[1]), (Long)entry[3], now) >= schedule.getLong("threshold_minutes") * 60000L) reached.put(schedule.getString("id"), (Long)entry[2]);
        }
        if (reached.isEmpty()) return false;
        store.change(current -> {
            JSONArray rows = current.getJSONArray("schedules");
            for (int index = 0; index < rows.length(); index++) {
                JSONObject schedule = rows.getJSONObject(index); Long start = reached.get(schedule.getString("id"));
                if (start == null || !schedule.optBoolean("enabled", true) || !LockState.hasThreshold(schedule)) continue;
                schedule.put("threshold_interval_ms", start); LockState.record(current, "threshold_reached", schedule.getString("group_id"), schedule.getString("id"));
            }
            return null;
        });
        return true;
    }
    private static List<UsageThreshold.Event> events(UsageStatsManager usage, long from, long to) {
        List<UsageThreshold.Event> list = new ArrayList<>(); UsageEvents events = usage.queryEvents(from, to); UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) { events.getNextEvent(event); list.add(new UsageThreshold.Event(event.getTimeStamp(), event.getPackageName(), event.getClassName(), event.getEventType())); }
        return list;
    }
    private static Predicate<String> selection(Context context, JSONObject group) throws JSONException {
        Set<String> packages = new HashSet<>(); Set<Integer> categories = new HashSet<>(); JSONObject android = group.optJSONObject("android");
        if (android != null) {
            JSONArray names = android.optJSONArray("packages"), ids = android.optJSONArray("categories");
            if (names != null) for (int index = 0; index < names.length(); index++) packages.add(names.getString(index));
            if (ids != null) for (int index = 0; index < ids.length(); index++) categories.add(ids.getInt(index));
        }
        PackageManager manager = context.getPackageManager(); Map<String, Boolean> known = new HashMap<>();
        return name -> packages.contains(name) || (!categories.isEmpty() && Build.VERSION.SDK_INT >= 26 && known.computeIfAbsent(name, unknown -> {
            try { return categories.contains(manager.getApplicationInfo(unknown, 0).category); }
            catch (PackageManager.NameNotFoundException invisible) { return false; }
        }));
    }
    /** Whether the group selects anything Android usage statistics can count: they are kept per app, so a threshold
     *  counts the group's Android apps and categories. */
    static boolean countable(JSONObject group) {
        JSONObject android = group.optJSONObject("android"); if (android == null) return false;
        JSONArray packages = android.optJSONArray("packages"), categories = android.optJSONArray("categories");
        return packages != null && packages.length() > 0 || categories != null && categories.length() > 0;
    }
}
