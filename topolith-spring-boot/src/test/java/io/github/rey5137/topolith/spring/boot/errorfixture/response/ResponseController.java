package io.github.rey5137.topolith.spring.boot.errorfixture.response;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ResponseController {
    @GetMapping("/reference")
    ResponseEntity<String> reference() {
        return answer();
    }

    @GetMapping("/headers-reference")
    HttpHeaders headersReference() {
        return headersAnswer();
    }

    static HttpHeaders headersAnswer() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("A", "b");
        return headers;
    }

    static ResponseEntity<String> answer() {
        return ResponseEntity.status(201).header("X-Result", "created").body("entity body");
    }
}
