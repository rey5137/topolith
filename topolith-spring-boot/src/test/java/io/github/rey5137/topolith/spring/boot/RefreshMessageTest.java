package io.github.rey5137.topolith.spring.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RefreshMessageTest {
    @Test
    void ac_ext_24_a_failure_message_carries_no_url_userinfo() {
        assertEquals("'https://example.test:8443/x' is unreachable; also http://example.test/y",
                ExternalClientEndpoints.stripUserInfo(
                        "'https://bob:s3cret@example.test:8443/x' is unreachable; also http://tok@example.test/y"));
        assertEquals("failed http://host/x", ExternalClientEndpoints.stripUserInfo("failed http://admin:p@ss@host/x"));
        assertEquals("failed host:8080", ExternalClientEndpoints.stripUserInfo("failed admin:secret@host:8080"));
        assertEquals("http://host:8080/x mail me@host.test key: value",
                ExternalClientEndpoints.stripUserInfo("http://host:8080/x mail me@host.test key: value"));
        assertEquals("no url here", ExternalClientEndpoints.stripUserInfo("no url here"));
    }
}
