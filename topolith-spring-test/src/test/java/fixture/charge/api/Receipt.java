package fixture.charge.api;

/** A deliberately mutable result. */
public class Receipt {
    public String id;
    public String reference;
    public String status;

    public Receipt() { }

    public Receipt(String id, String reference, String status) {
        this.id = id;
        this.reference = reference;
        this.status = status;
    }
}
