package io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api;

/** A contract whose implementations report the transaction they run in. */
public interface BoundaryContract {
    /** The callee has no transaction annotation. */
    String plain();

    /** The callee is {@code @Transactional}. */
    String own();

    /** Always throws. */
    String fail();
}
