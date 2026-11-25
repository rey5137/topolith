package io.github.rey5137.topolith.spring.boot.errorfixture.mute.api;

/** A caller cannot rebuild it: the class is not public. */
class HiddenException extends RuntimeException {
    public HiddenException(String message) {
        super(message);
    }
}
