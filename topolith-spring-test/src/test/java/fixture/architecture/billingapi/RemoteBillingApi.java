package fixture.architecture.billingapi;

/** An API package outside the billing module's own package (a separate API artifact). */
public interface RemoteBillingApi {
    String invoice(String order);
}
