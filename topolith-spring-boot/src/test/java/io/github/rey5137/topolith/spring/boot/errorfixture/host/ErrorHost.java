package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A web host with its own advice and controller, in a package disjoint from every module (TL0018). */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({ErrorHost.HostAdvice.class, ErrorHost.HostController.class})
public class ErrorHost {
    /** A global advice that would catch every declared account exception if it ran first. */
    @RestControllerAdvice
    public static class HostAdvice {
        @ExceptionHandler(AccountException.class)
        ResponseEntity<String> handle(AccountException exception) {
            return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body("host advice");
        }
    }

    @RestController
    public static class HostController {
        @GetMapping("/host/fail")
        String fail() {
            throw new AccountException("from the host");
        }
    }
}
