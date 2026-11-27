package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.spring.boot.loopfixture.host.LoopbackHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNull;

/** M7.3: the validation of {@code remote.retry.*} and {@code remote.circuit-breaker.*} (R-EXT-14, R-EXT-15). */
class ResilienceSettingsTest {
    private static final String RETRY = "topolith.modules.wallet.remote.retry.";
    private static final String BREAKER = "topolith.modules.wallet.remote.circuit-breaker.";
    private static final String WALLET = "io.github.rey5137.topolith.spring.boot.loopfixture.wallet.api."
            + "WalletContract";

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        LoopbackTest.descriptors(root, "wallet", WALLET);
        LoopbackTest.descriptors(root, "shop");
    }

    private void fails(TopolithCode code, String key, String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            LoopbackTest.server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(properties)
                    .run(context -> LoopbackTest.fails(context.getStartupFailure(), code, key));
        }
    }

    private void starts(String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            LoopbackTest.server(LoopbackHost.class).withClassLoader(loader).withPropertyValues(properties)
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test
    void ac_ext_67_a_key_on_an_internal_none_or_unknown_module_fails_with_tl0003() throws Exception {
        for (String key : List.of("topolith.modules.shop.remote.retry.max-attempts",
                "topolith.modules.shop.remote.circuit-breaker.enabled",
                "topolith.modules.other.remote.retry.initial-backoff",
                "topolith.modules.other.remote.circuit-breaker.sliding-window-size")) {
            fails(TopolithCode.TL0003, key, key + "=1");
        }
        fails(TopolithCode.TL0003, "topolith.modules.shop.remote.retry.multiplier",
                "topolith.modules.shop.mode=none", "topolith.modules.shop.remote.retry.multiplier=2");
    }

    @Test
    void ac_ext_67_an_unknown_or_scalar_key_fails_with_tl0003() throws Exception {
        fails(TopolithCode.TL0003, RETRY + "attempts", RETRY + "attempts=3");
        fails(TopolithCode.TL0003, BREAKER + "threshold", BREAKER + "threshold=3");
        fails(TopolithCode.TL0003, "topolith.modules.wallet.remote.retry", "topolith.modules.wallet.remote.retry=3");
    }

    @Test
    void ac_ext_67_each_invalid_value_fails_with_tl0003_naming_the_key() throws Exception {
        for (String pair : List.of("retry.max-attempts=0", "retry.max-attempts=11", "retry.max-attempts=two",
                "retry.initial-backoff=-1s", "retry.initial-backoff=soon", "retry.max-backoff=-1s",
                "retry.initial-backoff=2s,retry.max-backoff=1s", "retry.multiplier=0.9", "retry.multiplier=NaN",
                "retry.multiplier=Infinity", "retry.safe-methods-idempotent=yes",
                "circuit-breaker.enabled=1", "circuit-breaker.failure-rate-threshold=0",
                "circuit-breaker.failure-rate-threshold=100.5", "circuit-breaker.failure-rate-threshold=NaN",
                "circuit-breaker.sliding-window-size=0", "circuit-breaker.minimum-number-of-calls=0",
                "circuit-breaker.sliding-window-size=5,circuit-breaker.minimum-number-of-calls=6",
                "circuit-breaker.wait-duration-in-open-state=0s",
                "circuit-breaker.wait-duration-in-open-state=500us",
                "circuit-breaker.wait-duration-in-open-state=-5s")) {
            String[] parts = pair.split(",");
            String[] values = new String[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = "topolith.modules.wallet.remote." + parts[i];
            }
            String last = parts[parts.length - 1];
            fails(TopolithCode.TL0003, "topolith.modules.wallet.remote." + last.substring(0, last.indexOf('=')),
                    values);
        }
    }

    @Test
    void ac_ext_67_valid_tuning_keys_start_while_retry_and_the_breaker_are_off() throws Exception {
        starts(RETRY + "initial-backoff=PT0.01S", RETRY + "max-backoff=20ms", RETRY + "multiplier=1.5",
                RETRY + "safe-methods-idempotent=true", RETRY + "max-attempts=1", BREAKER + "enabled=false",
                BREAKER + "failure-rate-threshold=75.5", BREAKER + "sliding-window-size=20",
                BREAKER + "minimum-number-of-calls=20", BREAKER + "wait-duration-in-open-state=30s");
        starts(RETRY + "initial-backoff=2s");
        starts(BREAKER + "sliding-window-size=5");
    }

    @Test
    void ac_ext_67_retry_without_the_cloud_resilience_configuration_fails_with_tl0041() throws Exception {
        fails(TopolithCode.TL0041, RETRY + "max-attempts", RETRY + "max-attempts=2");
    }

    @Test
    void ac_ext_67_the_breaker_without_the_cloud_resilience_configuration_fails_with_tl0041() throws Exception {
        fails(TopolithCode.TL0041, BREAKER + "enabled", BREAKER + "enabled=true");
    }
}
