package io.github.rey5137.topolith.spring.boot.errorfixture.boundary;

import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;

/** A final provider that is not a handler, registered only by the test that expects it to take the boundary. */
public final class FinalPlainProvider implements BoundaryContract {
    @Override
    public String plain() {
        return BoundaryService.state();
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
