package io.github.rey5137.topolith.spring.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import io.github.rey5137.topolith.core.Incubating;

/** Injects the mock server for an external module into a test field. */
@Incubating
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TopolithMock {
    /** Module id. */
    String value();
}
