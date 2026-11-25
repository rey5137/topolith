package io.github.rey5137.topolith.spring.boot;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** N8: with no problem+json converter the resolver still writes the contract problem, escaped. */
class ProblemFallbackWriterTest {
    @Test
    void ac_ext_29_the_hand_written_problem_escapes_quotes_newlines_and_backslashes() throws Exception {
        String detail = "say \"hi\"\nline2\\tab\t\u0001 café";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setType(URI.create(ModuleExceptionResolver.TYPE));
        problem.setProperty(ModuleExceptionResolver.EXCEPTION, "a.b.\"Odd\"");
        problem.setProperty(ModuleExceptionResolver.MODULE, "user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModuleExceptionResolver.writeJson(problem, response);

        assertEquals(503, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertEquals(ModuleExceptionResolver.TYPE, body.get("type").asString());
        assertEquals(503, body.get("status").asInt());
        assertEquals(detail, body.get("detail").asString());
        assertEquals("a.b.\"Odd\"", body.get(ModuleExceptionResolver.EXCEPTION).asString());
        assertEquals("user", body.get(ModuleExceptionResolver.MODULE).asString());
    }
}
