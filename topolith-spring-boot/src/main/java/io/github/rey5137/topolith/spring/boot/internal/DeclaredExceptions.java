package io.github.rey5137.topolith.spring.boot.internal;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Incubating;

/** Which exceptions a contract call carries across HTTP, shared with the test helper (R-EXT-04, D-116, D-117). */
@Incubating
public final class DeclaredExceptions {
    private DeclaredExceptions() {
    }

    /**
     * The class a caller rebuilds for {@code type}: {@link ModuleUnavailableException}, or the nearest non-abstract
     * class from {@code type} up that lies in one of {@code apiPackages}, or one of the declared JDK three
     * ({@code IllegalArgumentException}, {@code IllegalStateException}, {@code UnsupportedOperationException});
     * null when {@code type} is not declared.
     */
    public static Class<?> declared(Class<?> type, Set<String> apiPackages) {
        if (!Exception.class.isAssignableFrom(type)) {
            return null;
        }
        for (Class<?> current = type; current != Exception.class && current != RuntimeException.class;
                current = current.getSuperclass()) {
            if (current == ModuleUnavailableException.class || current == IllegalArgumentException.class
                    || current == IllegalStateException.class || current == UnsupportedOperationException.class) {
                return current;
            }
            if (!Modifier.isAbstract(current.getModifiers()) && apiPackages.contains(current.getPackageName())) {
                return current;
            }
        }
        return null;
    }

    /** Whether a caller can rebuild {@code type}: public, and a public {@code (String)} or no-arg constructor. */
    public static boolean rebuildable(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        for (Class<?>[] parameters : List.<Class<?>[]>of(new Class<?>[] {String.class}, new Class<?>[0])) {
            try {
                type.getConstructor(parameters);
                return true;
            } catch (NoSuchMethodException e) {
                // try the next shape
            }
        }
        return false;
    }
}
