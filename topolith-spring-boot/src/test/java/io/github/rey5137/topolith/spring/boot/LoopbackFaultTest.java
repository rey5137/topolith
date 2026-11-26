package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.spring.boot.internal.FaultSequences;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.LoopbackHost;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.WalletCaller;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.WalletController;
import io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api.WalletContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.test.util.AopTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5.4: latency and fault injection in the loopback client (R-EXT-10, D-78). */
class LoopbackFaultTest {
    private static final String WALLET = "io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api."
            + "WalletContract";
    private static final String KEY = "topolith.modules.wallet.loopback.";

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        LoopbackTest.descriptors(root, "wallet", WALLET);
        LoopbackTest.descriptors(root, "shop");
    }

    private void serve(Consumer<AssertableWebApplicationContext> check, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            LoopbackTest.server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(properties)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context);
                    });
        }
    }

    private static WalletContract wallet(AssertableWebApplicationContext context) {
        return context.getBean(WalletCaller.class).wallet;
    }

    private static int ran(AssertableWebApplicationContext context) {
        WalletController provider = AopTestUtils.getUltimateTargetObject(context.getBean(WalletController.class));
        return provider.slowCalls.get();
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String kind(ModuleUnavailableException e) {
        return e.getMessage().replaceAll(".*\\((.*)\\)", "$1");
    }

    /** The calls, out of {@code count}, that failed. */
    private static List<Integer> failed(AssertableWebApplicationContext context, int count) {
        List<Integer> failed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            try {
                wallet(context).slow(0);
            } catch (ModuleUnavailableException e) {
                failed.add(i);
            }
        }
        return failed;
    }

    @Test
    void ac_ext_36_a_call_returns_no_sooner_than_the_latency_after_the_provider_answered() throws Exception {
        serve(context -> {
            long start = System.nanoTime();
            assertEquals("late", wallet(context).slow(0));
            assertTrue((System.nanoTime() - start) / 1_000_000 >= 300, "waited the latency");
        }, KEY + "latency=300ms");
    }

    @Test
    void ac_ext_36_a_latency_at_or_above_the_read_timeout_fails_as_a_timeout_after_the_provider_ran() throws Exception {
        serve(context -> {
            long start = System.nanoTime();
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    () -> wallet(context).slow(0));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertEquals("timeout", kind(e));
            assertEquals(1, ran(context));
            assertTrue(millis >= 400 && millis < 3000, "waited the read timeout, not the latency: " + millis);
        }, KEY + "latency=30s", "topolith.modules.wallet.remote.read-timeout=500ms");
    }

    @Test
    void ac_ext_37_a_connection_failure_fault_does_not_call_the_provider() throws Exception {
        serve(context -> {
            long start = System.nanoTime();
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    () -> wallet(context).slow(0));
            assertEquals("connection failure", kind(e));
            assertEquals("module 'wallet' could not be reached (connection failure)", e.getMessage());
            assertEquals(0, ran(context));
            assertTrue(millis(start) < 2000, "a faulted call is not delayed by the latency");
        }, KEY + "fault.rate=1", KEY + "latency=3s");
    }

    @Test
    void ac_ext_37_an_unavailable_fault_does_not_call_the_provider() throws Exception {
        serve(context -> {
            long start = System.nanoTime();
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    () -> wallet(context).slow(0));
            assertEquals("status 503", kind(e));
            assertEquals(0, ran(context));
            assertTrue(millis(start) < 2000, "a faulted call is not delayed by the latency");
        }, KEY + "fault.rate=1", KEY + "fault.kind=unavailable", KEY + "latency=3s");
    }

    @Test
    void ac_ext_37_a_timeout_fault_calls_the_provider_and_discards_the_response() throws Exception {
        serve(context -> {
            ModuleUnavailableException e = assertThrows(ModuleUnavailableException.class,
                    () -> wallet(context).slow(0));
            assertEquals("timeout", kind(e));
            assertEquals("module 'wallet' could not be reached (timeout)", e.getMessage());
            assertEquals(1, ran(context));
        }, KEY + "fault.rate=1", KEY + "fault.kind=timeout", KEY + "latency=100ms");
    }

    @Test
    void ac_ext_38_rate_zero_fails_no_call_and_rate_one_every_call() throws Exception {
        serve(context -> assertEquals(List.of(), failed(context, 30)), KEY + "fault.rate=0");
        serve(context -> assertEquals(30, failed(context, 30).size()), KEY + "fault.rate=1");
    }

    @Test
    void ac_ext_38_a_restart_makes_a_seeded_sequence_fail_the_same_calls() throws Exception {
        serve(context -> {
            List<Integer> first = failed(context, 40);
            assertTrue(!first.isEmpty() && first.size() < 40, "a mid rate fails some calls: " + first);
            assertTrue(!first.equals(failed(context, 40)), "the sequence goes on without a restart");
            context.getBean(FaultSequences.class).restart();
            assertEquals(first, failed(context, 40));
            context.getBean(FaultSequences.class).restart();
            failed(context, 7);
            context.getBean(FaultSequences.class).restart();
            assertEquals(first, failed(context, 40));
        }, KEY + "fault.rate=0.5", KEY + "fault.seed=42");
    }

    @Test
    void ac_ext_39_a_loopback_key_on_a_module_that_is_not_loopback_fails_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            for (String module : List.of("shop", "other")) {
                String key = "topolith.modules." + module + ".loopback.latency";
                LoopbackTest.server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(key + "=1s")
                        .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0003, key));
            }
            String key = "topolith.modules.shop.loopback.fault.rate";
            LoopbackTest.server(LoopbackHost.class).withClassLoader(loader)
                    .withPropertyValues("topolith.modules.shop.mode=none", key + "=1")
                    .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0003, key));
        }
    }

    @Test
    void ac_ext_39_an_unknown_loopback_key_fails_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            for (String key : List.of(KEY + "delay", KEY + "fault.chance")) {
                LoopbackTest.server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(key + "=1")
                        .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0003, key));
            }
        }
    }

    @Test
    void ac_ext_39_each_invalid_value_fails_with_tl0003_naming_the_key() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            for (String pair : List.of("latency=-1s", "latency=soon", "fault.rate=1.5", "fault.rate=-0.1",
                    "fault.rate=half", "fault.rate=0.5f", "fault.rate=1d", "fault.rate=NaN", "fault.kind=explode", "fault.seed=1.5", "fault.seed=abc")) {
                String key = KEY + pair.substring(0, pair.indexOf('='));
                LoopbackTest.server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(KEY + pair)
                        .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0003, key));
            }
        }
    }

    @Test
    void ac_ext_39_valid_values_start_and_a_module_without_loopback_keys_is_untouched() throws Exception {
        serve(context -> assertEquals("late", wallet(context).slow(0)), KEY + "latency=PT0.01S", KEY + "fault.rate=0",
                KEY + "fault.kind=Timeout", KEY + "fault.seed=-5");
    }

    @Test
    void ac_ext_36_injection_lives_in_the_loopback_client_only() throws Exception {
        serve(context -> {
            assertEquals(200, ProviderErrorsTest.get(context, "/wallet/slow?millis=0").status());
            assertEquals(1, ran(context));
            assertThrows(ModuleUnavailableException.class, () -> wallet(context).slow(0));
            assertEquals(1, ran(context));
        }, KEY + "fault.rate=1");
    }

    @Test
    void ac_ext_39_a_scalar_loopback_key_fails_with_tl0003() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            LoopbackTest.server(LoopbackHost.class).withClassLoader(loader)
                    .withPropertyValues("topolith.modules.wallet.loopback=1")
                    .run(context -> LoopbackTest.fails(context.getStartupFailure(), TopolithCode.TL0003,
                            "topolith.modules.wallet.loopback"));
        }
    }
}
