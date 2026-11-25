package io.github.rey5137.topolith.processor.fixture.versioned;

import io.github.rey5137.topolith.processor.fixture.versioned.api.Greeter;

class GreeterImpl implements Greeter {
    @Override
    public String greet() {
        return "hello";
    }
}
