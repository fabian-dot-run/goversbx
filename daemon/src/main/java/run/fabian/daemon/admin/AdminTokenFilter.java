package run.fabian.daemon.admin;

import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;

/**
 * Requires the admin token on every admin API and SSE call (threat-model F3). The loopback bind is
 * not a boundary against a sandbox - the sbx proxy re-originates sandbox traffic to the host's
 * loopback, so once any loopback host is allowed a sandbox can reach this server. The token (held by
 * the operator's browser, delivered by {@code goversbx dashboard}, never visible to a sandbox) is the
 * real boundary.
 *
 * <p>Static files (the UI itself) stay public - they carry no secret - and {@code /api/health} stays
 * public so the launcher can poll it. Everything else under {@code /api/*} needs the token, via an
 * {@code Authorization: Bearer <token>} header or a {@code ?token=<token>} query parameter (an
 * {@code EventSource} can't set headers).
 */
public final class AdminTokenFilter {

    private AdminTokenFilter() {
    }

    public static void register(Javalin app, String token) {
        app.before("/api/*", ctx -> {
            if (ctx.path().equals("/api/health")) {
                return;
            }
            if (!isAuthorized(ctx.header("Authorization"), ctx.queryParam("token"), token)) {
                throw new UnauthorizedResponse("admin token required");
            }
        });
    }

    /** Package-visible for a direct unit test. */
    static boolean isAuthorized(String authorizationHeader, String tokenQueryParam, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String provided = null;
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = authorizationHeader.substring(7).trim();
        } else if (tokenQueryParam != null) {
            provided = tokenQueryParam.trim();
        }
        return provided != null && constantTimeEquals(provided, expected);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
