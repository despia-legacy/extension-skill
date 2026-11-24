package despia.ext.applock;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VpnConsentTest {
    @Test public void recreatedHostReleasesAnActivityOwnedDisclosure() {
        VpnConsent requests = new VpnConsent(); long previous = requests.begin(); requests.hostDestroyed();
        assertFalse(requests.isPending()); assertFalse(requests.isCurrent(previous));
        long next = requests.begin(); assertTrue(requests.isCurrent(next));
    }
    @Test public void recreatedHostPreservesAnOutstandingSystemResult() {
        VpnConsent requests = new VpnConsent(); long previous = requests.begin(); requests.launched(previous); requests.hostDestroyed();
        assertTrue(requests.isPending()); assertTrue(requests.isCurrent(previous)); assertThrows(IllegalStateException.class, requests::begin);
        assertTrue(requests.returned()); assertFalse(requests.isPending());
    }
    @Test public void resultWithoutAnOutstandingRequestCannotStartVpn() { assertFalse(new VpnConsent().returned()); }
    @Test public void lateGrantAfterStopDoesNotRestartVpn() {
        VpnConsent requests = new VpnConsent(); long generation = requests.begin(); requests.launched(generation); requests.cancel();
        assertFalse(requests.isPending()); assertFalse(requests.isCurrent(generation)); assertFalse(requests.returned());
    }
    @Test public void earlierSystemDialogMustFinishBeforeAnotherRequest() {
        VpnConsent requests = new VpnConsent(); long first = requests.begin(); requests.launched(first); requests.cancel();
        assertThrows(IllegalStateException.class, requests::begin); assertFalse(requests.returned());
        long next = requests.begin(); assertTrue(next > first); assertTrue(requests.isCurrent(next));
    }
    @Test public void cancelledDisclosureCannotLaunchSystemRequest() {
        VpnConsent requests = new VpnConsent(); long generation = requests.begin(); requests.cancel();
        assertThrows(IllegalStateException.class, () -> requests.launched(generation)); assertFalse(requests.grantedWithoutPrompt(generation));
    }
    @Test public void genuineGrantClearsPendingState() {
        VpnConsent requests = new VpnConsent(); long generation = requests.begin(); requests.launched(generation);
        assertTrue(requests.returned()); assertFalse(requests.isPending()); assertTrue(requests.isCurrent(generation));
    }
    @Test public void stopInvalidatesQueuedServiceStartEvenAfterGrant() {
        VpnConsent requests = new VpnConsent(); long generation = requests.begin(); assertTrue(requests.grantedWithoutPrompt(generation));
        requests.cancel(); assertFalse(requests.isCurrent(generation));
    }
}
