package io.github.rey5137.topolith.spring.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.Mode;

/** Selects a module mode for a Spring test context. */
@Incubating
@Inherited
@Repeatable(TopolithModuleModes.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TopolithModuleMode {
    /** Module id. */
    String id();
    /** Explicit mode. */
    Mode mode();
}
