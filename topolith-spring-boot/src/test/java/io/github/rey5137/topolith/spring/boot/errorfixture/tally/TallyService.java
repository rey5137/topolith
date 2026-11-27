package io.github.rey5137.topolith.spring.boot.errorfixture.tally;

import io.github.rey5137.topolith.spring.boot.errorfixture.tally.api.TallyContract;
import org.springframework.stereotype.Service;

/** The tally module's only provider: a {@code @Service}, so no module route serves the contract (D-126). */
@Service
public class TallyService implements TallyContract {
    @Override
    public String count() {
        return "1";
    }

    @Override
    public String reset() {
        return "0";
    }
}
