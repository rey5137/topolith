package io.github.rey5137.topolith.spring.boot;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class UnauthenticatedRelayTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ac_ext_76_warns_once_per_module_without_exposing_token(CapturedOutput output) {
        var check = new UnauthenticatedRelay();
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer secret-token");
        check.check(request, "account");
        check.check(request, "account");
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        check.check(request, "payment");
        assertEquals(2, output.getOut().split("TL0046", -1).length - 1);
        assertTrue(output.getOut().contains("account"));
        assertTrue(output.getOut().contains("payment"));
        assertFalse(output.getOut().contains("secret-token"));
    }

    @Test
    void ac_ext_76_authenticated_or_no_bearer_does_not_warn(CapturedOutput output) {
        var check = new UnauthenticatedRelay();
        var request = new MockHttpServletRequest();
        check.check(request, "account");
        request.addHeader("Authorization", "Bearer secret-token");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
        check.check(request, "account");
        assertFalse(output.getOut().contains("TL0046"));
    }
}
