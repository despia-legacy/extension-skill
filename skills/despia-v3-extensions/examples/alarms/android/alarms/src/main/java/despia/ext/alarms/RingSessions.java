package despia.ext.alarms;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

final class RingSessions<Player> {
    private static final class Session<Player> {
        final Player player;
        boolean hasVibration;
        Session(Player player) { this.player = player; }
    }
    private final Map<String, Session<Player>> sessions = new LinkedHashMap<>();
    void add(String id, Player player) {
        if (sessions.containsKey(id)) throw new IllegalStateException("An audio session already owns this alarm");
        sessions.put(id, new Session<>(player));
    }
    boolean owns(String id, Player player) { Session<Player> session = sessions.get(id); return session != null && session.player == player; }
    boolean contains(String id) { return sessions.containsKey(id); }
    void prepared(String id, Player player, boolean hasVibration) {
        Session<Player> session = sessions.get(id);
        if (session != null && session.player == player) session.hasVibration = hasVibration;
    }
    Player remove(String id) { Session<Player> removed = sessions.remove(id); return removed == null ? null : removed.player; }
    boolean isEmpty() { return sessions.isEmpty(); }
    String firstId() { return sessions.keySet().iterator().next(); }
    boolean hasVibration() { for (Session<Player> session : sessions.values()) if (session.hasVibration) return true; return false; }
    List<Player> drain() {
        List<Player> released = new ArrayList<>();
        for (Session<Player> session : sessions.values()) released.add(session.player);
        sessions.clear(); return released;
    }
}
