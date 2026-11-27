package io.github.rey5137.topolith.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.rey5137.topolith.core.Incubating;

/**
 * Declares a Topolith module on a {@code package-info.java}; the annotation processor turns it into a descriptor.
 * On a package listed in its own {@link #api()} it declares the API artifact instead of the module (D-29). Add
 * {@code topolith-core} with {@code provided} scope: the annotation is kept in {@code package-info.class} only, and
 * every member is a String so a consumer compiled without it sees no missing class.
 */
@Incubating
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PACKAGE)
public @interface TopolithModule {
    /** The module id, for example {@code order-history}. */
    String id();

    /**
     * The exact API packages, without their subpackages. Empty (the default) declares a module without contracts,
     * such as a listener-only or job module: it has no API descriptor and runs only {@code internal} or {@code none}.
     */
    String[] api() default {};

    /**
     * On a module declaration, the contracts provided only by a {@code @Bean} method, a lambda or factory result that
     * no class under the package implements (D-110): fully qualified, binary names for member interfaces. Each must be
     * in the API's inferred {@code provides} and have no implementing class under the package; the listed and
     * inferred contracts together are the module's {@code provides}. An internal module needs exactly one bean of each
     * at startup. A compile error on an API declaration.
     */
    String[] provides() default {};

    /** Ids of the modules this module cannot start without; empty for an API declaration. */
    String[] requires() default {};

    /** Ids of the modules this module uses when present; empty for an API declaration. */
    String[] optional() default {};
}
