package io.github.rey5137.topolith.spring.boot.errorfixture.boundary;

import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import org.springframework.stereotype.Component;

/** The module's own code, calling its contract through the injected interface. */
@Component
public class BoundaryClient {
    private final BoundaryContract contract;

    BoundaryClient(BoundaryContract contract) {
        this.contract = contract;
    }

    public String callPlain() {
        return contract.plain();
    }
}
