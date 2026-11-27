package io.github.rey5137.topolith.samples.paymenttests;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Bearer tokens signed with a key generated for this test run, which the tests hand to the sample hosts as
 * {@code sample.jwt.secret}, so no key is kept in a tracked file.
 */
public final class SampleTokens {
    private static final String SECRET = secret(new SecureRandom());
    private static final SecretKeySpec KEY = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),
            "HmacSHA256");

    private SampleTokens() {
    }

    /** This run's key, for a host's {@code sample.jwt.secret}. */
    public static String secret() {
        return SECRET;
    }

    private static String secret(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The {@code Authentication} a resource server builds from a fresh token for {@code subject}. */
    static JwtAuthenticationToken authentication(String subject, String scope) {
        Instant now = Instant.now();
        String token = new NimbusJwtEncoder(new ImmutableSecret<>(KEY)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder().subject(subject)
                        .claim("scope", scope).issuedAt(now).expiresAt(now.plusSeconds(600)).build()))
                .getTokenValue();
        return (JwtAuthenticationToken) new JwtAuthenticationConverter()
                .convert(NimbusJwtDecoder.withSecretKey(KEY).build().decode(token));
    }
}
