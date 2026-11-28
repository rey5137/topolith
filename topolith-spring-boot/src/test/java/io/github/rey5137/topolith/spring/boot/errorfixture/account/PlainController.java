package io.github.rey5137.topolith.spring.boot.errorfixture.account;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A module route on a bean that implements no contract, so no Topolith advice wraps it (D-137). */
@RestController
@ConditionalOnProperty("test.plain")
public class PlainController {
    @GetMapping("/plain/state")
    String state() {
        throw new IllegalStateException("plain state");
    }

    @GetMapping("/plain/unresolved")
    String unresolved(List<String> values) {
        return "never";
    }
}
