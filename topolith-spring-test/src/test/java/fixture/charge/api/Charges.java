package fixture.charge.api;

import org.springframework.data.domain.Page;

/** A payment-like contract whose provider breaks by-value parity on purpose (AC-TEST-15). */
public interface Charges {
    /** Charges the order; the provider stamps its status on the order it was handed. */
    Receipt charge(ChargeOrder order);

    /** Authorizes the order and keeps the receipt it returns. */
    Receipt authorize(ChargeOrder order);

    /** Captures an authorized receipt by changing the one {@link #authorize} returned. */
    void capture(String id);

    /** A contract type no HTTP call can carry (R-ARCH-02). */
    Page<Receipt> history();
}
