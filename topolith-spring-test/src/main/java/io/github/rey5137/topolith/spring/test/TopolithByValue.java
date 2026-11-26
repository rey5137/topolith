package io.github.rey5137.topolith.spring.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.rey5137.topolith.core.Incubating;

/**
 * Puts a by-value proxy in front of the contracts of internal modules for a Spring test context (R-TEST-04): each call
 * passes its arguments and result as JSON copies, and a mutation that would leak between caller and provider in
 * internal mode fails the test. Found on the test class, its superclasses and its enclosing classes, like
 * {@link TopolithModuleMode}.
 */
@Incubating
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TopolithByValue {
    /** The module ids; empty means every internal module. */
    String[] value() default {};
}
