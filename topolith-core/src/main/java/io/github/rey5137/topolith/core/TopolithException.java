package io.github.rey5137.topolith.core;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A coded topology or configuration failure (D-28). It carries one or more failure diagnostics; warnings are
 * returned as values and never thrown. Only the {@code TLnnnn: } prefix of each message line and {@link #code()}
 * are contract.
 */
@Incubating
public class TopolithException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final List<Diagnostic> diagnostics;

    public TopolithException(Diagnostic diagnostic) {
        this(List.of(diagnostic));
    }

    public TopolithException(List<Diagnostic> diagnostics) {
        super(message(diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    private static String message(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("a TopolithException needs at least one diagnostic");
        }
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() != TopolithCode.Severity.FAILURE) {
                throw new IllegalArgumentException("warning " + diagnostic.code().id() + " is returned, not thrown");
            }
        }
        return diagnostics.stream().map(Diagnostic::toString).collect(Collectors.joining("\n"));
    }

    /** The diagnostics, never empty. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    /** The first diagnostic's code. */
    public TopolithCode code() {
        return diagnostics.get(0).code();
    }
}
