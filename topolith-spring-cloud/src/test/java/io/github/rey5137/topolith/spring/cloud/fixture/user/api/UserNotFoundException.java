package io.github.rey5137.topolith.spring.cloud.fixture.user.api;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String message) {
        super(message);
    }
}
