package despia.ext.applock;

import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** The parts of an Android usage threshold that need no Android API, so they run in JVM unit tests.
 *  Android reports usage per app through UsageStatsManager.queryEvents, as activity resumed, paused and stopped
 *  events (developer.android.com/reference/android/app/usage/UsageEvents.Event, read 24 November 2026). */
public final class UsageThreshold {
    private UsageThreshold() {}
    public static final int RESUMED = 1, PAUSED = 2, STOPPED = 23;

    /** One usage event: time in Unix milliseconds, package, activity class and UsageEvents.Event type. */
    public static final class Event {
        final long time; final String packageName, className; final int type;
        public Event(long time, String packageName, String className, int type) { this.time = time; this.packageName = packageName; this.className = className; this.type = type; }
    }

    /** Foreground milliseconds inside [from, to) of the packages {@code selected} accepts. A package counts while
     *  any of its activities is resumed, so two activities of one app never count twice. An activity still resumed
     *  at {@code to} counts until {@code to}. Events must be in time order, as queryEvents returns them. */
    public static long foregroundMs(List<Event> events, Predicate<String> selected, long from, long to) {
        Map<String, Set<String>> open = new HashMap<>(); Map<String, Long> since = new HashMap<>(); long total = 0;
        for (Event event : events) {
            if (event.time >= to) break;
            if (event.packageName == null || !selected.test(event.packageName)) continue;
            Set<String> activities = open.computeIfAbsent(event.packageName, name -> new HashSet<>());
            String activity = event.className == null ? "" : event.className;
            if (event.type == RESUMED) { if (activities.isEmpty()) since.put(event.packageName, event.time); activities.add(activity); }
            else if (event.type == PAUSED || event.type == STOPPED) {
                if (activities.remove(activity) && activities.isEmpty()) total += overlap(since.remove(event.packageName), event.time, from, to);
            }
        }
        for (Map.Entry<String, Long> still : since.entrySet()) total += overlap(still.getValue(), to, from, to);
        return total;
    }
    private static long overlap(long start, long end, long from, long to) { return Math.max(0, Math.min(end, to) - Math.max(start, from)); }

    /** The start of the schedule's window that contains {@code now}, in Unix milliseconds, or null outside every window.
     *  A window that crosses midnight belongs to the weekday it starts on, as LockRules.inWindow reads it. */
    public static Long intervalStart(ZoneId zone, Set<Integer> weekdays, int startHour, int startMinute, int endHour, int endMinute, Instant now) {
        if (!LockRules.inWindow(now, zone, weekdays, startHour, startMinute, endHour, endMinute)) return null;
        ZonedDateTime local = now.atZone(zone); int minute = local.getHour() * 60 + local.getMinute(), start = startHour * 60 + startMinute;
        LocalDate day = minute >= start ? local.toLocalDate() : local.toLocalDate().minusDays(1);
        return day.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli();
    }

    /** Where counting starts: the window start, or, without includes_past_activity, the later of the window start and
     *  the time monitoring began (the schedule's save), as DeviceActivityEvent's includesPastActivity reads it. */
    public static long countFrom(long intervalStart, long monitoringSince, boolean includesPastActivity) {
        return includesPastActivity ? intervalStart : Math.max(intervalStart, monitoringSince);
    }
}
