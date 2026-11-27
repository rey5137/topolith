package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountFailure;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountNotFoundException;
import io.github.rey5137.topolith.spring.boot.errorfixture.checked.api.AbstractCheckedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.checked.api.BadStatusCheckedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.checked.api.CheckedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.checked.api.BrokenCheckedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.checked.api.OutsideCheckedContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.mute.api.MuteContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.status.api.StatusContract;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static io.github.rey5137.topolith.spring.boot.Fixtures.ERRORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2.3: which exceptions are declared (D-43), and TL0027 for a contract whose exceptions cannot cross HTTP. */
class ContractExceptionsTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        contracts(root, "account", AccountContract.class);
        contracts(root, "checked", CheckedContract.class);
        contracts(root, "mute", MuteContract.class);
        contracts(root, "status", StatusContract.class);
    }

    /** The API descriptor and the module descriptor of {@code id} under {@link Fixtures#ERRORS}. */
    static void contracts(Path root, String id, Class<?>... contracts) throws IOException {
        String module = ERRORS + "." + id.replace("-", "");
        contracts(root, id, module, module + ".api", contracts);
    }

    /** Descriptors for module {@code id} whose contracts live in {@code apiPackage}, whatever the id is. */
    static void contracts(Path root, String id, String module, String apiPackage, Class<?>... contracts)
            throws IOException {
        List<String> names = Arrays.stream(contracts).map(Class::getName).toList();
        write(root, "apis", id, out -> Descriptors.write(ApiDescriptor.builder(id).apiPackages(List.of(apiPackage))
                .apiVersion("1.0.0").provides(names).build(), out));
        write(root, "modules", id, out -> Descriptors.write(ModuleDescriptor.builder(id).packageName(module)
                .apiPackages(List.of(apiPackage)).apiVersion("1.0.0").provides(names).build(), out));
    }

    private interface Body {
        void write(Writer out) throws IOException;
    }

    private static void write(Path root, String kind, String id, Body body) throws IOException {
        Path file = root.resolve("META-INF/topolith/" + kind + "/" + id + ".properties");
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            body.write(out);
        }
    }

    private void run(Consumer<AssertableApplicationContext> check, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.account.mode=none",
                            "topolith.modules.checked.mode=none", "topolith.modules.mute.mode=none",
                            "topolith.modules.status.mode=none")
                    .withPropertyValues(properties).run(context -> {
                        check.accept(context);
                        if (!Arrays.asList(properties).contains("topolith.modules.mute.mode=internal")
                                && !Arrays.asList(properties).contains("topolith.modules.mute.mode=external")) {
                            // INV-2: a none module's api package is never scanned or loaded
                            assertEquals(List.of(), loader.requested(ERRORS + ".mute"));
                        }
                    });
        }
    }

    private static void assertTl0027(AssertableApplicationContext context, String... expected) {
        Throwable cause = rootFailure(context);
        assertNotNull(cause, String.valueOf(context.getStartupFailure()));
        TopolithException failure = (TopolithException) cause;
        assertEquals(List.of(TopolithCode.TL0027), failure.diagnostics().stream().map(d -> d.code()).distinct()
                .toList());
        for (String text : expected) {
            assertTrue(failure.getMessage().contains(text), failure.getMessage());
        }
    }

    @Test
    void ac_ext_09_declared_is_the_nearest_concrete_class_in_a_known_api_package() {
        Set<String> api = Set.of(ERRORS + ".account.api");
        RuntimeException subclass = new AccountNotFoundException() {
        };
        assertEquals(AccountNotFoundException.class, ContractExceptions.declared(subclass.getClass(), api));
        assertEquals(AccountException.class, ContractExceptions.declared(AccountException.class, api));
        // abstract classes are walked past; nothing concrete above them is in an api package
        assertNull(ContractExceptions.declared(AccountFailure.class, api));
        assertEquals(IllegalStateException.class, ContractExceptions.declared(IllegalStateException.class, api));
        assertNull(ContractExceptions.declared(AccountException.class, Set.of(ERRORS + ".account")));
        assertEquals(ModuleUnavailableException.class,
                ContractExceptions.declared(ModuleUnavailableException.class, Set.of()));
        assertNull(ContractExceptions.declared(IOException.class, api));
    }

    @Test
    void ac_ext_30_checked_exception_on_a_contract_method_fails_in_a_monolith_and_when_external() throws Exception {
        run(context -> assertTl0027(context, "'checked'", CheckedContract.class.getName() + ".read",
                IOException.class.getName()), "topolith.modules.checked.mode=internal");
        run(context -> assertTl0027(context, "'checked'", CheckedContract.class.getName() + ".read"),
                "topolith.modules.checked.mode=external", "topolith.modules.checked.remote.url=http://localhost:1");
    }

    @Test
    void ac_ext_70_and_71_checked_contract_validation() throws Exception {
        contracts(root, "checked", ERRORS + ".valid", ERRORS + ".valid.api",
                io.github.rey5137.topolith.spring.boot.errorfixture.valid.api.ValidCheckedContract.class);
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.checked.mode=internal");
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.checked.mode=external",
                "topolith.modules.checked.remote.url=http://localhost:1");
    }

    @Test
    void ac_ext_71_checked_exceptions_must_be_in_api_and_rebuildable() throws Exception {
        contracts(root, "checked", OutsideCheckedContract.class);
        run(context -> assertTl0027(context, "OutsideCheckedException"), "topolith.modules.checked.mode=internal");
        contracts(root, "checked", BrokenCheckedContract.class);
        run(context -> assertTl0027(context, "BrokenCheckedException", "cannot rebuild"),
                "topolith.modules.checked.mode=internal");
        contracts(root, "checked", AbstractCheckedContract.class);
        run(context -> assertTl0027(context, "AbstractCheckedException", "cannot rebuild"),
                "topolith.modules.checked.mode=internal");
        contracts(root, "checked", BadStatusCheckedContract.class);
        run(context -> assertTl0027(context, "BadStatusCheckedException", "not an error status"),
                "topolith.modules.checked.mode=internal");
    }

    @Test
    void ac_ext_71_unrebuildable_checked_api_subclass_fails_startup() throws Exception {
        contracts(root, "checked", ERRORS + ".child", ERRORS + ".child.api",
                io.github.rey5137.topolith.spring.boot.errorfixture.child.api.ChildContract.class);
        run(context -> assertTl0027(context, "ChildException", "HiddenChild", "BadStatusChild",
                "cannot rebuild", "not an error status"),
                "topolith.modules.checked.mode=internal");
    }

    @Test
    void ac_ext_30_unreadable_contract_methods_produce_tl0027() {
        String name = "broken.Contract";
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String requested) throws ClassNotFoundException {
                if (!requested.equals(name)) {
                    throw new ClassNotFoundException(requested);
                }
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                        "broken/Contract", null, "java/lang/Object", null);
                writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "read", "()Lmissing/Result;", null,
                        null).visitEnd();
                writer.visitEnd();
                byte[] bytes = writer.toByteArray();
                return defineClass(requested, bytes, 0, bytes.length);
            }
        };
        List<Diagnostic> problems = new ArrayList<>();
        ContractExceptions.check(Set.of("broken"), Map.of("broken", Set.of(name)), Map.of(), loader, problems);
        assertEquals(List.of(TopolithCode.TL0027), problems.stream().map(Diagnostic::code).toList());
        assertTrue(problems.get(0).description().contains("cannot be read"));
        assertTrue(problems.get(0).description().contains("missing/Result"));
        assertTrue(problems.get(0).action().contains("referenced classes"));
    }

    @Test
    void ac_ext_73_jdk_exceptions_use_the_nearest_listed_class() {
        assertEquals(IllegalArgumentException.class,
                ContractExceptions.declared(NumberFormatException.class, Set.of()));
        assertEquals(IllegalStateException.class, ContractExceptions.declared(IllegalStateException.class, Set.of()));
        assertEquals(UnsupportedOperationException.class,
                ContractExceptions.declared(UnsupportedOperationException.class, Set.of()));
    }

    @Test
    void ac_ext_30_exception_a_caller_cannot_rebuild_fails() throws Exception {
        run(context -> {
            assertTl0027(context, "'mute'", ERRORS + ".mute.api.MuteException", ERRORS + ".mute.api.HiddenException");
            assertEquals(2, ((TopolithException) rootFailure(context)).diagnostics().size());
        }, "topolith.modules.mute.mode=internal");
        run(context -> assertTl0027(context, "'mute'", ERRORS + ".mute.api.MuteException"),
                "topolith.modules.mute.mode=external", "topolith.modules.mute.remote.url=http://localhost:1");
    }

    @Test
    void ac_ext_30_declared_exception_with_a_non_error_status_fails() throws Exception {
        String api = ERRORS + ".status.api.";
        run(context -> {
            assertTl0027(context, "'status'", api + "AcceptedException with @ResponseStatus ACCEPTED",
                    api + "InheritedStatusException with @ResponseStatus ACCEPTED",
                    api + "MovedException with @ResponseStatus FOUND", "4xx or 5xx");
            TopolithException failure = (TopolithException) rootFailure(context);
            assertEquals(3, failure.diagnostics().size(), failure.getMessage());
            assertFalse(failure.getMessage().contains("ConflictException"), failure.getMessage());
        }, "topolith.modules.status.mode=internal");
    }

    @Test
    void ac_ext_30_none_modules_and_sound_contracts_pass() throws Exception {
        run(context -> assertNull(context.getStartupFailure()));
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.account.mode=internal");
        run(context -> assertNull(context.getStartupFailure()), "topolith.modules.account.mode=external",
                "topolith.modules.account.remote.url=http://localhost:1");
        assertFalse(ContractExceptions.rebuildable(AccountFailure.class));
        assertTrue(ContractExceptions.rebuildable(AccountNotFoundException.class));
    }

    @Test
    void ac_ext_30_the_scan_reads_api_classes_without_loading_them() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            List<Diagnostic> problems = new ArrayList<>();
            ContractExceptions.check(Set.of("mute"), Map.of(), Map.of("mute", Set.of(ERRORS + ".mute.api")), loader,
                    problems);
            assertEquals(2, problems.size(), problems.toString());
            // INV-2: an external module's api classes are read as class files, not loaded
            assertEquals(List.of(), loader.requested(ERRORS + ".mute"));
        }
    }

    private static Throwable rootFailure(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        return cause;
    }
}
