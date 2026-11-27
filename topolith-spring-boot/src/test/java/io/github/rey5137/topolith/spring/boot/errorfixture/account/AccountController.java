package io.github.rey5137.topolith.spring.boot.errorfixture.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountNotFoundException;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentDeclinedException;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ThreadTenant;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
public class AccountController implements AccountContract {
    private final ObjectProvider<PaymentContract> payments;

    AccountController(ObjectProvider<PaymentContract> payments) {
        this.payments = payments;
    }

    @Override
    public String find(String failure) {
        if (failure.equals("ok")) {
            return "found";
        }
        if (failure.equals("ctx") || failure.equals("ctxfail")) {
            // what the provider sees of the caller's context (R-EXT-12)
            ThreadTenant.HANDLED.incrementAndGet();
            if (failure.equals("ctxfail")) {
                throw new IllegalStateException("secret detail");
            }
            return MDC.get("user.id") + "|" + MDC.get("trace") + "|" + MDC.get("other") + "|" + ThreadTenant.tenant();
        }
        if (failure.equals("pay")) {
            // a call from a module's class, when the payment module is internal
            return "paid " + payments.getObject().pay();
        }
        throw switch (failure) {
            case "missing" -> new AccountNotFoundException();
            case "locked" -> new LockedAccountException("account is locked");
            case "declined" -> new PaymentDeclinedException("card declined");
            case "unavailable" -> new ModuleUnavailableException("payment", "payment is down");
            case "secret" -> new IllegalStateException("secret detail");
            case "wrapped" -> new IllegalStateException("wrapped secret", new AccountException("inner"));
            default -> new UnsupportedOperationException(failure);
        };
    }

    /** An async module route: the values are read in the handler, the answer completes on another thread. */
    @GetMapping("/async")
    CompletableFuture<String> async() {
        String seen = find("ctx");
        return CompletableFuture.supplyAsync(() -> seen, CompletableFuture.delayedExecutor(100,
                TimeUnit.MILLISECONDS));
    }

    /** A forward from one module route to another. */
    @GetMapping("/forward")
    ModelAndView forward() {
        return new ModelAndView("forward:/account/find?case=ctx");
    }

    @Override
    public String partial() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
            HttpServletResponse response = attributes.getResponse();
            response.setContentType("application/json");
            response.setHeader("X-Trace", "kept");
            try {
                response.getWriter().write("{\"partial\":");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new AccountException("after a partial answer");
    }

    /** Runs only when this module is served over HTTP, so a declared exception must not reach it (INV-6). */
    @ExceptionHandler(AccountException.class)
    ResponseEntity<String> local(AccountException exception) {
        return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body("controller handler");
    }
}
