package fixture.ledger.api;

import java.util.List;

public interface Rates {
    /** The provider changes the list it receives. */
    int total(List<Integer> values);
}
