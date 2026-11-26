package io.github.rey5137.topolith.samples.user;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.github.rey5137.topolith.samples.payment.api.ChargeRequest;
import io.github.rey5137.topolith.samples.payment.api.ChargeResult;
import io.github.rey5137.topolith.samples.payment.api.PaymentApi;
import io.github.rey5137.topolith.samples.user.api.OrderView;
import io.github.rey5137.topolith.samples.user.api.PlaceOrderRequest;
import io.github.rey5137.topolith.samples.user.api.UserApi;
import io.github.rey5137.topolith.samples.user.api.UserOrderNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestController;

/** Serves the user contract under the module's fixed {@code /user} prefix and calls payment through its api. */
@RestController
public class UserController implements UserApi {
    private final JdbcTemplate jdbc;
    private final PaymentApi payments;

    public UserController(JdbcTemplate jdbc, PaymentApi payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @Override
    public OrderView placeOrder(PlaceOrderRequest request) {
        BigDecimal amount = request.amount();
        ChargeResult charge = payments.charge(new ChargeRequest(request.reference(), amount));
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into user_order (id, payment_id, reference, amount) values (?, ?, ?, ?)",
                id, charge.id(), request.reference(), amount);
        return new OrderView(id, charge.id(), request.reference(), amount);
    }

    @Override
    public OrderView findOrder(String id) {
        List<OrderView> found = jdbc.query(
                "select id, payment_id, reference, amount from user_order where id = ?",
                (row, number) -> new OrderView(row.getString("id"), row.getString("payment_id"),
                        row.getString("reference"), row.getBigDecimal("amount")), id);
        if (found.isEmpty()) {
            throw new UserOrderNotFoundException("order '" + id + "' not found");
        }
        return found.get(0);
    }
}
