package io.github.rey5137.topolith.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildSmokeTest {
    @Test
    void ac_build_01_core_builds() throws ClassNotFoundException {
        assertNotNull(Class.forName("io.github.rey5137.topolith.core.package-info").getPackage());
    }
}
