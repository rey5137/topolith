package io.github.rey5137.topolith.spring.boot.errorfixture.status.api;

@Moved
public class MovedException extends RuntimeException {
    public MovedException() {
        super("moved");
    }
}
