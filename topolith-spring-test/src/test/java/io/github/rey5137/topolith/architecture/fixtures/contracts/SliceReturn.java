package io.github.rey5137.topolith.architecture.fixtures.contracts;

public interface SliceReturn {
    org.springframework.data.domain.Slice<String> load(String id);
}
