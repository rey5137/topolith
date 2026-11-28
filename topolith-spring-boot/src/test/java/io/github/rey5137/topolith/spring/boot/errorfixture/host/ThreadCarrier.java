package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import io.github.rey5137.topolith.spring.boot.ContextCarrier;

public class ThreadCarrier implements ContextCarrier {
    public static final ThreadLocal<String> VALUE = new ThreadLocal<>();
    public String name() { return "example"; }
    public String current() { return VALUE.get(); }
    public Scope open(String value) {
        String previous = VALUE.get();
        set(value);
        return () -> set(previous);
    }
    public static void set(String value) {
        if (value == null) {
            VALUE.remove();
        } else {
            VALUE.set(value);
        }
    }
}
