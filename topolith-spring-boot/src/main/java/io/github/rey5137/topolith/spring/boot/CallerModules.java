package io.github.rey5137.topolith.spring.boot;

import java.util.List;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.observation.ModuleCallContext;

/**
 * Finds the module a contract call came from by walking the calling thread's stack (D-88): the nearest frame in a
 * known module's package names it; frames of the JDK, Spring, Micrometer, Topolith's own library packages, Mockito,
 * Byte Buddy, the servlet containers and generated proxies are skipped; the first other frame, or none, is host code.
 */
final class CallerModules {
    private static final List<String> SKIPPED = List.of("java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "io.micrometer.", "io.github.rey5137.topolith.core.",
            "io.github.rey5137.topolith.contract.", "io.github.rey5137.topolith.spring.", "org.mockito.",
            "net.bytebuddy.", "org.apache.catalina.", "org.apache.coyote.", "org.apache.tomcat.", "org.eclipse.jetty.",
            "io.undertow.");

    private static final Pattern GENERATED = Pattern.compile("(^|\\.)\\$Proxy\\d+$");

    private final Topology topology;

    CallerModules(Topology topology) {
        this.topology = topology;
    }

    /**
     * The calling module's id, or {@link ModuleCallContext#HOST}. A frame in {@code contract} or its package, such as a
     * default method running on a client proxy, counts as {@code called}, so the call it makes is a self-call.
     */
    String find(String called, Class<?> contract) {
        return StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                .map(name -> classify(name, called, contract)).filter(found -> found != null).findFirst())
                .orElse(ModuleCallContext.HOST);
    }

    /** The module id, {@code _host} for a frame of host code, or null for a frame to skip. */
    private String classify(String className, String called, Class<?> contract) {
        // a generated subclass of a module's class lies in its package too, but is the proxy, not the caller
        if (className.contains("$$") || GENERATED.matcher(className).find()) {
            return null;
        }
        if (className.startsWith(contract.getPackageName() + ".")
                && className.indexOf('.', contract.getPackageName().length() + 1) < 0) {
            return called;
        }
        String owner = topology.ownerOfClass(className).orElse(null);
        if (owner != null) {
            return owner;
        }
        for (String prefix : SKIPPED) {
            if (className.startsWith(prefix)) {
                return null;
            }
        }
        return ModuleCallContext.HOST;
    }
}
