package io.github.rey5137.topolith.processor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import io.github.rey5137.topolith.annotation.TopolithModule;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;

/** In-process javac runs of small source trees, with or without the Topolith processor. */
final class Javac {
    static final String VERSION = "-Atopolith.apiVersion=1.4.2";

    private Javac() {
    }

    /** One compilation: where the classes went and what javac said. */
    record Result(boolean success, Path output, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        String messages(Diagnostic.Kind kind) {
            return diagnostics.stream().filter(d -> d.getKind() == kind).map(d -> d.getMessage(null))
                    .collect(Collectors.joining("\n"));
        }

        String errors() {
            return messages(Diagnostic.Kind.ERROR);
        }

        Path file(String resource) {
            return output.resolve(resource);
        }

        ModuleDescriptor module(String id) throws IOException {
            Path path = file("META-INF/topolith/modules/" + id + ".properties");
            try (InputStream in = Files.newInputStream(path)) {
                return Descriptors.parseModule(path.toString(), in);
            }
        }

        ApiDescriptor api(String id) throws IOException {
            Path path = file("META-INF/topolith/apis/" + id + ".properties");
            try (InputStream in = Files.newInputStream(path)) {
                return Descriptors.parseApi(path.toString(), in);
            }
        }
    }

    /** The location of topolith-core's classes: a directory in the reactor, a JAR otherwise. */
    static Path core() {
        try {
            return Path.of(TopolithModule.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Compiles the sources (path to text) into {@code work/out}, running the processor unless options say not. */
    static Result compile(Path work, Map<String, String> sources, List<Path> classpath, String... options)
            throws IOException {
        Path src = work.resolve("src");
        Path out = Files.createDirectories(work.resolve("out"));
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collected = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collected, null,
                StandardCharsets.UTF_8)) {
            List<String> arguments = new ArrayList<>(List.of("-d", out.toString(), "-classpath",
                    classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator))));
            arguments.addAll(List.of(options));
            boolean processing = !arguments.contains("-proc:none");
            JavaCompiler.CompilationTask task = compiler.getTask(null, manager, collected, arguments, null,
                    manager.getJavaFileObjectsFromPaths(files));
            if (processing) {
                task.setProcessors(List.of(new TopolithModuleProcessor()));
            }
            return new Result(task.call(), out, collected.getDiagnostics());
        }
    }

    /** Packs a directory of classes and resources into a JAR, as an API artifact would be. */
    static Path jar(Path directory, Path jar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> all =
                Files.walk(directory)) {
            for (Path file : all.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(directory.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
