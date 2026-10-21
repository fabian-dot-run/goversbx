package run.fabian.daemon.e2e;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Builds the shared {@link E2eEnvironment} before the first test class that needs it and registers
 * it in the root store, so JUnit closes it (sandboxes removed, daemon stopped) exactly once, when the
 * whole run is over - not after each class.
 */
final class E2eSuite implements BeforeAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(E2eSuite.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        // The store closes AutoCloseable values when the root context ends, i.e. after the last class.
        context.getRoot().getStore(NS).getOrComputeIfAbsent("environment", key -> E2eEnvironment.get());
    }
}
