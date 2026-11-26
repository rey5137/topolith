package io.github.rey5137.topolith.samples.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.ChargeResult;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.payment.api.PaymentDeclinedException;
import io.github.rey5137.topolith.samples.payment.api.PaymentNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

// snippet:start payment-controller-header
/** Serves the payment contract under the module's fixed {@code /payment} prefix. */
@RestController
public class PaymentController implements PaymentApi {
// snippet:end payment-controller-header
    private static final String TAG_SEPARATOR = "\n";

    private final JdbcTemplate jdbc;
    private final PaymentProperties properties;

    public PaymentController(JdbcTemplate jdbc, PaymentProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    // snippet:start payment-charge
    @Override
    public ChargeResult charge(ChargeRequest request) {
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentDeclinedException("amount must be positive");
        }
        if (amount.compareTo(properties.getMaxAmount()) > 0) {
            throw new PaymentDeclinedException("amount exceeds the configured maximum");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into payment_charge (id, reference, amount, status, tags) values (?, ?, ?, ?, ?)",
                id, request.reference(), amount, "CHARGED", String.join(TAG_SEPARATOR, request.tags()));
        return new ChargeResult(id, request.reference(), amount, "CHARGED", request.tags());
    }
    // snippet:end payment-charge

    private static List<String> splitTags(String stored) {
        return stored == null || stored.isEmpty() ? List.of() : List.of(stored.split(TAG_SEPARATOR));
    }

    @Override
    public ChargeResult find(String id) {
        List<ChargeResult> found = jdbc.query(
                "select id, reference, amount, status, tags from payment_charge where id = ?",
                (row, number) -> new ChargeResult(row.getString("id"), row.getString("reference"),
                        row.getBigDecimal("amount"), row.getString("status"), splitTags(row.getString("tags"))), id);
        if (found.isEmpty()) {
            throw new PaymentNotFoundException("payment '" + id + "' not found");
        }
        return found.get(0);
    }
}
