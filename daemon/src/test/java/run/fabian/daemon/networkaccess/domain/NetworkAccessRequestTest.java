package run.fabian.daemon.networkaccess.domain;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.api.RequestStatus;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
