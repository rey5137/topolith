package fixture.architecture.shop;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;

public class ShopHandlesOutage {
    public boolean outage(RuntimeException failure) {
        return failure instanceof ModuleUnavailableException;
    }
}
