package io.github.rey5137.topolith.spring.boot.errorfixture.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class AccountHiddenException extends RuntimeException { }
