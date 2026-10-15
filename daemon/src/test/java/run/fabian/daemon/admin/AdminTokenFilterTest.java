package run.fabian.daemon.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin token check (threat-model F3). */
class AdminTokenFilterTest {

    private static final String TOKEN = "gvadm-secret";

    @Test
    void acceptsTheTokenViaBearerHeaderOrQueryParam() {
        assertTrue(AdminTokenFilter.isAuthorized("Bearer " + TOKEN, null, TOKEN));
        assertTrue(AdminTokenFilter.isAuthorized("bearer " + TOKEN, null, TOKEN));   // scheme is case-insensitive
        assertTrue(AdminTokenFilter.isAuthorized(null, TOKEN, TOKEN));
    }

    @Test
    void rejectsMissingWrongOrMalformed() {
        assertFalse(AdminTokenFilter.isAuthorized(null, null, TOKEN));
        assertFalse(AdminTokenFilter.isAuthorized("Bearer wrong", null, TOKEN));
        assertFalse(AdminTokenFilter.isAuthorized(null, "wrong", TOKEN));
        assertFalse(AdminTokenFilter.isAuthorized(TOKEN, null, TOKEN));              // header must say "Bearer"
        assertFalse(AdminTokenFilter.isAuthorized("Bearer ", null, TOKEN));
    }

    @Test
    void rejectsEverythingWhenNoTokenIsConfigured() {
        assertFalse(AdminTokenFilter.isAuthorized("Bearer x", "x", ""));
        assertFalse(AdminTokenFilter.isAuthorized("Bearer x", "x", null));
    }
}
