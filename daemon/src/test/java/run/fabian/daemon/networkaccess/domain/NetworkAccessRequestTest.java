package run.fabian.daemon.networkaccess.domain;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.api.RequestStatus;

import run.fabian.daemon.networkaccess.api.HostGrant;
import run.fabian.daemon.networkaccess.api.RequestOrigin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAccessRequestTest {

    private NetworkAccessRequest newPendingRequest() {
        return new NetworkAccessRequest(UUID.randomUUID(), "sandbox-1", "api.example.com", "because", Instant.now());
    }

    @Test
    void startsPending() {
        NetworkAccessRequest request = newPendingRequest();
        assertEquals(RequestStatus.PENDING, request.getStatus());
    }

    @Test
    void approveSetsFields() {
        NetworkAccessRequest request = newPendingRequest();

        request.approve("fabian", true, "applied ok");

        assertEquals(RequestStatus.APPROVED, request.getStatus());
        assertEquals("fabian", request.getDecidedBy());
        assertTrue(request.getSbxApplied());
        assertEquals("applied ok", request.getSbxOutput());
        assertTrue(request.getDecidedAt().isAfter(request.getRequestedAt().minusSeconds(1)));
    }

    @Test
    void denySetsFields() {
        NetworkAccessRequest request = newPendingRequest();

        request.deny("fabian");

        assertEquals(RequestStatus.DENIED, request.getStatus());
        assertEquals("fabian", request.getDecidedBy());
    }

    @Test
    void approveTwiceThrows() {
        NetworkAccessRequest request = newPendingRequest();
        request.approve("fabian", true, "ok");

        assertThrows(AlreadyDecidedException.class, () -> request.approve("someone-else", true, "ok"));
    }

    @Test
    void denyAfterApproveThrows() {
        NetworkAccessRequest request = newPendingRequest();
        request.approve("fabian", true, "ok");

        assertThrows(AlreadyDecidedException.class, () -> request.deny("fabian"));
    }

    private NetworkAccessRequest newHostSet() {
        return NetworkAccessRequest.projectHostSet(UUID.randomUUID(), "sandbox-1", List.of("a.example", "b.example"),
                "/ws/.goversbx/network.yaml", List.of("jvm"), Instant.now());
    }

    @Test
    void aSingleRequestIsAnAgentRequestForOneHost() {
        NetworkAccessRequest request = newPendingRequest();
        assertEquals(RequestOrigin.AGENT, request.getOrigin());
        assertEquals(List.of("api.example.com"), request.getHosts());
        assertTrue(request.isSingleHost());
        assertNull(request.getSource());
        assertNull(request.getSbxApplied());
        assertNull(request.getSbxOutput());
    }

    @Test
    void aHostSetCarriesItsOriginAndApprovesPerHost() {
        NetworkAccessRequest set = newHostSet();
        assertEquals(RequestOrigin.PROJECT_FILE, set.getOrigin());
        assertFalse(set.isSingleHost());
        assertTrue(set.getReason().contains("/ws/.goversbx/network.yaml"));
        assertTrue(set.getReason().contains("jvm"));

        set.approve("fabian", List.of(new HostGrant("a.example", true, "ok"), new HostGrant("b.example", false, "refused")));

        assertEquals(RequestStatus.APPROVED, set.getStatus());
        assertEquals(Boolean.FALSE, set.getSbxApplied());
        assertEquals("a.example: ok\nb.example: refused", set.getSbxOutput());
    }

    @Test
    void grantsMustMatchTheHostsExactly() {
        NetworkAccessRequest set = newHostSet();
        assertThrows(IllegalArgumentException.class, () -> set.approve("fabian", List.of(new HostGrant("a.example", true, "ok"))));
        assertThrows(IllegalArgumentException.class, () -> set.approve("fabian",
                List.of(new HostGrant("b.example", true, "ok"), new HostGrant("a.example", true, "ok"))));
        assertThrows(IllegalStateException.class, () -> set.approve("fabian", true, "ok"));
        assertEquals(RequestStatus.PENDING, set.getStatus());
    }

    @Test
    void aRequestNeedsDistinctHosts() {
        assertThrows(IllegalArgumentException.class, () -> NetworkAccessRequest.projectHostSet(UUID.randomUUID(), "sb",
                List.of("a.example", "a.example"), "/f", List.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> NetworkAccessRequest.projectHostSet(UUID.randomUUID(), "sb",
                List.of(), "/f", List.of(), Instant.now()));
    }
}
