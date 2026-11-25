package io.github.rey5137.topolith.architecture.fixtures.contracts;

public interface PageReturn {
    org.springframework.data.domain.Page<String> load(String id);
}
