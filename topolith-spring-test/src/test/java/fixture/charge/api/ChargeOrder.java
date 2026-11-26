package fixture.charge.api;

import java.math.BigDecimal;

/** A deliberately mutable request: public fields a provider can change under its caller in internal mode. */
public class ChargeOrder {
    public String reference;
    public BigDecimal amount;
    public String status;

    public ChargeOrder() { }

    public ChargeOrder(String reference, BigDecimal amount) {
        this.reference = reference;
        this.amount = amount;
    }
}
