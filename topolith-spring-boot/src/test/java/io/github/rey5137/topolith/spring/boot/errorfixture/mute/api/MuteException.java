package io.github.rey5137.topolith.spring.boot.errorfixture.mute.api;

/** A caller cannot rebuild it: its only constructor takes more than the message. */
public class MuteException extends RuntimeException {
    public MuteException(String message, int code) {
        super(message + code);
    }
}
