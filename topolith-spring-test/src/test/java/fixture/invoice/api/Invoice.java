package fixture.invoice.api;

import java.util.ArrayList;
import java.util.List;

public class Invoice {
    public String name;
    public List<Line> lines = new ArrayList<>();

    public Invoice() { }

    public Invoice(String name, Line... lines) {
        this.name = name;
        this.lines.addAll(List.of(lines));
    }
}
