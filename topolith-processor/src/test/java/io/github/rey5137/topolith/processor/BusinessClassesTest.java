package io.github.rey5137.topolith.processor;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessClassesTest {
    private static final String TOPOLITH = "io/github/rey5137/topolith";

    @TempDir
    Path work;

    private Javac.Result compileModule() throws IOException {
        Map<String, String> sources = new java.util.LinkedHashMap<>(Fixtures.api(""));
        sources.putAll(Fixtures.module(Fixtures.MODULE));
        sources.put("com/acme/user/impl/Greeting.java", """
                package com.acme.user.impl;
                public class Greeting { public String greet() { return new UserServiceImpl().find(); } }
                """);
        return Javac.compile(work.resolve("module"), sources, List.of(Javac.core()), Javac.VERSION);
    }

    @Test
    void ac_desc_03_only_package_info_refers_to_a_topolith_type() throws IOException {
        Javac.Result result = compileModule();
        assertTrue(result.success(), result.errors());
        try (Stream<Path> files = Files.walk(result.output())) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                boolean refers = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(TOPOLITH);
                Path relative = result.output().relativize(file);
                assertEquals(relative.equals(Path.of("com/acme/user/impl/package-info.class")), refers,
                        relative.toString());
            }
        }
    }

    @Test
    void ac_desc_03_business_classes_run_without_topolith_core_on_the_classpath() throws Exception {
        Javac.Result result = compileModule();
        assertTrue(result.success(), result.errors());
        try (URLClassLoader loader = new URLClassLoader(new URL[] {result.output().toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Object greeting = loader.loadClass("com.acme.user.impl.Greeting").getDeclaredConstructor().newInstance();
            assertEquals("found", greeting.getClass().getMethod("greet").invoke(greeting));
            assertFalse(loader.loadClass("com.acme.user.UserService").isAnnotation());
        }
    }

    @Test
    void ac_desc_03_a_consumer_compiles_without_core_and_without_warnings() throws IOException {
        Javac.Result module = compileModule();
        assertTrue(module.success(), module.errors());
        Javac.Result consumer = Javac.compile(work.resolve("consumer"), Map.of("app/Use.java", """
                package app;
                import com.acme.user.UserService;
                import com.acme.user.impl.Greeting;
                public class Use { UserService service; String run() { return new Greeting().greet(); } }
                """), List.of(module.output()), "-proc:none", "-Xlint:all", "-Werror");
        assertTrue(consumer.success(), consumer.errors() + consumer.messages(javax.tools.Diagnostic.Kind.WARNING));
        assertTrue(consumer.diagnostics().stream().noneMatch(d -> d.getKind() != javax.tools.Diagnostic.Kind.NOTE),
                consumer.diagnostics().toString());
    }
}
