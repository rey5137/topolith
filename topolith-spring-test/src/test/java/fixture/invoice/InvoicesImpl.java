package fixture.invoice;

import java.util.List;

import fixture.invoice.api.Invoice;
import fixture.invoice.api.InvoiceException;
import fixture.invoice.api.InvoiceCheckedException;
import fixture.invoice.api.Invoices;
import fixture.invoice.api.Line;
import fixture.invoice.api.Lossy;
import fixture.invoice.api.Shape;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class InvoicesImpl implements Invoices {
    /** Set by a test to change the argument inside {@link #total}. */
    public volatile boolean mutateArgument;
    public volatile Invoice kept;
    public final Invoice held = new Invoice("held", new Line("a", 1));
    public volatile Invoice lastArgument;
    public volatile Object thrown;

    @Override
    public int total(Invoice invoice) {
        lastArgument = invoice;
        int sum = invoice.lines.stream().mapToInt(line -> line.qty).sum();
        if (mutateArgument) { invoice.lines.get(0).qty = 99; }
        return sum;
    }

    @Override
    public void keep(Invoice invoice) { kept = invoice; }

    @Override
    public void touchKept() { kept.name = "changed"; }

    @Override
    public Invoice held() { return held; }

    @Override
    public void touchHeld() { held.lines.get(0).qty = 42; }

    @Override
    public List<Line> echo(List<Line> lines) { return lines; }

    @Override
    public Page<Line> page() { return new Page<>() { }; }

    @Override
    public Shape shape() { return () -> "circle"; }

    @Override
    public Lossy lossy(Lossy value) { return value; }

    public Lossy lossyResultValue;

    @Override
    public Lossy lossyResult() { return lossyResultValue = new Lossy("secret-value"); }

    @Override
    public void failLossy(Lossy value) {
        var failure = new InvoiceException("failure");
        thrown = failure;
        throw failure;
    }

    @Override
    public void fail(String kind) {
        RuntimeException failure = switch (kind) {
            case "declared" -> new InvoiceException("declared failure");
            case "unavailable" -> new ModuleUnavailableException("ledger", "down");
            case "jdk" -> new IllegalStateException("jdk failure", new RuntimeException("cause"));
            case "nfe" -> new NumberFormatException("nfe failure");
            default -> new ArithmeticException("undeclared failure");
        };
        thrown = failure;
        throw failure;
    }

    public InvoiceCheckedException checkedFailure;
    /** Set by a test to throw a subclass outside the api package from {@link #checked}. */
    public volatile boolean checkedSub;

    @Override
    public void checked() throws InvoiceCheckedException {
        checkedFailure = checkedSub ? new InvoiceCheckedSub("checked sub failure")
                : new InvoiceCheckedException("checked failure");
        throw checkedFailure;
    }

    private volatile Invoice stored;

    @Override
    public void put(Invoice value) { stored = value; }

    @Override
    public Invoice last() { return stored; }

    @Override
    public void add(java.util.concurrent.atomic.AtomicInteger counter) { counter.incrementAndGet(); }

    @Override
    public void nothing() { }
}
