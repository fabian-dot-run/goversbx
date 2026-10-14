package run.fabian.daemon.nativeimage;

import io.modelcontextprotocol.spec.McpSchema;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Runs inside native-image at build time ({@code --features}, see daemon/build.gradle.kts); never
 * loaded on the JVM. The MCP SDK (de)serializes every {@link McpSchema} type with Jackson via
 * reflection, and which ones a session needs depends on the client - Claude Code's
 * {@code initialize} carries capability objects a bare test client doesn't send. Registering all of
 * them, rather than what the tracing agent happened to see, keeps the binary working for any client
 * and for types a newer SDK adds.
 */
public final class McpSchemaReflectionFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        register(McpSchema.class);
    }

    private static void register(Class<?> type) {
        RuntimeReflection.register(type);
        RuntimeReflection.registerAllDeclaredConstructors(type);
        RuntimeReflection.registerAllDeclaredMethods(type);
        RuntimeReflection.registerAllDeclaredFields(type);
        RuntimeReflection.register(type.getDeclaredConstructors());
        RuntimeReflection.register(type.getDeclaredMethods());
        RuntimeReflection.register(type.getDeclaredFields());
        if (type.isRecord()) {
            RuntimeReflection.registerAllRecordComponents(type);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            register(nested);
        }
    }
}
