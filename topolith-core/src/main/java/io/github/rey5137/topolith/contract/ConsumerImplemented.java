package io.github.rey5137.topolith.contract;

import io.github.rey5137.topolith.core.Incubating;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface in an api package as a consumer-implemented SPI (D-110): other modules implement it, so it is no
 * contract. Neither the API's nor the module's {@code provides} lists it, and a class implementing it is not its
 * provider. Marked interfaces are dropped before the most-derived contracts are kept, so an unmarked superinterface
 * stays a contract that the SPI's implementers provide, unless it is marked too.
 */
@Incubating
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConsumerImplemented {
}
