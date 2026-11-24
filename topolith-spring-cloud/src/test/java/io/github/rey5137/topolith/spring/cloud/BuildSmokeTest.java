package io.github.rey5137.topolith.spring.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildSmokeTest {
    @Test
    void ac_build_01_spring_cloud_builds() throws ClassNotFoundException {
        assertNotNull(Class.forName("io.github.rey5137.topolith.spring.cloud.package-info").getPackage());
    }
}
