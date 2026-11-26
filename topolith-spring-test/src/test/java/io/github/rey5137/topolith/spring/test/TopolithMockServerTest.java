package io.github.rey5137.topolith.spring.test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopolithMockServerTest {
    private static int status(TopolithMockServer server, String method, String path) throws Exception {
        var request = HttpRequest.newBuilder(server.url().resolve(path)).timeout(java.time.Duration.ofSeconds(2))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void ac_test_03_unstubbed_version_check_answers_404_and_is_not_recorded() throws Exception {
        try (var server = new TopolithMockServer("payment")) {
            assertEquals(404, status(server, "GET", "/payment/.topolith/api-version"));
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void ac_test_03_stubbed_version_check_is_served_and_not_recorded() throws Exception {
        try (var server = new TopolithMockServer("payment")) {
            server.stub("GET", "/.topolith/api-version", 200, "1.0.0");
            assertEquals(200, status(server, "GET", "/payment/.topolith/api-version"));
            assertTrue(server.requests().isEmpty());
        }
    }

    @Test
    void ac_test_03_only_the_exact_version_check_is_excluded() throws Exception {
        try (var server = new TopolithMockServer("payment")) {
            status(server, "POST", "/payment/.topolith/api-version");
            status(server, "GET", "/payment/.topolith/other");
            status(server, "GET", "/other/.topolith/api-version");
            assertEquals(java.util.List.of(
                    new TopolithMockServer.Request("POST", "/payment/.topolith/api-version", ""),
                    new TopolithMockServer.Request("GET", "/payment/.topolith/other", ""),
                    new TopolithMockServer.Request("GET", "/other/.topolith/api-version", "")), server.requests());
        }
    }

    @Test
    void ac_test_03_empty_204_has_no_content_type_or_chunked_body() throws Exception {
        assertEmptyResponse(204, "");
    }

    @Test
    void ac_test_03_null_200_has_no_content_type_or_chunked_body() throws Exception {
        assertEmptyResponse(200, null);
    }

    private void assertEmptyResponse(int status, String body) throws Exception {
        try (var server = new TopolithMockServer("payment")) {
            server.stub("GET", "/empty", status, body);
            var request = HttpRequest.newBuilder(server.url().resolve("/payment/empty"))
                    .timeout(java.time.Duration.ofSeconds(2)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode());
            assertEquals("", response.body());
            assertTrue(response.headers().firstValue("Content-Type").isEmpty());
            assertTrue(response.headers().firstValue("Transfer-Encoding").isEmpty());
        }
    }

    @Test
    void ac_test_04_problem_type_matches_provider_and_records_requests() throws Exception {
        var type = Class.forName("io.github.rey5137.topolith.spring.boot.ModuleExceptionResolver")
                .getDeclaredField("TYPE");
        type.setAccessible(true);
        try (var server = new TopolithMockServer("payment")) {
            server.problem("POST", "/pay", 400, IllegalArgumentException.class, "bad \"input\"\n");
            var request = HttpRequest.newBuilder(server.url().resolve("/payment/pay"))
                    .POST(HttpRequest.BodyPublishers.ofString("request")).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertEquals("application/problem+json", response.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(response.body().contains((String) type.get(null)));
            assertTrue(response.body().contains("bad \\\"input\\\"\\n"));
            assertTrue(response.body().contains("\"title\":\"Bad Request\""));
            assertEquals(new TopolithMockServer.Request("POST", "/payment/pay", "request"), server.requests().get(0));
            server.reset();
            assertTrue(server.requests().isEmpty());
            assertEquals(404, HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void ac_test_04_explicit_status_must_be_an_error_status() {
        try (var server = new TopolithMockServer("payment")) {
            for (int status : new int[] {200, 204, 302, 399, 600}) {
                assertThrows(IllegalArgumentException.class,
                        () -> server.problem("GET", "/p", status, IllegalStateException.class, "x"));
            }
            assertDoesNotThrow(() -> server.problem("GET", "/p", 409, IllegalStateException.class, "x"));
            assertDoesNotThrow(() -> server.problem("GET", "/p", 503, IllegalStateException.class, "x"));
        }
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.OK)
    static class OddStatus extends RuntimeException { }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.GONE)
    static class GoneStatus extends OddStatus { }

    @Test
    void ac_test_04_derived_status_coerces_non_error_to_500_and_reads_merged_annotation() throws Exception {
        try (var server = new TopolithMockServer("payment")) {
            server.problem("GET", "/odd", OddStatus.class, "x");
            server.problem("GET", "/gone", GoneStatus.class, "x");
            server.problem("GET", "/plain", IllegalStateException.class, "x");
            for (var expected : java.util.Map.of("/odd", 500, "/gone", 410, "/plain", 500).entrySet()) {
                var request = HttpRequest.newBuilder(server.url().resolve("/payment" + expected.getKey())).build();
                assertEquals(expected.getValue(), HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString()).statusCode(), expected.getKey());
            }
        }
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
    static class ConflictStatus extends RuntimeException { }

    @Test
    void ac_test_04_problem_rejects_module_unavailable_exception() {
        try (var server = new TopolithMockServer("payment")) {
            // the type is final, so the subclass check is isAssignableFrom on the type itself
            var type = io.github.rey5137.topolith.contract.ModuleUnavailableException.class;
            var derived = assertThrows(IllegalArgumentException.class, () -> server.problem("GET", "/p", type, "x"));
            assertTrue(derived.getMessage().contains("unavailable("), derived.getMessage());
            assertThrows(IllegalArgumentException.class, () -> server.problem("GET", "/p", 503, type, "x"));
        }
    }

    @Test
    void ac_test_04_explicit_status_must_match_response_status_annotation() {
        try (var server = new TopolithMockServer("payment")) {
            assertThrows(IllegalArgumentException.class,
                    () -> server.problem("GET", "/p", 400, ConflictStatus.class, "x"));
            assertDoesNotThrow(() -> server.problem("GET", "/p", 409, ConflictStatus.class, "x"));
        }
    }

    @Test
    void ac_test_03_reset_keeps_unstubbed_paths_answering_like_a_fresh_server() throws Exception {
        try (var server = new TopolithMockServer("payment")) {
            var request = HttpRequest.newBuilder(server.url().resolve("/payment/.topolith/api-version")).build();
            int fresh = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            server.stub("GET", "/.topolith/api-version", 200, "1");
            assertEquals(200, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            server.reset();
            assertTrue(server.requests().isEmpty());
            assertEquals(fresh, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
                    .statusCode());
            assertTrue(server.requests().isEmpty());
        }
    }
}
