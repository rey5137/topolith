package io.github.rey5137.topolith.spring.boot;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The caller and provider halves of the context a loopback or external call carries: the listed MDC keys as
 * {@code Topolith-Mdc-<key>} headers and the tenant as {@code Topolith-Tenant} (R-EXT-12, D-90, D-91). The caller half
 * is {@link #headers()}, called by every external and loopback group's proxy; the provider half is
 * {@link ContextRoute}. This class links no servlet or web type, so a host without them can still start.
 */
final class ContextPropagation {
    private static final Logger LOG = LoggerFactory.getLogger(ContextPropagation.class);
    static final String KEYS_PROPERTY = "topolith.context.mdc-keys";
    static final String MDC_PREFIX = "Topolith-Mdc-";
    static final String CARRIER_PREFIX = "Topolith-Context-";
    static final String TENANT_HEADER = "Topolith-Tenant";
    static final int MAX_KEYS = 16;
    static final int MAX_ENCODED = 1024;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final List<String> keys;
    private final Map<String, String> byLowerCase = new LinkedHashMap<>();
    private final ObjectProvider<TenantContext> tenants;
    private final Map<String, ContextCarrier> carriers = new LinkedHashMap<>();
    private final boolean active;

    /** TL0034 for an invalid key list or for {@code tenantBeans} over one. */
    ContextPropagation(List<String> listed, int tenantBeans, boolean slf4j, ObjectProvider<TenantContext> tenants,
            List<ContextCarrier> carrierBeans) {
        List<Diagnostic> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String key : listed) {
            if (!KEY.matcher(key).matches()) {
                problems.add(problem("MDC key '" + key + "' in " + KEYS_PROPERTY + " is not letters, digits, '.', '_' "
                        + "and '-' starting with a letter or digit.", "Rename key '" + key + "'."));
            } else if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                problems.add(problem("MDC key '" + key + "' in " + KEYS_PROPERTY + " equals another key ignoring case.",
                        "List key '" + key + "' once."));
            }
        }
        for (ContextCarrier carrier : carrierBeans) {
            String name = carrier.name();
            if (name == null || !KEY.matcher(name).matches()) {
                problems.add(problem("carrier name '" + name + "' is invalid.", "Rename the carrier."));
            } else if (carriers.putIfAbsent(name.toLowerCase(Locale.ROOT), carrier) != null) {
                problems.add(problem("carrier name '" + name + "' equals another ignoring case.",
                        "Give each carrier a distinct name."));
            }
        }
        if (listed.size() + carrierBeans.size() > MAX_KEYS) {
            problems.add(problem(listed.size() + " MDC keys are listed in " + KEYS_PROPERTY + ", more than "
                    + MAX_KEYS + " together with " + carrierBeans.size() + " carriers.",
                    "List at most " + MAX_KEYS + " keys and carriers together."));
        }
        if (!listed.isEmpty() && !slf4j) {
            problems.add(problem("MDC key '" + listed.get(0) + "' is listed in " + KEYS_PROPERTY
                    + " but SLF4J is not on the classpath.", "Add SLF4J, or list no key."));
        }
        if (tenantBeans > 1) {
            problems.add(problem("more than one TenantContext bean exists, so the tenant's holder is ambiguous.",
                    "Keep one TenantContext bean."));
        }
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        this.keys = List.copyOf(listed);
        keys.forEach(key -> byLowerCase.put(key.toLowerCase(Locale.ROOT), key));
        this.tenants = tenants;
        this.active = !keys.isEmpty() || tenantBeans > 0 || !carriers.isEmpty();
    }

    private static Diagnostic problem(String message, String hint) {
        return new Diagnostic(TopolithCode.TL0034, "Context propagation is misconfigured: " + message, hint);
    }

    /** Whether a key is listed or a {@code TenantContext} bean exists; decided here, once every bean is defined. */
    boolean active() {
        return active;
    }

    List<String> keys() {
        return keys;
    }

    Map<String, ContextCarrier> carriers() {
        return carriers;
    }

    /** Listed key by its lower-case spelling, for the provider's case-insensitive header match. */
    Map<String, String> keysByLowerCase() {
        return byLowerCase;
    }

    /** The host's tenant context, or null. */
    TenantContext tenantContext() {
        return tenants.getIfAvailable();
    }

    /**
     * The caller half: the headers to send, read on the calling thread, where the host's MDC and tenant are (a
     * circuit breaker may run the exchange itself on another one). TL0035 for an MDC value over the limit or with a
     * control character; TL0038 for a tenant over it or with a control character, so no call goes out without its
     * tenant.
     */
    Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (ContextCarrier carrier : carriers.values()) {
            String value = carrier.current();
            if (value != null) {
                String encoded = encode(value);
                if (value.chars().anyMatch(Character::isISOControl) || encoded.length() > MAX_ENCODED) {
                    LOG.warn(new Diagnostic(TopolithCode.TL0035, "carrier '" + carrier.name()
                            + "' has an invalid or oversize value, so it is not sent.",
                            "Use a shorter value without control characters.").toString());
                } else {
                    headers.put(CARRIER_PREFIX + carrier.name(), encoded);
                }
            }
        }
        for (String key : keys) {
            String value = MDC.get(key);
            if (value == null) {
                continue;
            }
            if (value.chars().anyMatch(Character::isISOControl)) {
                LOG.warn(new Diagnostic(TopolithCode.TL0035, "MDC key '" + key + "' has a value with a control "
                        + "character, so it is not sent.", "Remove the control characters from the value of '" + key
                        + "', or stop listing the key.").toString());
            } else {
                String encoded = encode(value);
                if (encoded.length() > MAX_ENCODED) {
                    LOG.warn(new Diagnostic(TopolithCode.TL0035, "MDC key '" + key + "' has a value over "
                            + MAX_ENCODED + " characters once percent-encoded, so it is not sent.",
                            "Shorten the value of '" + key + "', or stop listing the key.").toString());
                } else {
                    headers.put(MDC_PREFIX + key, encoded);
                }
            }
        }
        TenantContext context = tenants.getIfAvailable();
        String tenant = context == null ? null : context.current();
        if (tenant != null) {
            if (tenant.chars().anyMatch(Character::isISOControl)) {
                throw new TopolithException(new Diagnostic(TopolithCode.TL0038, "the tenant has a control "
                        + "character, so the call is not sent.", "Use a tenant without control characters."));
            }
            String encoded = encode(tenant);
            if (encoded.length() > MAX_ENCODED) {
                throw new TopolithException(new Diagnostic(TopolithCode.TL0038, "the tenant is over "
                        + MAX_ENCODED + " characters once percent-encoded, so the call is not sent.",
                        "Use a shorter tenant."));
            }
            headers.put(TENANT_HEADER, encoded);
        }
        return headers;
    }

    private static boolean unreserved(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.'
                || c == '_' || c == '~';
    }

    /** UTF-8, percent-encoded except for RFC 3986's unreserved characters. */
    static String encode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (unreserved(c)) {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** The inverse of {@link #encode}; IllegalArgumentException for an over-long, mis-encoded, non-UTF-8 or
     * control-character value (D-99). */
    static String decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED) {
            throw new IllegalArgumentException("absent or over-long value");
        }
        byte[] bytes = new byte[encoded.length()];
        int length = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%') {
                int high = i + 2 < encoded.length() ? Character.digit(encoded.charAt(i + 1), 16) : -1;
                int low = i + 2 < encoded.length() ? Character.digit(encoded.charAt(i + 2), 16) : -1;
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("bad percent escape");
                }
                bytes[length++] = (byte) (high << 4 | low);
                i += 2;
            } else if (unreserved(c)) {
                bytes[length++] = (byte) c;
            } else {
                throw new IllegalArgumentException("unencoded character");
            }
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not UTF-8", e);
        }
        if (decoded.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("control character");
        }
        return decoded;
    }
}
