package io.github.rey5137.topolith.spring.boot;

import java.util.List;
import java.util.function.Supplier;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Optional OAuth client integration, loaded only when the OAuth client and servlet APIs exist. */
final class SessionRelay {
    private SessionRelay() { }

    static Supplier<String> create(ListableBeanFactory beans, List<Diagnostic> problems,
            Supplier<ClientHttpRequestFactory> tokenRequests) {
        String[] registrations = beans.getBeanNamesForType(ClientRegistrationRepository.class);
        String[] repositories = beans.getBeanNamesForType(OAuth2AuthorizedClientRepository.class);
        if (registrations.length > 1 || repositories.length > 1) {
            problems.add(new Diagnostic(TopolithCode.TL0047, "session relay has ambiguous repositories: "
                    + String.join(", ", registrations) + "; " + String.join(", ", repositories) + ".",
                    "Keep at most one repository of each type."));
            return () -> null;
        }
        if (registrations.length == 0 || repositories.length == 0) {
            return () -> null;
        }
        var manager = new DefaultOAuth2AuthorizedClientManager(
                beans.getBean(registrations[0], ClientRegistrationRepository.class),
                beans.getBean(repositories[0], OAuth2AuthorizedClientRepository.class));
        var provider = OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode();
        if (tokenRequests == null) {
            provider.refreshToken();
        } else {
            var tokens = new RestClientRefreshTokenTokenResponseClient();
            tokens.setRestClient(RestClient.builder().requestFactory(tokenRequests.get())
                    .configureMessageConverters(converters -> converters
                            .addCustomConverter(new FormHttpMessageConverter())
                            .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                    .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
            provider.refreshToken(refresh -> refresh.accessTokenResponseClient(tokens));
        }
        manager.setAuthorizedClientProvider(provider.build());
        return () -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof OAuth2AuthenticationToken login)
                    || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return null;
            }
            OAuth2AuthorizeRequest.Builder builder = OAuth2AuthorizeRequest.withClientRegistrationId(
                    login.getAuthorizedClientRegistrationId()).principal(login)
                    .attribute(jakarta.servlet.http.HttpServletRequest.class.getName(), attributes.getRequest());
            if (attributes.getResponse() != null) {
                builder.attribute(jakarta.servlet.http.HttpServletResponse.class.getName(), attributes.getResponse());
            }
            try {
                OAuth2AuthorizedClient client = manager.authorize(builder.build());
                return client == null || client.getAccessToken() == null
                        ? null : client.getAccessToken().getTokenValue();
            } catch (OAuth2AuthorizationException failure) {
                return null;
            }
        };
    }
}
