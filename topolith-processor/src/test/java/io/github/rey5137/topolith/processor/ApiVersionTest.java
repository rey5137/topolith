package io.github.rey5137.topolith.processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiVersionTest {
    @TempDir
    Path work;

    private Javac.Result compile(String... options) throws IOException {
        return Javac.compile(work, Fixtures.api(Fixtures.API), List.of(Javac.core()), options);
    }

    @Test
    void ac_desc_05_the_option_is_copied_verbatim_into_the_descriptor() throws IOException {
        Javac.Result result = compile("-Atopolith.apiVersion=1.4.2");
        assertTrue(result.success(), result.errors());
        assertEquals("1.4.2", result.api("user").apiVersion());
        assertTrue(result.messages(javax.tools.Diagnostic.Kind.WARNING).isEmpty(), "the option is declared");
    }

    @Test
    void ac_desc_05_a_missing_or_blank_option_is_a_tl0013_compile_error() throws IOException {
        for (String[] options : new String[][] {{}, {"-Atopolith.apiVersion="}, {"-Atopolith.apiVersion=  "}}) {
            Javac.Result result = compile(options);
            assertFalse(result.success());
            assertTrue(result.errors().contains("TL0013") && result.errors().contains("-Atopolith.apiVersion"),
                    result.errors());
            assertFalse(Files.exists(result.file("META-INF/topolith")));
        }
    }

    @Test
    void ac_desc_05_a_version_without_a_numeric_major_is_a_tl0013_compile_error() throws IOException {
        Javac.Result result = compile("-Atopolith.apiVersion=latest");
        assertFalse(result.success());
        assertTrue(result.errors().contains("TL0013") && result.errors().contains("api-version"), result.errors());
    }

    @Test
    void ac_desc_05_without_a_declaration_the_option_is_not_needed() throws IOException {
        Javac.Result result = Javac.compile(work, java.util.Map.of("a/B.java", "package a;\npublic class B { }\n"),
                List.of(Javac.core()));
        assertTrue(result.success(), result.errors());
    }

    /** The processor ran on this module's own test fixture with {@code -Atopolith.apiVersion=${project.version}}. */
    @Test
    void ac_desc_05_api_version_equals_the_maven_project_version() throws IOException {
        String projectVersion = System.getProperty("project.version");
        assertNotNull(projectVersion, "surefire passes the project version");
        String resource = "/META-INF/topolith/modules/processor-version-fixture.properties";
        try (InputStream in = ApiVersionTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "the test compile generated " + resource);
            ModuleDescriptor module = Descriptors.parseModule(resource, in);
            assertEquals(projectVersion, module.apiVersion());
        }
        try (InputStream in = ApiVersionTest.class.getResourceAsStream(
                "/META-INF/topolith/apis/processor-version-fixture.properties")) {
            assertNotNull(in);
            assertEquals(projectVersion,
                    Descriptors.parseApi("x/processor-version-fixture.properties", in).apiVersion());
        }
    }
}
