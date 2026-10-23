package run.fabian.daemon.networkaccess.adapter.sbx;

import org.junit.jupiter.api.Test;
import run.fabian.daemon.networkaccess.application.port.SbxPolicyPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbxCliPolicyAdapterTest {

    private static final String REAL_OUTPUT = """
            Added rule d7f296da-249f-4bd1-97c3-c7b868ce9a48 to policy local (scope: sandbox:sb): example.com [tcp]
            Already covered in policy local (scope: sandbox:sb): example.net [tcp] (by rule "923163b0-bf7c-423a-ada5-0a0942e38d6c")""";

    @Test
    void picksEachHostsOwnLineOutOfTheBatchOutput() {
        // Real sbx 0.49.1 output of `policy allow network example.com,example.net --sandbox sb`.
        assertTrue(SbxCliPolicyAdapter.lineFor("example.com", REAL_OUTPUT).startsWith("Added rule"));
        assertTrue(SbxCliPolicyAdapter.lineFor("example.net", REAL_OUTPUT).startsWith("Already covered"));
        assertEquals(REAL_OUTPUT, SbxCliPolicyAdapter.lineFor("other.example", REAL_OUTPUT));
    }

    @Test
    void dryRunIsOneCommandWithTheCommaSeparatedList() {
        SbxCliPolicyAdapter adapter = new SbxCliPolicyAdapter("sbx", true);

        List<SbxPolicyPort.Result> results = adapter.allowNetwork("sb", List.of("a.example", "b.example"));

        assertEquals(2, results.size());
        results.forEach(r -> assertTrue(r.applied()));
        assertEquals("dry-run: would execute: sbx policy allow network a.example,b.example --sandbox sb", results.get(0).output());
        assertEquals("dry-run: would execute: sbx policy allow network a.example --sandbox sb",
                adapter.allowNetwork("sb", "a.example").output());
    }
}
