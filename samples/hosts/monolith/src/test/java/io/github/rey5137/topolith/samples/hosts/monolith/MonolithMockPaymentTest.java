package io.github.rey5137.topolith.samples.hosts.monolith;

import java.math.BigDecimal;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.user.api.OrderView;
import io.github.rey5137.topolith.samples.user.api.PlaceOrderRequest;
import io.github.rey5137.topolith.samples.user.api.UserApi;
import io.github.rey5137.topolith.spring.test.TopolithMock;
import io.github.rey5137.topolith.spring.test.TopolithMockServer;
import io.github.rey5137.topolith.spring.test.TopolithModuleMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

// snippet:start test-helpers-mock
@SpringBootTest(classes = MonolithApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:monolith-mock;DB_CLOSE_DELAY=-1")
@TopolithModuleMode(id = "user", mode = Mode.INTERNAL)
@TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
class MonolithMockPaymentTest {
    @TopolithMock(id = "payment")
    TopolithMockServer paymentMock;

    @Autowired
    UserApi userApi;

    @Test
    void user_places_order_against_mocked_external_payment() {
        paymentMock.stub("POST", "/charges", 200,
                "{\"id\":\"chg-1\",\"reference\":\"ord-1\",\"amount\":10.00,\"status\":\"CHARGED\",\"tags\":[]}");

        OrderView order = userApi.placeOrder(new PlaceOrderRequest("ord-1", new BigDecimal("10.00")));

        assertEquals("chg-1", order.paymentId());
        assertEquals(1, paymentMock.requests().size());
        TopolithMockServer.Request recorded = paymentMock.requests().get(0);
        assertEquals("POST", recorded.method());
        assertEquals("/payment/charges", recorded.path());
        assertEquals("{\"reference\":\"ord-1\",\"amount\":10.00,\"tags\":[]}", recorded.body());
    }
}
// snippet:end test-helpers-mock
