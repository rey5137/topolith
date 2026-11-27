package fixture.invoice.api;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.domain.Page;

public interface Invoices extends Store<Invoice> {
    /** Returns the sum of the quantities; changes the argument when {@code change} is set on the provider. */
    int total(Invoice invoice);

    /** Keeps the argument. */
    void keep(Invoice invoice);

    /** Changes what {@link #keep} kept. */
    void touchKept();

    /** Returns an instance the provider also holds. */
    Invoice held();

    /** Changes what {@link #held} returned. */
    void touchHeld();

    List<Line> echo(List<Line> lines);

    Page<Line> page();

    Shape shape();

    Lossy lossy(Lossy value);

    Lossy lossyResult();

    void failLossy(Lossy value);

    void fail(String kind);

    void checked() throws InvoiceCheckedException;

    /** The provider increments the counter it receives. */
    void add(AtomicInteger counter);

    void nothing();
}
