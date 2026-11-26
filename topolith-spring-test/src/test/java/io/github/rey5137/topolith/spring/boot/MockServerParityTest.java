package io.github.rey5137.topolith.spring.boot;

import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import fixture.payment.PaymentRoute;
import fixture.payment.api.PaymentConflict;
import fixture.payment.api.PaymentException;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.test.TopolithMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** The mock server's problems against what {@link ModuleExceptionResolver} writes for the same throw. */
class MockServerParityTest {
    @SuppressWarnings("unchecked")
    private static void assertParity(RuntimeException thrown, java.util.function.Consumer<TopolithMockServer> stub)
            throws Exception {
        URL root = MockServerParityTest.class.getClassLoader().getResource("test-descriptors/");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root}, MockServerParityTest.class.getClassLoader());
                var server = new TopolithMockServer("payment")) {
            var found = DescriptorDiscovery.discover(loader);
            Topology topology = Topology.resolve(found.modules(), found.apis(), Map.of("payment", "internal"), null);
            var resolver = new ModuleExceptionResolver(topology, List::of);
            var response = new MockHttpServletResponse();
            var handler = new HandlerMethod(new PaymentRoute(), PaymentRoute.class.getMethod("payment"));
            assertNotNull(resolver.resolveException(new MockHttpServletRequest(), response, handler, thrown));

            stub.accept(server);
            var request = HttpRequest.newBuilder(server.url().resolve("/payment/p")).build();
            var mock = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(response.getStatus(), mock.statusCode());
            assertTrue(response.getContentType().startsWith("application/problem+json"));
            assertTrue(mock.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
            var mapper = JsonMapper.builder().build();
            Map<String, Object> expected = mapper.readValue(response.getContentAsString(), Map.class);
            Map<String, Object> actual = mapper.readValue(mock.body(), Map.class);
            assertEquals(expected, actual);
            assertEquals("urn:topolith:problem:contract-exception", actual.get("type"));
        }
    }

    @Test
    void ac_test_04_declared_status_matches_provider() throws Exception {
        assertParity(new PaymentConflict("taken"),
                server -> server.problem("GET", "/p", PaymentConflict.class, "taken"));
    }

    @Test
    void ac_test_04_default_status_matches_provider() throws Exception {
        assertParity(new PaymentException("declined"),
                server -> server.problem("GET", "/p", PaymentException.class, "declined"));
    }

    @Test
    void ac_test_04_unavailable_matches_provider() throws Exception {
        assertParity(new ModuleUnavailableException("ledger", "secret detail", null),
                server -> server.unavailable("GET", "/p", "ledger"));
    }
}
