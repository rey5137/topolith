package io.github.rey5137.topolith.spring.test;

import java.net.URL;
import java.net.URLClassLoader;

final class FixtureLoader {
    private FixtureLoader() { }

    static URLClassLoader create() { return create("test-descriptors/"); }

    static URLClassLoader create(String directory) {
        URL root = FixtureLoader.class.getClassLoader().getResource(directory);
        return new URLClassLoader(new URL[] { root }, FixtureLoader.class.getClassLoader());
    }
}
