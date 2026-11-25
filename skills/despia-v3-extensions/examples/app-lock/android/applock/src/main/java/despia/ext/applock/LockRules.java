package despia.ext.applock;

import java.time.*;
import java.util.*;

public final class LockRules {
    private LockRules() {}
    public static String identifier(String id) {
        if (id.isEmpty() || id.codePointCount(0, id.length()) > 128) throw new IllegalArgumentException("Identifiers must contain 1 to 128 Unicode code points");
        return id;
    }
    public static String domain(String input) {
        String domain = input.trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        String[] labels = domain.split("\\.", -1);
        if (domain.length() > 253 || labels.length < 2) throw new IllegalArgumentException("Use a DNS hostname without a scheme, path, port or wildcard");
        for (String label : labels) if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw new IllegalArgumentException("Invalid hostname; encode international names as punycode");
        return domain;
    }
    public static boolean matchesDomain(String hostname, Collection<String> blocked) {
        String normalized = hostname.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        for (String parent : blocked) if (normalized.equals(parent) || normalized.endsWith("." + parent)) return true;
        return false;
    }
    public static boolean inWindow(Instant instant, ZoneId zone, Set<Integer> weekdays, int startHour, int startMinute, int endHour, int endMinute) {
        if (weekdays.isEmpty() || !weekdays.stream().allMatch(day -> day >= 1 && day <= 7) || startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23 || startMinute < 0 || startMinute > 59 || endMinute < 0 || endMinute > 59) throw new IllegalArgumentException("Invalid local-time schedule");
        int start = startHour * 60 + startMinute, end = endHour * 60 + endMinute;
        if (start == end) throw new IllegalArgumentException("A schedule must have different start and end times");
        ZonedDateTime local = instant.atZone(zone);
        int minute = local.getHour() * 60 + local.getMinute(), day = local.getDayOfWeek().getValue() % 7 + 1;
        if (end > start) return weekdays.contains(day) && minute >= start && minute < end;
        int yesterday = day == 1 ? 7 : day - 1;
        return weekdays.contains(day) && minute >= start || weekdays.contains(yesterday) && minute < end;
    }
}
