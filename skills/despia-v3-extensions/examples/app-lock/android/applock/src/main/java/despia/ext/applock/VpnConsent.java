package despia.ext.applock;

final class VpnConsent {
    private long generation, launchedGeneration;
    private boolean pending, outstanding;
    synchronized long begin() {
        if (pending || outstanding) throw new IllegalStateException("A VPN request or earlier system consent result is pending");
        pending = true; return ++generation;
    }
    synchronized boolean isPending() { return pending; }
    synchronized boolean isCurrent(long candidate) { return generation == candidate; }
    synchronized long generation() { return generation; }
    synchronized void launched(long candidate) {
        if (!isCurrent(candidate)) throw new IllegalStateException("VPN request was cancelled");
        launchedGeneration = candidate; outstanding = true;
    }
    synchronized boolean returned() {
        if (!outstanding) return false;
        outstanding = false;
        if (launchedGeneration != generation) return false;
        pending = false; return true;
    }
    synchronized boolean grantedWithoutPrompt(long candidate) {
        if (!isCurrent(candidate)) return false;
        pending = false; return true;
    }
    synchronized void cancel() { generation++; pending = false; }
    synchronized void hostDestroyed() { if (!outstanding) cancel(); }
    synchronized void hostFinished() { cancel(); outstanding = false; }
    synchronized void launchFailed(long candidate) { if (isCurrent(candidate)) { cancel(); outstanding = false; } }
}
