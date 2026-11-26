package io.github.rey5137.topolith.core;

/** The 0.1 diagnostic codes of the catalog (reference/90-catalog.md), each with its severity. */
@Incubating
public enum TopolithCode {
    TL0001(Severity.FAILURE),
    TL0002(Severity.FAILURE),
    TL0003(Severity.FAILURE),
    TL0004(Severity.FAILURE),
    TL0005(Severity.FAILURE),
    TL0006(Severity.FAILURE),
    TL0007(Severity.FAILURE),
    TL0008(Severity.FAILURE),
    TL0009(Severity.FAILURE),
    TL0010(Severity.WARNING),
    TL0011(Severity.WARNING),
    TL0012(Severity.FAILURE),
    TL0013(Severity.FAILURE),
    TL0014(Severity.WARNING),
    TL0015(Severity.WARNING),
    TL0016(Severity.FAILURE),
    TL0017(Severity.FAILURE),
    TL0018(Severity.FAILURE),
    TL0019(Severity.FAILURE),
    TL0020(Severity.FAILURE),
    TL0021(Severity.FAILURE),
    TL0022(Severity.FAILURE),
    TL0023(Severity.WARNING),
    TL0024(Severity.FAILURE),
    TL0025(Severity.FAILURE),
    TL0026(Severity.FAILURE),
    TL0027(Severity.FAILURE),
    TL0028(Severity.FAILURE),
    TL0029(Severity.FAILURE),
    TL0030(Severity.FAILURE),
    TL0031(Severity.FAILURE),
    TL0032(Severity.FAILURE);

    /** Whether a diagnostic fails startup or is only reported. */
    @Incubating
    public enum Severity {
        FAILURE,
        WARNING
    }

    private final Severity severity;

    TopolithCode(Severity severity) {
        this.severity = severity;
    }

    /** The stable code, such as {@code TL0006}. */
    public String id() {
        return name();
    }

    /**
     * The severity of the code unless a later layer overrides it (D-38); {@link Diagnostic#severity()} carries the
     * effective one.
     */
    public Severity defaultSeverity() {
        return severity;
    }
}
