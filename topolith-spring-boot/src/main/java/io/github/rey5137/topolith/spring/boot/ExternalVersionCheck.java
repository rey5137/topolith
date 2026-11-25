package io.github.rey5137.topolith.spring.boot;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.service.registry.HttpServiceProxyRegistry;

/**
 * The readiness check of R-VAL-04 for external modules: reads {@code <base-url>/.topolith/api-version} through the
 * module's own client, so its timeouts and load balancing apply, and compares the major with the host's API
 * descriptor. A mismatch is TL0023, a failed read TL0015; both are warnings, never a failure (D-24, D-34).
 */
final class ExternalVersionCheck {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)(?:[.-]\\S*)?$");
    private static final int MAX_LENGTH = 64;

    private ExternalVersionCheck() {
    }

    /** One warning line per external module that needs one, in id order. */
    static void check(BeanFactory beanFactory, TopologyResolver.Resolution resolution, Consumer<String> warn) {
        check(beanFactory, resolution, resolution.topology().idsOf(Mode.EXTERNAL), warn);
    }

    /** The same for the given modules only, such as one whose client a refresh rebuilt. */
    static void check(BeanFactory beanFactory, TopologyResolver.Resolution resolution, Set<String> ids,
            Consumer<String> warn) {
        // under lazy initialization the groups are not configured until the registry exists
        beanFactory.getBeanProvider(HttpServiceProxyRegistry.class).getIfAvailable();
        ExternalClientBuilders clients = beanFactory.getBean(ExternalErrorsConfiguration.CLIENTS_BEAN,
                ExternalClientBuilders.class);
        for (String id : ids) {
            RestClient.Builder builder = clients.get(id);
            if (builder == null) {
                continue;
            }
            String path = "/" + id + ExposedApiVersionConfiguration.VERSION_PATH;
            String failure;
            String body = null;
            try {
                ResponseEntity<String> response = builder.build().get()
                        .uri(ExposedApiVersionConfiguration.VERSION_PATH).retrieve().toEntity(String.class);
                body = response.getBody();
                failure = response.getStatusCode().is2xxSuccessful() ? null
                        : "it answered HTTP " + response.getStatusCode().value();
            } catch (RestClientResponseException e) {
                failure = "it answered HTTP " + e.getStatusCode().value();
            } catch (RuntimeException e) {
                failure = "it could not be reached (" + e.getClass().getSimpleName() + ")";
            }
            Optional<BigInteger> remote = failure == null ? major(body) : Optional.empty();
            if (failure == null && remote.isEmpty()) {
                failure = "it answered without a readable version";
            }
            if (failure != null) {
                warn.accept(new Diagnostic(TopolithCode.TL0015, "the API version of external module '" + id
                        + "' was not read from " + path + ": " + failure + ".", "Expose the module with topolith"
                        + ".modules." + id + ".expose.enabled=true and let this host reach " + path
                        + ", or ignore this warning.").toString());
                continue;
            }
            String host = resolution.hostApiVersions().get(id);
            if (host != null && !remote.get().equals(TopologyResolver.major(host))) {
                warn.accept(new Diagnostic(TopolithCode.TL0023, "module '" + id + "' has API version " + host
                        + " and the remote provider reports " + body.trim() + ".",
                        "Align the host API JAR and the remote provider's major versions.").toString());
            }
        }
    }

    private static Optional<BigInteger> major(String body) {
        if (body == null || body.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher matcher = VERSION.matcher(body.trim());
        return matcher.matches() ? Optional.of(new BigInteger(matcher.group(1))) : Optional.empty();
    }
}
