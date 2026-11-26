package fixture.invoice.api;

public class Line {
    public String sku;
    public int qty;

    public Line() { }

    public Line(String sku, int qty) {
        this.sku = sku;
        this.qty = qty;
    }
}
