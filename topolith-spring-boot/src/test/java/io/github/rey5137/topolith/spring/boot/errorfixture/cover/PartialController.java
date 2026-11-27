package io.github.rey5137.topolith.spring.boot.errorfixture.cover;

import io.github.rey5137.topolith.spring.boot.errorfixture.cover.api.PartialContract;
import org.springframework.web.bind.annotation.RestController;

/** Overrides every method of {@link PartialContract}, but adds no mapping where the contract has none. */
@RestController
public class PartialController implements PartialContract {
    @Override
    public String find(String query) {
        return query;
    }

    @Override
    public String find(String query, int limit) {
        return query;
    }

    @Override
    public String pending() {
        return "";
    }
}
