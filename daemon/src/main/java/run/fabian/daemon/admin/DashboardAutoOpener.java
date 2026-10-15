package run.fabian.daemon.admin;

import run.fabian.daemon.networkaccess.api.NetworkAccessListener;
import run.fabian.daemon.networkaccess.api.NetworkAccessRequestView;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Opens the admin dashboard in the browser when a request arrives and no dashboard is currently
 * connected to the daemon, so the operator sees the request without having kept a tab open - and
 * <em>only</em> then, so an already-open dashboard is never reopened and never has its focus stolen
 * (which would defeat the point of notifications).
 *
 * <p>"Is a dashboard open?" is answered by the daemon itself, not by any browser API: an open
 * dashboard holds a live SSE connection to {@code /api/events}, so {@code dashboardConnected} (backed
 * by the admin event stream's live-client count) is the reliable signal. A short cooldown absorbs a
 * burst of requests in the window between opening the tab and it connecting.
 */
public final class DashboardAutoOpener implements NetworkAccessListener {

    private final String dashboardUrl;
    private final BrowserOpener opener;
    private final BooleanSupplier dashboardConnected;
    private final long cooldownNanos;
    private final AtomicLong lastOpenNanos;

    public DashboardAutoOpener(int adminPort, String adminToken, BrowserOpener opener,
                               BooleanSupplier dashboardConnected, long cooldownMillis) {
        this.dashboardUrl = "http://127.0.0.1:" + adminPort + "/?token=" + adminToken;
        this.opener = opener;
        this.dashboardConnected = dashboardConnected;
        this.cooldownNanos = cooldownMillis * 1_000_000L;
        // Start one cooldown in the past so the first request opens immediately; a plain MIN_VALUE
        // would overflow the `now - last` subtraction below.
        this.lastOpenNanos = new AtomicLong(System.nanoTime() - cooldownNanos - 1);
    }

    @Override
    public void requestCreated(NetworkAccessRequestView request) {
        if (dashboardConnected.getAsBoolean()) {
            return;   // a dashboard is already open and listening - don't reopen or steal focus
        }
        long now = System.nanoTime();
        long last = lastOpenNanos.get();
        if (now - last < cooldownNanos) {
            return;   // just opened one; the tab is still connecting
        }
        if (!lastOpenNanos.compareAndSet(last, now)) {
            return;   // another request won the race
        }
        opener.open(dashboardUrl);
    }

    @Override
    public void requestDecided(NetworkAccessRequestView request) {
        // nothing to do
    }
}
