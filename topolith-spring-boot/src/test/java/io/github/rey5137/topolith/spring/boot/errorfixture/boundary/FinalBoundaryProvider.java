package io.github.rey5137.topolith.spring.boot.errorfixture.boundary;

import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import org.springframework.web.bind.annotation.RequestMapping;

/** A final handler provider, registered only by the test that expects TL0029 (not a scanned component). */
@RequestMapping("/final")
public final class FinalBoundaryProvider implements BoundaryContract {
    @Override
    public String plain() {
        return "plain";
    }

    @Override
    public String own() {
        return "own";
    }

    @Override
    public String fail() {
        throw new IllegalStateException("callee failed");
    }
}
