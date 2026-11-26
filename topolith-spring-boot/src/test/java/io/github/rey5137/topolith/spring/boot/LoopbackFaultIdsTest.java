package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.Topology;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5.4: loopback keys of a dashed module id, written as an environment variable would bind them (D-50). */
class LoopbackFaultIdsTest {
    private static Topology topology(String mode) {
        ModuleDescriptor module = ModuleDescriptor.builder("order-service").packageName("fixture.orderservice")
                .apiPackages(List.of("fixture.orderservice.api")).apiVersion("1.0.0").build();
        ApiDescriptor api = ApiDescriptor.builder("order-service").apiPackages(List.of("fixture.orderservice.api"))
                .apiVersion("1.0.0").build();
        return Topology.resolve(List.of(module), List.of(api), Map.of("order-service", mode), null);
    }

    private static List<Diagnostic> check(String mode, String key, String value) {
        List<Diagnostic> problems = new ArrayList<>();
        LoopbackFaults.check(Binder.get(new MockEnvironment().withProperty(key, value)), topology(mode), problems);
        return problems;
    }

    @Test
    void ac_ext_39_a_valid_key_of_a_dashed_loopback_id_without_its_dashes_is_known() {
        String key = "topolith.modules.orderservice.loopback.fault.rate";
        assertEquals(List.of(), check("loopback", key, "0.5"));
        assertEquals(0.5, LoopbackFaults.read(Binder.get(new MockEnvironment().withProperty(key, "0.5")),
                "order-service").rate());
    }

    @Test
    void ac_ext_39_a_key_of_a_dashed_module_that_is_not_loopback_fails_naming_the_key() {
        String key = "topolith.modules.orderservice.loopback.latency";
        List<Diagnostic> problems = check("none", key, "1s");
        assertEquals(1, problems.size());
        assertEquals(TopolithCode.TL0003, problems.get(0).code());
        assertTrue(problems.get(0).toString().contains(key), problems.get(0).toString());
    }
}
