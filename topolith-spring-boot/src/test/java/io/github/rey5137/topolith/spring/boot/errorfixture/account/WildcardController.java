package io.github.rey5137.topolith.spring.boot.errorfixture.account;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A module route that matches any two-segment path, such as the version endpoint's, when asked for. */
@RestController
@ConditionalOnProperty("test.wildcard")
public class WildcardController {
    @GetMapping("/{first}/{second}")
    String any() {
        return "wildcard";
    }
}
