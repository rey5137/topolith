package io.github.rey5137.topolith.contract;

import io.github.rey5137.topolith.core.Incubating;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a contract method safe to retry (R-DESC-05, D-100). On an abstract method of a provided contract
 * interface it marks that method; on the contract interface it marks every public abstract method the interface
 * exposes, including inherited ones, but never a {@code default} method. It is the only way a method enters the
 * descriptor's {@code idempotent} set: HTTP exchange annotations such as {@code @GetExchange} never do. Overloads of
 * one method name on a contract must agree. The processor rejects it on a class, a type outside the module's api
 * packages, or a {@code default}, {@code static} or {@code private} method (TL0013), and startup checks the descriptor
 * against it (TL0040).
 */
@Incubating
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Idempotent {
}
