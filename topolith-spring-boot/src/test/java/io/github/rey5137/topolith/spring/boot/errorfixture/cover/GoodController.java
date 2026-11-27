package io.github.rey5137.topolith.spring.boot.errorfixture.cover;

import io.github.rey5137.topolith.spring.boot.errorfixture.cover.api.GoodContract;
import org.springframework.web.bind.annotation.RestController;

/** Implements every method of {@link GoodContract}; {@code put} gets a bridge for the generic superinterface. */
@RestController
public class GoodController implements GoodContract {
    @Override
    public String put(String value) {
        return value;
    }

    @Override
    public String ping() {
        return "pong";
    }

    @Override
    public String name() {
        return "good";
    }
}
