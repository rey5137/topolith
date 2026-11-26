package io.github.rey5137.topolith.samples.hosts.hybrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The hybrid host: user internal, payment external over a fixed URL. */
@SpringBootApplication
public class HybridApplication {
    public static void main(String[] args) {
        SpringApplication.run(HybridApplication.class, args);
    }
}
