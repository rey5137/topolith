package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;

/** Builds fixture classpath roots: directories holding descriptors and imports files, read through one loader. */
final class Fixtures {
    static final String BASE = "io.github.rey5137.topolith.spring.boot.fixture";
    static final String USER = BASE + ".user";
    static final String USER_API = BASE + ".user.api";
    static final String PAYMENT = BASE + ".payment";
    static final String OUTSIDE = BASE + ".outside";
    static final String HOST = BASE + ".host";
    static final String LEDGER = BASE + ".ledger";
    static final String BILLING = BASE + ".billing";
    static final String AUDIT = BASE + ".audit";
    static final String INVENTORY = BASE + ".inventory";
    static final String ROGUE = BASE + ".rogue";
    static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private Fixtures() {
    }

    /** A class loader that records which fixture classes were asked for, in front of the test class loader. */
    static final class RecordingLoader extends URLClassLoader {
        private final List<String> requested = Collections.synchronizedList(new ArrayList<>());

        RecordingLoader(Path... roots) {
            this(Fixtures.class.getClassLoader(), roots);
        }

        /** With a parent that hides classes, such as a {@code FilteredClassLoader}. */
        RecordingLoader(ClassLoader parent, Path... roots) {
            super(urls(roots), parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(BASE)) {
                requested.add(name);
            }
            return super.loadClass(name, resolve);
        }

        /** Child first, so a fixture root can hold a copy of a class that the test class path also has. */
        @Override
        public URL getResource(String name) {
            URL own = findResource(name);
            return own != null ? own : super.getResource(name);
        }

        List<String> requested(String packagePrefix) {
            synchronized (requested) {
                return requested.stream().filter(name -> name.startsWith(packagePrefix)).toList();
            }
        }
    }

    private static URL[] urls(Path... roots) {
        try {
            URL[] urls = new URL[roots.length];
            for (int i = 0; i < roots.length; i++) {
                urls[i] = roots[i].toUri().toURL();
            }
            return urls;
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A module descriptor, written into {@code root} the way the processor writes it. */
    static void module(Path root, String id, String packageName, List<String> api, String... requires)
            throws IOException {
        ModuleDescriptor module = ModuleDescriptor.builder(id).packageName(packageName).apiPackages(api)
                .apiVersion("1.0.0").requires(List.of(requires)).build();
        Path file = root.resolve("META-INF/topolith/modules/" + id + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            Descriptors.write(module, out);
        }
    }

    /** An API descriptor, as a {@code *-api} JAR carries it. */
    static void api(Path root, String id, String... packages) throws IOException {
        Path file = root.resolve("META-INF/topolith/apis/" + id + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            Descriptors.write(io.github.rey5137.topolith.core.ApiDescriptor.builder(id)
                    .apiPackages(List.of(packages)).apiVersion("1.0.0").build(), out);
        }
    }

    /** An {@code AutoConfiguration.imports} file in {@code root}. */
    static void imports(Path root, String... classes) throws IOException {
        Path file = root.resolve(IMPORTS);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# fixture\n" + String.join("\n", classes) + "\n", StandardCharsets.UTF_8);
    }

    /** A text file in {@code root}, such as a migration script. */
    static void resource(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static Path dir(Path parent, String name) throws IOException {
        return Files.createDirectories(parent.resolve(name));
    }
}
