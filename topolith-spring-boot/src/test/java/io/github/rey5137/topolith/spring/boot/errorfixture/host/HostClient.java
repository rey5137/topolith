package io.github.rey5137.topolith.spring.boot.errorfixture.host;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** The host's own HTTP service, in its own group: Topolith never advises it. */
@HttpExchange("/host")
public interface HostClient {
    @GetExchange("/status")
    String status();
}
