package io.github.rey5137.topolith.spring.boot.errorfixture.status.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A composed annotation carrying a redirect status. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ResponseStatus(code = HttpStatus.FOUND)
public @interface Moved {
}
