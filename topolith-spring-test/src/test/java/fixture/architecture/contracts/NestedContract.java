package fixture.architecture.contracts;

import org.springframework.data.domain.Page;

public interface NestedContract {
    interface OrderApi {
        Page<String> list();
    }
}
