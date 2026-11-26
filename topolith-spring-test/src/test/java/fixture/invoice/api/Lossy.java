package fixture.invoice.api;

/** The setter normalizes, so the copy serializes differently from the original. */
public class Lossy {
    private String code;

    public Lossy() { }

    public Lossy(String code) { this.code = code; }

    public String getCode() { return code; }

    public void setCode(String code) { this.code = code == null ? null : code.toUpperCase(); }
}
