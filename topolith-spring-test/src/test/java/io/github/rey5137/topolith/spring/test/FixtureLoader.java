package io.github.rey5137.topolith.spring.test;

import java.net.URL;
import java.net.URLClassLoader;

final class FixtureLoader {
    private FixtureLoader() { }

    static URLClassLoader create() {
        URL root = FixtureLoader.class.getClassLoader().getResource("test-descriptors/");
        return new URLClassLoader(new URL[] { root }, FixtureLoader.class.getClassLoader());
    }
}
