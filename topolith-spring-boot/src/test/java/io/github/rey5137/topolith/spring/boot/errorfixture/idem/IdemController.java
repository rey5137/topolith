package io.github.rey5137.topolith.spring.boot.errorfixture.idem;

import io.github.rey5137.topolith.spring.boot.errorfixture.idem.api.ReadContract;
import org.springframework.web.bind.annotation.RestController;

/** The idem module's provider: a controller, so a loopback idem module has a route for each method (D-126). */
@RestController
public class IdemController implements ReadContract {
    @Override
    public String get(String id) {
        return id;
    }

    @Override
    public String save(String value) {
        return value;
    }

    @Override
    public int count() {
        return 1;
    }
}
