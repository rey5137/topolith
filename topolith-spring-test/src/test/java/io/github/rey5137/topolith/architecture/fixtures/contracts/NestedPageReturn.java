package io.github.rey5137.topolith.architecture.fixtures.contracts;

import java.util.List;
import java.util.Optional;

public interface NestedPageReturn {
    Optional<List<org.springframework.data.domain.Page<String>>> load(String id);
}
