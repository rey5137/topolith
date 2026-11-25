package io.github.rey5137.topolith.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildSmokeTest {
    @Test
    void ac_build_01_processor_builds() throws ClassNotFoundException {
        assertNotNull(Class.forName("io.github.rey5137.topolith.processor.package-info").getPackage());
    }
}
