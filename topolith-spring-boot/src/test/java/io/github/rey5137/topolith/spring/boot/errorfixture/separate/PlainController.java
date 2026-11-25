package io.github.rey5137.topolith.spring.boot.errorfixture.separate;

import io.github.rey5137.topolith.spring.boot.errorfixture.separate.api.PlainException;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlainController implements PlainHttp {
    @Override
    public String find(String value) {
        if (value.equals("fail")) {
            throw new PlainException("failed");
        }
        return "found " + value;
    }
}
