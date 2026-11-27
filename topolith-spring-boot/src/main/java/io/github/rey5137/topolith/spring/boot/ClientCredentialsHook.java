package io.github.rey5137.topolith.spring.boot;

import java.util.List;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * The {@code client-credentials} hook (D-94); loaded only when Spring Security's OAuth2 client classes are present
 * (INV-7). Topolith builds its own manager from the single repository and service, never a host manager bean, which
 * may need the current servlet request a contract call does not have.
 */
final class ClientCredentialsHook {
    private ClientCredentialsHook() {
    }

    static Diagnostic problem(String id, String reason, String hint) {
        return new Diagnostic(TopolithCode.TL0037, "module '" + id + "' has auth.type=client-credentials, but "
                + reason + ".", hint);
    }

    /**
     * Spring Security's interceptor authorizing {@code registrationId} for the fixed principal
     * {@code topolith:<registration id>}, so one token per registration is obtained and cached whoever calls; null,
     * with a TL0037 problem, when it cannot be built.
     */
    static ClientHttpRequestInterceptor create(String id, String registrationId, ListableBeanFactory beans,
            ClientHttpRequestFactory tokenRequests, List<Diagnostic> problems) {
        ClientRegistrationRepository registrations = single(id, ClientRegistrationRepository.class, beans, problems);
        OAuth2AuthorizedClientService clients = single(id, OAuth2AuthorizedClientService.class, beans, problems);
        if (registrations == null || clients == null) {
            return null;
        }
        if (registrations.findByRegistrationId(registrationId) == null) {
            problems.add(problem(id, "the ClientRegistrationRepository holds no registration '" + registrationId
                    + "'", "Register it under spring.security.oauth2.client.registration, or fix "
                    + TopologyResolver.MODES + "." + id + ".auth." + OutgoingAuth.REGISTRATION + "."));
            return null;
        }
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        ClientCredentialsOAuth2AuthorizedClientProvider provider =
                new ClientCredentialsOAuth2AuthorizedClientProvider();
        ResolvableType tokenClientType = ResolvableType.forClassWithGenerics(OAuth2AccessTokenResponseClient.class,
                OAuth2ClientCredentialsGrantRequest.class);
        String[] hostTokenClients = beans.getBeanNamesForType(tokenClientType);
        if (hostTokenClients.length > 1) {
            problems.add(problem(id, "there are " + hostTokenClients.length + " client-credentials "
                    + "OAuth2AccessTokenResponseClient beans", "Declare at most one, as Spring Security requires."));
            return null;
        }
        if (hostTokenClients.length == 1) {
            // the host's token client, as Spring Security's own manager would use it (D-96)
            @SuppressWarnings("unchecked")
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> host =
                    (OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>) beans.getBean(
                            hostTokenClients[0]);
            provider.setAccessTokenResponseClient(host);
        } else if (tokenRequests != null) {
            // Spring Security's own token client and converters, with the module's timeouts: its default has none
            RestClientClientCredentialsTokenResponseClient tokens =
                    new RestClientClientCredentialsTokenResponseClient();
            tokens.setRestClient(RestClient.builder().requestFactory(tokenRequests)
                    .configureMessageConverters(converters -> converters
                            .addCustomConverter(new FormHttpMessageConverter())
                            .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                    .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
            provider.setAccessTokenResponseClient(tokens);
        }
        manager.setAuthorizedClientProvider(provider);
        Authentication principal = UsernamePasswordAuthenticationToken.unauthenticated("topolith:" + registrationId,
                null);
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> registrationId);
        interceptor.setPrincipalResolver(request -> principal);
        // a token the provider rejects as invalid_token is removed, so the next call obtains a new one (D-96)
        interceptor.setAuthorizationFailureHandler(OAuth2ClientHttpRequestInterceptor
                .authorizationFailureHandler(clients));
        return interceptor;
    }

    private static <T> T single(String id, Class<T> type, ListableBeanFactory beans, List<Diagnostic> problems) {
        String[] names = beans.getBeanNamesForType(type);
        if (names.length != 1) {
            problems.add(problem(id, "there " + (names.length == 0 ? "is no " : "are " + names.length + " ")
                    + type.getSimpleName() + " bean" + (names.length == 0 ? "" : "s"),
                    "Declare exactly one " + type.getSimpleName() + " bean to build the hook's manager from."));
            return null;
        }
        return beans.getBean(names[0], type);
    }
}
