package io.github.rey5137.topolith.architecture;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.TopolithAutoConfiguration;
import io.github.rey5137.topolith.spring.test.TopolithArchitecture;
import io.github.rey5137.topolith.spring.test.TopolithBaseline;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Writer;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup reference set (R-ARCH-05) against ArchUnit's dependency set for the non-API access rule (R-ARCH-02),
 * on class files compiled from the sources below: one alpha class per reference kind, each naming its own beta class.
 */
class BoundaryParityTest {
    private static final String STORE = "freeze.store.default.";
    private static final Pattern PAIR = Pattern.compile("([\\w.$]+) -> ([\\w.$]+)");

    @TempDir
    Path root;

    private Path classes;

    @BeforeEach
    void configureStore() {
        ArchConfiguration.get().reset();
        ArchConfiguration.get().setProperty(STORE + "path", root.resolve("archunit_store").toString());
    }

    @AfterEach
    void resetConfiguration() {
        ArchConfiguration.get().reset();
    }

    private static List<ModuleDescriptor> modules() {
        return List.of(
                ModuleDescriptor.builder("alpha").packageName("fixture.alpha")
                        .apiPackages(List.of("fixture.alpha.api")).apiVersion("1.0.0").build(),
                ModuleDescriptor.builder("beta").packageName("fixture.beta").apiPackages(List.of("fixture.beta.api"))
                        .apiVersion("1.0.0").build());
    }

    /** Beta classes, all in the non-API package, and the beta API callee whose signatures leak non-API types. */
    private static Map<String, String> sources() {
        Map<String, String> s = new TreeMap<>();
        for (String name : List.of("B01", "B02", "B03", "B05", "B08", "B09", "B10", "B11", "B12", "B13", "B14", "B15",
                "B16", "B17", "B18", "B19", "B20", "B21", "B25", "B27", "B28", "B30", "B31", "B34", "B35", "B38", "B39")) {
            s.put("fixture.beta." + name, "public class " + name + " { public static void m() {} "
                    + "public static final String S = \"s\"; }");
        }
        s.put("fixture.beta.B04", "public class B04 extends Exception {}");
        s.put("fixture.beta.B22", "public class B22 extends RuntimeException {}");
        s.put("fixture.beta.B23", "public class B23 { public static class Inner {} }");
        s.put("fixture.beta.B24", "public class B24 {}");
        s.put("fixture.beta.B26", "public interface B26 {}");
        s.put("fixture.beta.B29", "public enum B29 { ONE, TWO }");
        s.put("fixture.beta.B06", "public class B06 {}");
        s.put("fixture.beta.B07", "public class B07 {}");
        s.put("fixture.beta.B32", "public @interface B32 { Class<?> value() default Object.class; }");
        s.put("fixture.beta.B33", "public @interface B33 {}");
        s.put("fixture.beta.api.Facade", "public class Facade { "
                + "public static fixture.beta.B06 make() { return null; } "
                + "public static void take(fixture.beta.B07 p) {} "
                + "public static fixture.beta.B31 FIELD; "
                + "public static fixture.beta.B30[] arrays() { return null; } "
                + "public java.util.List<fixture.beta.B28> generic() { return null; } }");
        // alpha classes, one kind each
        s.put("fixture.alpha.A01", "public class A01 { public fixture.beta.B01 field; }");
        s.put("fixture.alpha.A02", "public class A02 { public void m(fixture.beta.B02 p) {} }");
        s.put("fixture.alpha.A03", "public class A03 { public fixture.beta.B03 m() { return null; } }");
        s.put("fixture.alpha.A04", "public class A04 { public void m() throws fixture.beta.B04 {} }");
        s.put("fixture.alpha.A05", "public class A05 { public void m() { fixture.beta.B05.m(); } }");
        s.put("fixture.alpha.A06", "public class A06 { public Object m() { return fixture.beta.api.Facade.make(); } }");
        s.put("fixture.alpha.A07", "public class A07 { public void m() { fixture.beta.api.Facade.take(null); } }");
        s.put("fixture.alpha.A08", "@fixture.beta.B32 public class A08 {}");
        s.put("fixture.alpha.A09", "public class A09 { @fixture.beta.B33 public void m() {} }");
        s.put("fixture.alpha.A10", "public class A10 { public java.util.List<fixture.beta.B10> field; }");
        s.put("fixture.alpha.A11", "public class A11 { public java.util.List<fixture.beta.B11> m() { return null; } }");
        s.put("fixture.alpha.A12", "public class A12 extends java.util.ArrayList<fixture.beta.B12> {}");
        s.put("fixture.alpha.A13", "public class A13 { public Class<?> m() { return fixture.beta.B13.class; } }");
        s.put("fixture.alpha.A14", "public class A14 { public boolean m(Object o) { return o instanceof fixture.beta.B14; } }");
        s.put("fixture.alpha.A15", "public class A15 { public Object m(Object o) { return (fixture.beta.B15) o; } }");
        s.put("fixture.alpha.A16", "public class A16 { public Object m() { return (java.util.function.Supplier<Object>) () -> new fixture.beta.B16(); } }");
        s.put("fixture.alpha.A17", "public class A17 { public Object m() { java.util.function.Supplier<fixture.beta.B17> s = fixture.beta.B17::new; return s; } }");
        s.put("fixture.alpha.A18", "public class A18 { public static class Inner { public fixture.beta.B18 field; } }");
        s.put("fixture.alpha.A19", "public class A19 { public Object m() { return new Object() { fixture.beta.B19 f; }; } }");
        s.put("fixture.alpha.A20", "public class A20 { public fixture.beta.B20[] field; }");
        s.put("fixture.alpha.A21", "public class A21 { public Object m() { return new fixture.beta.B21[1][1]; } }");
        s.put("fixture.alpha.A22", "public class A22 { public void m() { try { m(); } catch (fixture.beta.B22 e) { } } }");
        s.put("fixture.alpha.A23", "public class A23 { public Object m() { return new fixture.beta.B23.Inner(); } }");
        s.put("fixture.alpha.A24", "public class A24 extends fixture.beta.B24 {}");
        s.put("fixture.alpha.A25", "public class A25 { public void m(@fixture.beta.B33 Object p) {} }");
        s.put("fixture.alpha.A26", "public abstract class A26 implements fixture.beta.B26 {}");
        s.put("fixture.alpha.A27", "public class A27 { public int m(fixture.beta.B29 e) { switch (e) { case ONE: return 1; default: return 2; } } }");
        s.put("fixture.alpha.A28", "public class A28 { public Object m(fixture.beta.api.Facade f) { return f.generic(); } }");
        s.put("fixture.alpha.A29", "public class A29 { public Object m() { return fixture.beta.api.Facade.FIELD; } }");
        s.put("fixture.alpha.A30", "public class A30 { public Object m() { return fixture.beta.api.Facade.arrays(); } }");
        s.put("fixture.alpha.A31", "public class A31 { public Object m() { return new fixture.beta.B34[1]; } }");
        s.put("fixture.alpha.A32", "public class A32 { public Object m() { return fixture.beta.B35[].class; } }");
        s.put("fixture.alpha.A33", "public class A33 { public Object m() { return fixture.beta.B38.S; } }");
        s.put("fixture.alpha.A34", "public class A34 { public void m() { fixture.beta.B39 unused = null; } }");
        return s;
    }

    private void compile() throws IOException {
        classes = Files.createDirectories(root.resolve("classes"));
        Path src = root.resolve("src");
        List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
        for (var entry : sources().entrySet()) {
            String name = entry.getKey();
            String pkg = name.substring(0, name.lastIndexOf('.'));
            Path file = src.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package " + pkg + "; " + entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        for (ModuleDescriptor module : modules()) {
            Path file = classes.resolve("META-INF/topolith/modules/" + module.id() + ".properties");
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                Descriptors.write(module, out);
            }
        }
    }

    private JavaClasses imported() {
        return new ClassFileImporter().importPath(classes);
    }

    private static Set<String> pairs(String text) {
        Set<String> pairs = new TreeSet<>();
        Matcher matcher = PAIR.matcher(text);
        while (matcher.find()) {
            pairs.add(matcher.group(1) + " -> " + matcher.group(2));
        }
        return pairs;
    }

    /** ArchUnit's non-API access violations over the compiled classes, as {@code from -> to} pairs. */
    private Set<String> archUnitPairs() {
        Set<String> pairs = new TreeSet<>();
        var result = TopolithArchitecture.rule(TopolithArchitecture.Rule.NON_API_ACCESS, modules(), List.<ApiDescriptor>of()).evaluate(imported());
        result.getFailureReport().getDetails().forEach(detail -> pairs.add(detail.substring(0, detail.indexOf(": "))));
        return pairs;
    }

    /** The pairs of the startup TL0012, or an empty set when startup succeeds; {@code extra} is a further root. */
    private Set<String> startupPairs(Path... roots) throws Exception {
        var urls = new java.net.URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        Set<String> found = new TreeSet<>();
        try (var loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withConfiguration(AutoConfigurations.of(TopolithAutoConfiguration.class))
                    .withPropertyValues("topolith.modules.alpha.mode=internal", "topolith.modules.beta.mode=none")
                    .run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) {
                            error = error.getCause();
                        }
                        if (error == null) {
                            assertNull(context.getStartupFailure());
                            return;
                        }
                        TopolithException failure = (TopolithException) error;
                        assertEquals(TopolithCode.TL0012, failure.code(), failure.getMessage());
                        found.addAll(pairs(failure.getMessage()));
                    });
        }
        return found;
    }

    @Test
    void ac_arch_15_startup_reference_set_equals_archunits_non_api_set() throws Exception {
        compile();
        Set<String> archUnit = archUnitPairs();
        Set<String> startup = startupPairs(classes);
        assertTrue(archUnit.size() > 20, archUnit.toString());
        Set<String> onlyStartup = new TreeSet<>(startup);
        onlyStartup.removeAll(archUnit);
        Set<String> onlyArchUnit = new TreeSet<>(archUnit);
        onlyArchUnit.removeAll(startup);
        assertEquals("startup only: []\nArchUnit only: []",
                "startup only: " + onlyStartup + "\nArchUnit only: " + onlyArchUnit);
    }

    @Test
    void ac_arch_15_frozen_violations_start_after_generate_and_a_new_one_fails() throws Exception {
        compile();
        ArchConfiguration.get().setProperty(STORE + "allowStoreCreation", "true");
        TopolithArchitecture.verifyFrozen(modules(), List.of(), imported());
        ArchConfiguration.get().setProperty(STORE + "allowStoreCreation", "false");
        List<Path> written = TopolithBaseline.generate(modules(), List.of(), classes);
        assertEquals(List.of(classes.resolve("META-INF/topolith/baseline/alpha.txt")), written);
        assertEquals(Set.of(), startupPairs(classes));

        Path source = root.resolve("src/fixture/alpha/Fresh.java");
        Files.writeString(source, "package fixture.alpha; public class Fresh { public fixture.beta.B01 f; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                "-cp", classes.toString(), source.toString()));
        assertEquals(Set.of("fixture.alpha.Fresh -> fixture.beta.B01"), startupPairs(classes));
    }

    @Test
    void ac_arch_15_a_baseline_generated_into_a_separate_resource_directory_is_read_at_startup() throws Exception {
        compile();
        ArchConfiguration.get().setProperty(STORE + "allowStoreCreation", "true");
        TopolithArchitecture.verifyFrozen(modules(), List.of(), imported());
        ArchConfiguration.get().setProperty(STORE + "allowStoreCreation", "false");
        Path resources = Files.createDirectories(root.resolve("resources"));
        List<Path> written = TopolithBaseline.generate(modules(), List.of(), resources, "alpha");
        assertEquals(List.of(resources.resolve("META-INF/topolith/baseline/alpha.txt")), written);
        assertFalse(Files.exists(classes.resolve("META-INF/topolith/baseline/alpha.txt")));
        assertEquals(Set.of(), startupPairs(classes, resources));
    }
}
