package io.github.rey5137.topolith.spring.test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TopolithMockServerTest {
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
            assertEquals(new TopolithMockServer.Request("POST", "/payment/pay", "request"), server.requests().get(0));
            server.reset();
            assertTrue(server.requests().isEmpty());
            assertEquals(404, HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
