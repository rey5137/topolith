package fixture.charge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fixture.charge.api.ChargeOrder;
import fixture.charge.api.Charges;
import fixture.charge.api.Receipt;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class ChargesImpl implements Charges {
    private final Map<String, Receipt> authorized = new ConcurrentHashMap<>();

    @Override
    public Receipt charge(ChargeOrder order) {
        order.status = "CHARGED";
        return new Receipt(UUID.randomUUID().toString(), order.reference, order.status);
    }

    @Override
    public Receipt authorize(ChargeOrder order) {
        Receipt receipt = new Receipt(UUID.randomUUID().toString(), order.reference, "AUTHORIZED");
        authorized.put(receipt.id, receipt);
        return receipt;
    }

    @Override
    public void capture(String id) { authorized.get(id).status = "CAPTURED"; }

    @Override
    public Page<Receipt> history() { return new Page<>() { }; }
}
