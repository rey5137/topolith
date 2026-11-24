package io.github.rey5137.topolith.spring.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildSmokeTest {
    @Test
    void ac_build_01_spring_test_builds() throws ClassNotFoundException {
        assertNotNull(Class.forName("io.github.rey5137.topolith.spring.test.package-info").getPackage());
    }
}
