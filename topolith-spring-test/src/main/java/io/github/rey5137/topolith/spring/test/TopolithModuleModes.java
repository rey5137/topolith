package io.github.rey5137.topolith.spring.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import io.github.rey5137.topolith.core.Incubating;

/** Container for repeated module mode declarations. */
@Incubating
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TopolithModuleModes {
    /** Mode declarations. */
    TopolithModuleMode[] value();
}
