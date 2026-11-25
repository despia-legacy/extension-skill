package despia.ext.alarms;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RingSessionsTest {
    @Test public void stoppingOneAlarmKeepsTheOtherPlayer() {
        RingSessions<Object> sessions = new RingSessions<>(); Object first = new Object(), second = new Object();
        sessions.add("first", first); sessions.add("second", second);
        sessions.prepared("first", first, true); sessions.prepared("second", second, false);
        assertSame(first, sessions.remove("first"));
        assertTrue(sessions.owns("second", second)); assertFalse(sessions.isEmpty());
        assertEquals("second", sessions.firstId()); assertFalse(sessions.hasVibration());
    }
    @Test public void preparationFailureLeavesNoReleasedPlayerOwned() {
        RingSessions<Object> sessions = new RingSessions<>(); Object first = new Object(), failed = new Object();
        sessions.add("first", first); sessions.prepared("first", first, true); sessions.add("failed", failed);
        assertSame(failed, sessions.remove("failed"));
        assertFalse(sessions.owns("failed", failed)); assertTrue(sessions.owns("first", first)); assertTrue(sessions.hasVibration());
    }
    @Test public void stalePreparedCallbackCannotAffectReplacement() {
        RingSessions<Object> sessions = new RingSessions<>(); Object previous = new Object(), replacement = new Object();
        sessions.add("alarm", previous); sessions.remove("alarm"); sessions.add("alarm", replacement);
        sessions.prepared("alarm", previous, true);
        assertFalse(sessions.owns("alarm", previous)); assertFalse(sessions.hasVibration());
        sessions.prepared("alarm", replacement, true); assertTrue(sessions.hasVibration());
    }
    @Test public void drainReleasesEachRemainingSessionOnce() {
        RingSessions<Object> sessions = new RingSessions<>(); Object first = new Object(), second = new Object();
        sessions.add("first", first); sessions.add("second", second);
        java.util.List<Object> released = sessions.drain();
        assertEquals(2, released.size()); assertTrue(released.contains(first)); assertTrue(released.contains(second));
        assertTrue(sessions.isEmpty()); assertTrue(sessions.drain().isEmpty());
    }
}
