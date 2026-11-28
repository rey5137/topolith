package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.HttpServer;
import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.boundary.api.BoundaryContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ErrorHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.SeenAuth;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ThreadTenant;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.Map;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** M6.4: the outgoing-auth hook per module, token relay by default, client credentials (R-EXT-13, D-92..D-95). */
class OutgoingAuthTest {
    private static final SecretKeySpec KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private static final JwtDecoder DECODER = NimbusJwtDecoder.withSecretKey(KEY).build();
    // Spring Security 7 adds the bearer factor authority to every bearer-token Authentication
    private static final String ALICE = "alice [FACTOR_BEARER, SCOPE_read, SCOPE_write]";
    private static final String ANONYMOUS = "anonymousUser [ROLE_ANONYMOUS]";
    private static final String[] NONE = {"spring.liquibase.enabled=false", "topolith.modules.account.mode=none",
            "topolith.modules.payment.mode=none", "topolith.modules.boundary.mode=none"};
    private static final String AUTH = "topolith.modules.account.auth.";

    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment", PaymentContract.class);
        ContractExceptionsTest.contracts(root, "boundary", BoundaryContract.class);
        SeenAuth.clear();
        Registrations.MANAGER_CALLS.set(0);
        Hooks.SAW.set(null);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        ThreadTenant.lastPrincipal = null;
    }

    /** A bearer-token {@code Authentication} built as a resource server builds it from the token. */
    static JwtAuthenticationToken jwt(String subject) {
        Instant now = Instant.now();
        String token = new NimbusJwtEncoder(new ImmutableSecret<>(KEY)).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder().subject(subject)
                        .claim("scope", "read write").issuedAt(now).expiresAt(now.plusSeconds(600)).build()))
                .getTokenValue();
        return (JwtAuthenticationToken) new JwtAuthenticationConverter().convert(DECODER.decode(token));
    }

    private static void as(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** The provider host's resource-server configuration: it authenticates a relayed token, and permits all. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class ResourceServer {
        @Bean
        SecurityFilterChain resourceServer(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(DECODER))).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class PermitAll {
        @Bean
        SecurityFilterChain permitAll(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable()).build();
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void ac_ext_76_loopback_warns_once_when_bearer_is_not_authenticated(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        loopback(account -> {
            as(jwt("alice"));
            assertEquals(ANONYMOUS, account.find("auth"));
            assertEquals(ANONYMOUS, account.find("auth"));
            assertEquals(1, output.getOut().split("TL0046", -1).length - 1);
            assertTrue(output.getOut().contains("account"));
            assertFalse(output.getOut().contains(SeenAuth.AUTHORIZATION.get().substring("Bearer ".length())));
        }, Fixtures.class.getClassLoader(), new Class<?>[] {PermitAll.class}, AUTH + "type=relay");
    }

    private WebApplicationContextRunner server(Fixtures.RecordingLoader loader, String... properties) {
        return new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                .withClassLoader(loader).withUserConfiguration(ErrorHost.class, ContextPropagationTest.Holders.class)
                .withPropertyValues("server.port=0").withPropertyValues(NONE).withPropertyValues(properties);
    }

    private static ApplicationContextRunner client(ClassLoader loader, String... properties) {
        return new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                .withPropertyValues(NONE).withPropertyValues(properties);
    }

    /** A loopback account module's client on a host with {@code extra} configuration. */
    private void loopback(Consumer<AccountContract> check, ClassLoader parent, Class<?>[] extra,
            String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
            server(loader, "topolith.modules.account.mode=loopback").withUserConfiguration(extra)
                    .withPropertyValues(properties).run(context -> {
                        assertNull(context.getStartupFailure());
                        check.accept(context.getBean(ContextPropagationTest.Holder.class).account());
                    });
        }
    }

    private void loopback(Consumer<AccountContract> check, Class<?>... extra) throws Exception {
        loopback(check, Fixtures.class.getClassLoader(), extra);
    }

    /** An external account module's client, on a host loaded from {@code parent}, against a resource server. */
    private void external(Consumer<AccountContract> check, ClassLoader parent) throws Exception {
        try (Fixtures.RecordingLoader served = new Fixtures.RecordingLoader(root)) {
            server(served, "topolith.modules.account.mode=internal", "topolith.modules.account.expose.enabled=true")
                    .withUserConfiguration(ResourceServer.class).run(provider -> {
                        assertNull(provider.getStartupFailure());
                        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
                            client(loader, "topolith.modules.account.mode=external",
                                    "topolith.modules.account.remote.url=http://127.0.0.1:"
                                            + ProviderErrorsTest.port(provider),
                                    "topolith.modules.account.remote.read-timeout=5s").run(context -> {
                                        assertNull(context.getStartupFailure());
                                        check.accept(context.getBean(AccountContract.class));
                                    });
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
    }

    /** With no {@code Authentication}, or one without a token, nothing is sent and the call succeeds. */
    private static void assertNothingSent(AccountContract account, String seen) {
        SecurityContextHolder.clearContext();
        assertEquals(seen, account.find("auth"));
        assertNull(SeenAuth.AUTHORIZATION.get());
        as(UsernamePasswordAuthenticationToken.authenticated("bob", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertEquals(seen, account.find("auth"));
        assertNull(SeenAuth.AUTHORIZATION.get());
    }

    private static void assertRelayed(AccountContract account) {
        JwtAuthenticationToken alice = jwt("alice");
        as(alice);
        assertEquals(ALICE, account.find("auth"));
        assertEquals("Bearer " + alice.getToken().getTokenValue(), SeenAuth.AUTHORIZATION.get());
    }

    @Test
    void ac_ext_53_internal_and_loopback_providers_see_the_callers_name_and_authorities() throws Exception {
        AtomicReference<String> internal = new AtomicReference<>();
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            client(loader, "topolith.modules.account.mode=internal").run(context -> {
                assertNull(context.getStartupFailure());
                as(jwt("alice"));
                internal.set(context.getBean(AccountContract.class).find("auth"));
            });
        }
        assertEquals(ALICE, internal.get());
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback").withUserConfiguration(ResourceServer.class)
                    .withBean(ThreadTenant.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(ContextPropagationTest.Holder.class).account();
                        assertRelayed(account);
                        // the tenant SPI receives the Authentication the resource server built (D-91)
                        assertEquals("alice", ThreadTenant.lastPrincipal);
                        assertNothingSent(account, ANONYMOUS);
                    });
        }
    }

    @Test
    void ac_ext_53_an_external_call_relays_the_token_to_the_providers_resource_server() throws Exception {
        external(account -> {
            assertRelayed(account);
            assertNothingSent(account, ANONYMOUS);
        }, Fixtures.class.getClassLoader());
    }

    @Test
    void ac_ext_53_an_exchange_on_another_thread_relays_the_calling_threads_token() throws Exception {
        loopback(OutgoingAuthTest::assertRelayed, Fixtures.class.getClassLoader(), new Class<?>[] {
                ResourceServer.class, ContextPropagationTest.Hop.class}, AUTH + "type=relay");
    }

    @Test
    void ac_ext_54_none_sends_no_authorization() throws Exception {
        loopback(account -> {
            as(jwt("alice"));
            assertEquals(ANONYMOUS, account.find("auth"));
            assertNull(SeenAuth.AUTHORIZATION.get());
        }, Fixtures.class.getClassLoader(), new Class<?>[] {ResourceServer.class}, AUTH + "type=none");
    }

    /** A client registration whose token endpoint is {@link #tokenUri}, the service, and a host manager bean. */
    @Configuration(proxyBeanMethods = false)
    static class Registrations {
        static volatile String tokenUri = "http://127.0.0.1:1/token";
        static final AtomicInteger MANAGER_CALLS = new AtomicInteger();

        static ClientRegistrationRepository repository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("svc")
                    .clientId("client").clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).tokenUri(tokenUri).build());
        }

        @Bean
        ClientRegistrationRepository registrations() {
            return repository();
        }

        @Bean
        OAuth2AuthorizedClientService authorizedClients() {
            return new InMemoryOAuth2AuthorizedClientService(repository());
        }

        /** Needs the current servlet request, as Spring Security's default manager does; Topolith never uses it. */
        @Bean
        OAuth2AuthorizedClientManager hostManager() {
            return request -> {
                MANAGER_CALLS.incrementAndGet();
                throw new AssertionError("host manager must not be used");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SessionRepository {
        @Bean
        OAuth2AuthorizedClientRepository sessionRepository(OAuth2AuthorizedClientService service) {
            return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(service);
        }
    }

    @Test
    void ac_ext_75_session_login_relays_its_authorized_client_and_requires_a_request() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback")
                    .withUserConfiguration(ResourceServer.class, Registrations.class, SessionRepository.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                                Map.of("name", "alice"), "name");
                        var login = new OAuth2AuthenticationToken(user, user.getAuthorities(), "svc");
                        as(login);
                        String token = jwt("alice").getToken().getTokenValue();
                        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                                Instant.now(), Instant.now().plusSeconds(600));
                        var registration = context.getBean(ClientRegistrationRepository.class)
                                .findByRegistrationId("svc");
                        context.getBean(OAuth2AuthorizedClientService.class).saveAuthorizedClient(
                                new OAuth2AuthorizedClient(registration, "alice", access), login);
                        AccountContract account = context.getBean(ContextPropagationTest.Holder.class).account();
                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                                new MockHttpServletRequest(), new MockHttpServletResponse()));
                        assertEquals(ALICE, account.find("auth"));
                        assertEquals("Bearer " + token, SeenAuth.AUTHORIZATION.get());
                        assertEquals(0, Registrations.MANAGER_CALLS.get());
                        CompletableFuture.runAsync(new DelegatingSecurityContextRunnable(
                                () -> {
                                    assertNull(RequestContextHolder.getRequestAttributes());
                                    assertInstanceOf(OAuth2AuthenticationToken.class,
                                            SecurityContextHolder.getContext().getAuthentication());
                                    assertEquals(ANONYMOUS, account.find("auth"));
                                    assertNull(SeenAuth.AUTHORIZATION.get());
                                })).join();
                        context.getBean(OAuth2AuthorizedClientService.class).removeAuthorizedClient("svc", "alice");
                        assertEquals(ANONYMOUS, account.find("auth"));
                        assertNull(SeenAuth.AUTHORIZATION.get());
                        RequestContextHolder.resetRequestAttributes();
                        assertEquals(ANONYMOUS, account.find("auth"));
                        assertNull(SeenAuth.AUTHORIZATION.get());
                    });
        }
    }

    @Test
    void ac_ext_75_76_77_none_modules_install_no_relay_or_carrier_hooks() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new WebApplicationContextRunner(AnnotationConfigServletWebServerApplicationContext::new)
                    .withClassLoader(loader).withUserConfiguration(ErrorHost.class, Registrations.class,
                            SecondRepository.class)
                    .withBean(ContextCarrier.class, () -> new ContextCarrierTest.Carrier("!"))
                    .withPropertyValues("server.port=0").withPropertyValues(NONE).run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.getBeansOfType(OutgoingAuth.class).isEmpty());
                        assertTrue(context.getBeansOfType(ContextPropagation.class).isEmpty());
                        assertTrue(context.getBeansOfType(ExposureConfiguration.class).isEmpty());
                    });
        }
    }

    @Test
    void ac_ext_76_external_only_installs_no_warning_hook() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=external",
                    "topolith.modules.account.remote.url=http://127.0.0.1:1").run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(context.getBeansOfType(ExposureConfiguration.class).isEmpty());
                        assertTrue(context.getBeansOfType(UnauthenticatedRelay.class).isEmpty());
                    });
        }
    }

    @Test
    void ac_ext_75_failed_refresh_sends_no_authorization_and_call_succeeds() throws Exception {
        HttpServer tokens = failingTokens(400, "{\"error\":\"invalid_grant\"}");
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback")
                    .withUserConfiguration(ResourceServer.class, Registrations.class, SessionRepository.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                                Map.of("name", "alice"), "name");
                        var login = new OAuth2AuthenticationToken(user, user.getAuthorities(), "svc");
                        as(login);
                        var registration = context.getBean(ClientRegistrationRepository.class)
                                .findByRegistrationId("svc");
                        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired",
                                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
                        var refresh = new OAuth2RefreshToken("refresh", Instant.now().minusSeconds(120));
                        context.getBean(OAuth2AuthorizedClientService.class).saveAuthorizedClient(
                                new OAuth2AuthorizedClient(registration, "alice", access, refresh), login);
                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                                new MockHttpServletRequest(), new MockHttpServletResponse()));
                        assertEquals(ANONYMOUS, context.getBean(ContextPropagationTest.Holder.class)
                                .account().find("auth"));
                        assertNull(SeenAuth.AUTHORIZATION.get());
                    });
        } finally {
            stop(tokens);
        }
    }

    @Test
    void ac_ext_75_duplicate_repositories_fail_with_tl0047() throws Exception {
        startup(context -> assertEquals(TopolithCode.TL0047, failure(context)),
                new Class<?>[] {Registrations.class, SecondRepository.class});
        startup(context -> assertEquals(TopolithCode.TL0047, failure(context)),
                new Class<?>[] {Registrations.class, SessionRepository.class, SecondSessionRepository.class});
    }

    @Test
    void ac_ext_75_expired_session_token_is_refreshed() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        HttpServer tokenServer = tokens(issued, 0);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            client(loader, "topolith.modules.account.mode=external",
                    "topolith.modules.account.remote.url=http://127.0.0.1:1")
                    .withUserConfiguration(Registrations.class, SessionRepository.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                                Map.of("name", "alice"), "name");
                        var login = new OAuth2AuthenticationToken(user, user.getAuthorities(), "svc");
                        as(login);
                        var registration = context.getBean(ClientRegistrationRepository.class)
                                .findByRegistrationId("svc");
                        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired",
                                Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
                        var refresh = new OAuth2RefreshToken("refresh", Instant.now().minusSeconds(120));
                        context.getBean(OAuth2AuthorizedClientService.class).saveAuthorizedClient(
                                new OAuth2AuthorizedClient(registration, "alice", access, refresh), login);
                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                                new MockHttpServletRequest(), new MockHttpServletResponse()));
                        assertEquals("cc-1", context.getBean(OutgoingAuth.class).relayToken("account"));
                        assertEquals(1, issued.get());
                    });
        } finally {
            stop(tokenServer);
        }
    }

    @Test
    void ac_ext_75_external_session_login_relays_authorized_client() throws Exception {
        try (Fixtures.RecordingLoader served = new Fixtures.RecordingLoader(root)) {
            server(served, "topolith.modules.account.mode=internal", "topolith.modules.account.expose.enabled=true")
                    .withUserConfiguration(ResourceServer.class).run(provider -> {
                        assertNull(provider.getStartupFailure());
                        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                            client(loader, "topolith.modules.account.mode=external",
                                    "topolith.modules.account.remote.url=http://127.0.0.1:"
                                            + ProviderErrorsTest.port(provider))
                                    .withUserConfiguration(Registrations.class, SessionRepository.class)
                                    .run(context -> {
                                        assertNull(context.getStartupFailure());
                                        var user = new DefaultOAuth2User(
                                                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                                                Map.of("name", "alice"), "name");
                                        var login = new OAuth2AuthenticationToken(user, user.getAuthorities(), "svc");
                                        as(login);
                                        String token = jwt("alice").getToken().getTokenValue();
                                        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                                                Instant.now(), Instant.now().plusSeconds(600));
                                        var registration = context.getBean(ClientRegistrationRepository.class)
                                                .findByRegistrationId("svc");
                                        context.getBean(OAuth2AuthorizedClientService.class).saveAuthorizedClient(
                                                new OAuth2AuthorizedClient(registration, "alice", access), login);
                                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                                                new MockHttpServletRequest(), new MockHttpServletResponse()));
                                        assertEquals(ALICE, context.getBean(AccountContract.class).find("auth"));
                                        assertEquals("Bearer " + token, SeenAuth.AUTHORIZATION.get());
                                    });
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondSessionRepository {
        @Bean
        OAuth2AuthorizedClientRepository otherSessionRepository(OAuth2AuthorizedClientService service) {
            return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(service);
        }
    }

    /**
     * A token endpoint that waits {@code delayMillis}, then issues {@code cc-1}, {@code cc-2} and so on, and points
     * {@link Registrations} at it.
     */
    static HttpServer tokens(AtomicInteger issued, long delayMillis) throws IOException {
        HttpServer tokens = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokens.createContext("/token", exchange -> {
            int token = issued.incrementAndGet();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("{\"access_token\":\"cc-" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException gone) {
                // the client stopped waiting
            }
            exchange.close();
        });
        tokens.setExecutor(Executors.newCachedThreadPool());
        tokens.start();
        Registrations.tokenUri = "http://127.0.0.1:" + tokens.getAddress().getPort() + "/token";
        return tokens;
    }

    static void stop(HttpServer tokens) {
        tokens.stop(0);
        Registrations.tokenUri = "http://127.0.0.1:1/token";
    }

    @Test
    void ac_ext_54_client_credentials_sends_one_token_for_the_fixed_principal_whoever_calls() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        HttpServer tokens = tokens(issued, 0);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback", AUTH + "type=client-credentials",
                    AUTH + "registration-id=svc").withUserConfiguration(Registrations.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(ContextPropagationTest.Holder.class).account();
                        as(jwt("alice"));
                        account.find("auth");
                        assertEquals("Bearer cc-1", SeenAuth.AUTHORIZATION.get());
                        as(UsernamePasswordAuthenticationToken.authenticated("bob", null, List.of()));
                        account.find("auth");
                        assertEquals("Bearer cc-1", SeenAuth.AUTHORIZATION.get());
                        // a thread outside any servlet request, with no Authentication at all
                        SeenAuth.AUTHORIZATION.set(null);
                        CompletableFuture.runAsync(() -> account.find("auth")).join();
                        assertEquals("Bearer cc-1", SeenAuth.AUTHORIZATION.get());
                        assertEquals(1, issued.get());
                        OAuth2AuthorizedClientService clients = context.getBean(OAuth2AuthorizedClientService.class);
                        assertNotNull(clients.loadAuthorizedClient("svc", "topolith:svc"));
                        assertNull(clients.loadAuthorizedClient("svc", "alice"));
                        assertNull(clients.loadAuthorizedClient("svc", "bob"));
                    });
        } finally {
            stop(tokens);
        }
    }

    @Test
    void ac_ext_60_a_slow_token_request_fails_as_module_unavailable_within_the_read_timeout() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        HttpServer tokens = tokens(issued, 3000);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback", AUTH + "type=client-credentials",
                    AUTH + "registration-id=svc", "topolith.modules.account.remote.read-timeout=300ms")
                    .withUserConfiguration(Registrations.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(ContextPropagationTest.Holder.class).account();
                        long start = System.nanoTime();
                        ModuleUnavailableException thrown = assertThrows(ModuleUnavailableException.class,
                                () -> account.find("auth"));
                        // the token endpoint never answered in time: the module's 300ms ended the wait
                        assertTrue(System.nanoTime() - start < 2_500_000_000L);
                        assertTrue(thrown.getMessage().endsWith("(timeout)"), thrown.getMessage());
                        assertEquals(1, issued.get());
                        assertEquals(0, SeenAuth.HANDLED.get());
                    });
        } finally {
            stop(tokens);
        }
    }

    /** A token endpoint answering every request with {@code status} and {@code body}. */
    private static HttpServer failingTokens(int status, String body) throws IOException {
        HttpServer tokens = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokens.createContext("/token", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        tokens.start();
        Registrations.tokenUri = "http://127.0.0.1:" + tokens.getAddress().getPort() + "/token";
        return tokens;
    }

    private void tokenEndpointAnswers(int status, String body, Consumer<Throwable> check) throws Exception {
        HttpServer tokens = failingTokens(status, body);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            server(loader, "topolith.modules.account.mode=loopback", AUTH + "type=client-credentials",
                    AUTH + "registration-id=svc").withUserConfiguration(Registrations.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(ContextPropagationTest.Holder.class).account();
                        check.accept(assertThrows(RuntimeException.class, () -> account.find("auth")));
                        assertEquals(0, SeenAuth.HANDLED.get());
                    });
        } finally {
            stop(tokens);
        }
    }

    @Test
    void ac_ext_61_a_token_endpoint_answering_503_or_429_fails_the_call_as_module_unavailable() throws Exception {
        for (int status : new int[] {503, 429}) {
            tokenEndpointAnswers(status, "", thrown -> {
                assertInstanceOf(ModuleUnavailableException.class, thrown);
                assertTrue(thrown.getMessage().endsWith("(connection failure)"), thrown.getMessage());
            });
        }
    }

    @Test
    void ac_ext_61_a_token_endpoint_answering_invalid_client_keeps_the_oauth2_exception() throws Exception {
        tokenEndpointAnswers(400, "{\"error\":\"invalid_client\"}", thrown ->
                assertInstanceOf(OAuth2AuthorizationException.class, thrown));
    }

    @Test
    void ac_ext_61_a_replaced_token_request_factory_is_closed_with_the_context() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        class Closing implements ClientHttpRequestFactory, AutoCloseable {
            public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri,
                    org.springframework.http.HttpMethod method) {
                throw new UnsupportedOperationException();
            }

            public void close() {
                closed.incrementAndGet();
            }
        }
        class Disposing implements ClientHttpRequestFactory, org.springframework.beans.factory.DisposableBean {
            public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri,
                    org.springframework.http.HttpMethod method) {
                throw new UnsupportedOperationException();
            }

            public void destroy() {
                closed.incrementAndGet();
            }
        }
        OutgoingAuth.TokenRequests requests = new OutgoingAuth.TokenRequests(id -> new Closing());
        requests.forModule("account");
        requests.replace("account", new Disposing());
        requests.replace("account", new Closing());
        // a request created just before a swap may still run on the old factory, so nothing closes yet
        assertEquals(0, closed.get());
        // a module whose factory was never requested has nothing to replace
        requests.replace("other", new Closing());
        requests.destroy();
        // the initial, the replaced and the current factory
        assertEquals(3, closed.get());
    }

    @Test
    void ac_ext_56_the_dashless_spelling_is_dropped_when_another_module_has_that_id() {
        assertEquals(List.of("a-b"), AuthSpellings.of("a-b", Set.of("ab", "a-b")));
        assertEquals(List.of("ab"), AuthSpellings.of("ab", Set.of("ab", "a-b")));
        // without a module 'ab' the dashless spelling still names a-b
        assertEquals(List.of("a-b", "ab"), AuthSpellings.of("a-b", Set.of("a-b")));
    }

    /** A host's own client-credentials token client, which needs no token endpoint. */
    @Configuration(proxyBeanMethods = false)
    static class HostTokenClient {
        @Bean
        OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> hostTokens() {
            return request -> OAuth2AccessTokenResponse.withToken("host-1")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(3600).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondTokenClient {
        @Bean
        OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> otherTokens() {
            return request -> OAuth2AccessTokenResponse.withToken("other-1")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(3600).build();
        }
    }

    @Test
    void ac_ext_60_the_hosts_token_client_bean_obtains_the_token() throws Exception {
        loopback(account -> {
            account.find("auth");
            assertEquals("Bearer host-1", SeenAuth.AUTHORIZATION.get());
        }, Fixtures.class.getClassLoader(), new Class<?>[] {Registrations.class, HostTokenClient.class},
                AUTH + "type=client-credentials", AUTH + "registration-id=svc");
    }

    /** A provider that answers its first call with {@code status} and {@code challenge}, then {@code found}. */
    private static HttpServer rejecting(int status, String challenge, List<String> seen) throws IOException {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/account/find", exchange -> {
            seen.add(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            if (seen.size() == 1) {
                exchange.getResponseHeaders().add(HttpHeaders.WWW_AUTHENTICATE, challenge);
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] body = "found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        provider.start();
        return provider;
    }

    /** Two calls of an external client-credentials module to a provider rejecting the first one. */
    private void rejected(int status, String challenge, List<String> sent, int issuedTokens, boolean kept)
            throws Exception {
        rejected(status, challenge, sent, issuedTokens, kept, Fixtures.class.getClassLoader());
    }

    private void rejected(int status, String challenge, List<String> sent, int issuedTokens, boolean kept,
            ClassLoader parent) throws Exception {
        AtomicInteger issued = new AtomicInteger();
        HttpServer tokens = tokens(issued, 0);
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer provider = rejecting(status, challenge, seen);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
            client(loader, "topolith.modules.account.mode=external", "topolith.modules.account.remote.url=http://"
                    + "127.0.0.1:" + provider.getAddress().getPort(), "topolith.modules.account.remote.read-timeout=5s",
                    AUTH + "type=client-credentials", AUTH + "registration-id=svc")
                    .withUserConfiguration(Registrations.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(AccountContract.class);
                        // the failed call is not retried
                        assertThrows(RuntimeException.class, () -> account.find("ok"));
                        assertEquals(1, seen.size());
                        OAuth2AuthorizedClientService clients = context.getBean(OAuth2AuthorizedClientService.class);
                        assertEquals(kept, clients.loadAuthorizedClient("svc", "topolith:svc") != null);
                        assertEquals("found", account.find("ok"));
                    });
        } finally {
            provider.stop(0);
            stop(tokens);
        }
        assertEquals(sent, seen);
        assertEquals(issuedTokens, issued.get());
    }

    @Test
    void ac_ext_59_a_token_the_provider_rejects_as_invalid_is_replaced_on_the_next_call() throws Exception {
        rejected(401, "Bearer error=\"invalid_token\"", List.of("Bearer cc-1", "Bearer cc-2"), 2, false);
        // a 401 naming no error counts as invalid_token
        rejected(401, "Bearer", List.of("Bearer cc-1", "Bearer cc-2"), 2, false);
    }

    @Test
    void ac_ext_59_a_servlet_free_host_still_evicts_a_token_the_provider_rejects() throws Exception {
        rejected(401, "Bearer error=\"invalid_token\"", List.of("Bearer cc-1", "Bearer cc-2"), 2, false,
                new FilteredClassLoader("jakarta.servlet"));
    }

    @Test
    void ac_ext_59_a_token_refused_for_its_scope_is_kept() throws Exception {
        rejected(403, "Bearer error=\"insufficient_scope\"", List.of("Bearer cc-1", "Bearer cc-1"), 1, true);
    }

    /** A host hook, and a customizer interceptor that a late auto-configuration registers after Topolith's own. */
    @Configuration(proxyBeanMethods = false)
    static class Hooks {
        static final AtomicReference<String> SAW = new AtomicReference<>();

        @Bean
        RestClientHttpServiceGroupConfigurer apiKey() {
            return ModuleClients.authenticate("account", (request, body, execution) -> {
                SAW.set(request.getHeaders().getFirst("X-Order"));
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "ApiKey k-1");
                return execution.execute(request, body);
            });
        }

        @Bean
        static BeanDefinitionRegistryPostProcessor lateCustomizer() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    RootBeanDefinition customizer = new RootBeanDefinition(RestClientHttpServiceGroupConfigurer.class,
                            () -> ModuleClients.configure("account", builder -> builder.requestInterceptor(
                                    (request, body, execution) -> {
                                        request.getHeaders().set("X-Order", "customizer");
                                        return execution.execute(request, body);
                                    })));
                    registry.registerBeanDefinition("lateAccountCustomizer", customizer);
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
                }
            };
        }
    }

    @Test
    void ac_ext_55_a_host_hook_is_the_modules_only_hook_and_runs_after_the_customizers() throws Exception {
        loopback(account -> {
            as(jwt("alice"));
            account.find("auth");
            assertEquals("ApiKey k-1", SeenAuth.AUTHORIZATION.get());
            assertEquals("customizer", Hooks.SAW.get());
        }, Hooks.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondHook {
        @Bean
        RestClientHttpServiceGroupConfigurer secondKey() {
            return ModuleClients.authenticate("account", (request, body, execution) -> execution.execute(request,
                    body));
        }
    }

    @Test
    void ac_ext_55_two_hooks_or_a_hook_and_an_explicit_auth_type_fail_startup_with_tl0036() throws Exception {
        startup(context -> assertEquals(TopolithCode.TL0036, failure(context)), new Class<?>[] {Hooks.class,
                SecondHook.class});
        for (String type : List.of("relay", "none", "client-credentials")) {
            startup(context -> assertEquals(TopolithCode.TL0036, failure(context), type),
                    new Class<?>[] {SecondHook.class}, AUTH + "type=" + type);
        }
        startup(context -> assertNull(context.getStartupFailure()), new Class<?>[] {SecondHook.class});
    }

    /** A customizer that sets its own {@code Authorization}, and a host hook that keeps or changes it. */
    @Configuration(proxyBeanMethods = false)
    static class Basic {
        @Bean
        RestClientHttpServiceGroupConfigurer basic() {
            return ModuleClients.configure("account", builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                    "Basic dXNlcjpwdw=="));
        }
    }

    /** A customizer whose interceptor sets {@code Authorization} per call: only the call-time check sees it. */
    @Configuration(proxyBeanMethods = false)
    static class BasicInterceptor {
        @Bean
        RestClientHttpServiceGroupConfigurer basicInterceptor() {
            return ModuleClients.configure("account", builder -> builder.requestInterceptor(
                    (request, body, execution) -> {
                        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwdw==");
                        return execution.execute(request, body);
                    }));
        }
    }

    /** The default-header customizer of {@link Basic}, registered after every auto-configuration bean. */
    @Configuration(proxyBeanMethods = false)
    static class LateBasic {
        @Bean
        static BeanDefinitionRegistryPostProcessor lateBasic() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    registry.registerBeanDefinition("lateBasicCustomizer", new RootBeanDefinition(
                            RestClientHttpServiceGroupConfigurer.class, () -> ModuleClients.configure("account",
                                    builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                                            "Basic dXNlcjpwdw=="))));
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherHeader {
        @Bean
        RestClientHttpServiceGroupConfigurer otherHeader() {
            return ModuleClients.configure("account", builder -> builder.defaultHeader("X-Custom", "yes"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApiKeyHook {
        @Bean
        RestClientHttpServiceGroupConfigurer apiKeyHook() {
            return ModuleClients.authenticate("account", (request, body, execution) -> execution.execute(request,
                    body));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Changing {
        @Bean
        RestClientHttpServiceGroupConfigurer changing() {
            return ModuleClients.authenticate("account", (request, body, execution) -> {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, "ApiKey k-1");
                return execution.execute(request, body);
            });
        }
    }

    private static void assertUnsent(AccountContract account) {
        TopolithException thrown = assertThrows(TopolithException.class, () -> account.find("auth"));
        assertEquals(TopolithCode.TL0036, thrown.diagnostics().get(0).code());
        assertEquals(0, SeenAuth.HANDLED.get());
    }

    @Test
    void ac_ext_55_a_hook_changing_the_customizers_authorization_fails_the_call_unsent() throws Exception {
        loopback(OutgoingAuthTest::assertUnsent, BasicInterceptor.class, Changing.class);
        // the relay keeps the customizer's header when there is no token, and changes it when there is one
        loopback(account -> {
            account.find("auth");
            assertEquals("Basic dXNlcjpwdw==", SeenAuth.AUTHORIZATION.get());
            SeenAuth.clear();
            as(jwt("alice"));
            assertUnsent(account);
        }, BasicInterceptor.class);
    }

    private static void startsOrFailsWithTl0036(org.springframework.boot.test.context.assertj
            .AssertableApplicationContext context, boolean fails, String what) {
        startsOrFailsWithTl0036(context.getStartupFailure(), fails, what);
    }

    private static void startsOrFailsWithTl0036(Throwable startupFailure, boolean fails, String what) {
        if (!fails) {
            assertNull(startupFailure, what);
            return;
        }
        Throwable cause = startupFailure;
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, String.valueOf(startupFailure));
        assertEquals(TopolithCode.TL0036, ((TopolithException) cause).diagnostics().get(0).code(), what);
        assertTrue(cause.getMessage().contains("'account'"), what);
    }

    @Test
    void ac_ext_55_a_default_authorization_on_a_module_with_a_hook_fails_startup_with_tl0036() throws Exception {
        // external module: relay by default, explicit relay, client-credentials, a host hook
        startup(context -> startsOrFailsWithTl0036(context, true, "relay default"), new Class<?>[] {Basic.class});
        startup(context -> startsOrFailsWithTl0036(context, true, "explicit relay"), new Class<?>[] {Basic.class},
                AUTH + "type=relay");
        startup(context -> startsOrFailsWithTl0036(context, true, "client-credentials"),
                new Class<?>[] {Basic.class, Registrations.class}, AUTH + "type=client-credentials",
                AUTH + "registration-id=svc");
        startup(context -> startsOrFailsWithTl0036(context, true, "host hook"),
                new Class<?>[] {Basic.class, ApiKeyHook.class});
        // a customizer registered after the auto-configuration is still seen (D-92)
        startup(context -> startsOrFailsWithTl0036(context, true, "late customizer"),
                new Class<?>[] {LateBasic.class});
        // loopback module
        for (String[] auth : List.of(new String[0], new String[] {AUTH + "type=relay"})) {
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(Fixtures.class.getClassLoader(),
                    root)) {
                server(loader, "topolith.modules.account.mode=loopback").withUserConfiguration(Basic.class)
                        .withPropertyValues(auth)
                        .run(context -> startsOrFailsWithTl0036(context.getStartupFailure(), true,
                                "loopback " + List.of(auth)));
            }
        }
    }

    @Test
    void ac_ext_55_no_hook_or_another_default_header_leaves_a_default_authorization_alone() throws Exception {
        startup(context -> startsOrFailsWithTl0036(context, false, "none"), new Class<?>[] {Basic.class},
                AUTH + "type=none");
        startup(context -> startsOrFailsWithTl0036(context, false, "other header"),
                new Class<?>[] {OtherHeader.class});
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(Fixtures.class.getClassLoader(), root)) {
            server(loader, "topolith.modules.account.mode=loopback", AUTH + "type=none")
                    .withUserConfiguration(Basic.class)
                    .run(context -> startsOrFailsWithTl0036(context.getStartupFailure(), false, "loopback none"));
        }
    }

    @Test
    void ac_ext_55_a_customizer_runs_before_the_hooks_configurer() {
        assertTrue(ModuleClientConfigurer.ORDER < org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    /** An external account module's client host, which needs no provider to start. */
    private void startup(Consumer<AssertableApplicationContext> check, ClassLoader parent, Class<?>[] extra,
            String... properties) throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
            client(loader, "topolith.modules.account.mode=external",
                    "topolith.modules.account.remote.url=http://127.0.0.1:1").withUserConfiguration(extra)
                    .withPropertyValues(properties).run(check::accept);
        }
    }

    private void startup(Consumer<AssertableApplicationContext> check, Class<?>[] extra, String... properties)
            throws Exception {
        startup(check, Fixtures.class.getClassLoader(), extra, properties);
    }

    private static TopolithCode failure(AssertableApplicationContext context) {
        Throwable cause = context.getStartupFailure();
        while (cause != null && !(cause instanceof TopolithException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, String.valueOf(context.getStartupFailure()));
        return ((TopolithException) cause).diagnostics().get(0).code();
    }

    private void internal(Consumer<AssertableApplicationContext> check, Class<?>[] extra, String... properties)
            throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            client(loader, "topolith.modules.account.mode=internal").withUserConfiguration(extra)
                    .withPropertyValues(properties).run(check::accept);
        }
    }

    @Test
    void ac_ext_56_misplaced_or_invalid_auth_keys_fail_with_tl0003() throws Exception {
        internal(context -> assertEquals(TopolithCode.TL0003, failure(context)), new Class<?>[0],
                AUTH + "type=relay");
        internal(context -> assertEquals(TopolithCode.TL0003, failure(context)), new Class<?>[0],
                "topolith.modules.payment.auth.type=none");
        internal(context -> assertEquals(TopolithCode.TL0003, failure(context)), new Class<?>[] {SecondHook.class});
        for (String[] keys : List.of(new String[] {AUTH + "scheme=bearer"}, new String[] {AUTH + "type=basic"},
                new String[] {AUTH + "registration-id=svc"}, new String[] {AUTH + "type=relay",
                        AUTH + "registration-id=svc"})) {
            startup(context -> assertEquals(TopolithCode.TL0003, failure(context), keys[0]), new Class<?>[0],
                    keys);
        }
        // the type is read ignoring case and surrounding blanks
        startup(context -> assertNull(context.getStartupFailure()), new Class<?>[0], AUTH + "type= None ");
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondRepository {
        @Bean
        ClientRegistrationRepository otherRegistrations() {
            return Registrations.repository();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondService {
        @Bean
        OAuth2AuthorizedClientService otherClients() {
            return new InMemoryOAuth2AuthorizedClientService(Registrations.repository());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyService {
        @Bean
        OAuth2AuthorizedClientService clients() {
            return new InMemoryOAuth2AuthorizedClientService(Registrations.repository());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyRepository {
        @Bean
        ClientRegistrationRepository registrations() {
            return Registrations.repository();
        }
    }

    @Test
    void ac_ext_56_an_unsatisfiable_client_credentials_hook_fails_with_tl0037() throws Exception {
        String type = AUTH + "type=client-credentials";
        String svc = AUTH + "registration-id=svc";
        Consumer<AssertableApplicationContext> tl0037 = context -> assertEquals(TopolithCode.TL0037,
                failure(context));
        startup(tl0037, new FilteredClassLoader("org.springframework.security.oauth2.client"),
                new Class<?>[] {Registrations.class}, type, svc);
        startup(tl0037, new Class<?>[] {Registrations.class}, type);
        startup(tl0037, new Class<?>[] {Registrations.class}, type, AUTH + "registration-id=other");
        startup(tl0037, new Class<?>[0], type, svc);
        startup(tl0037, new Class<?>[] {OnlyService.class}, type, svc);
        startup(tl0037, new Class<?>[] {OnlyRepository.class}, type, svc);
        startup(tl0037, new Class<?>[] {Registrations.class, SecondRepository.class}, type, svc);
        startup(tl0037, new Class<?>[] {Registrations.class, SecondService.class}, type, svc);
        startup(tl0037, new Class<?>[] {Registrations.class, HostTokenClient.class, SecondTokenClient.class}, type,
                svc);
        startup(context -> {
            assertNull(context.getStartupFailure());
            ClientHttpRequestInterceptor hook = context.getBean(OutgoingAuth.class).hooks().get("account");
            assertEquals("OAuth2ClientHttpRequestInterceptor", hook.getClass().getSimpleName());
        }, new Class<?>[] {Registrations.class}, type, svc);
    }

    @Test
    void ac_ext_57_an_internal_provider_sees_the_callers_security_context_unchanged() throws Exception {
        internal(context -> {
            assertNull(context.getStartupFailure());
            JwtAuthenticationToken alice = jwt("alice");
            as(alice);
            SecurityContext caller = SecurityContextHolder.getContext();
            assertEquals(ALICE, context.getBean(AccountContract.class).find("auth"));
            assertSame(caller, SeenAuth.CONTEXT.get());
            assertSame(alice, SeenAuth.CONTEXT.get().getAuthentication());
            // and Topolith neither copies nor clears it
            assertSame(caller, SecurityContextHolder.getContext());
            assertSame(alice, SecurityContextHolder.getContext().getAuthentication());
        }, new Class<?>[0]);
    }

    @Test
    void ac_ext_57_an_internal_or_none_module_gets_no_auth_hook() throws Exception {
        // account internal, payment and boundary none: no client, so no hook and no auth bean at all
        internal(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(OutgoingAuth.class).isEmpty());
        }, new Class<?>[0]);
        // an external module beside the none ones: only it gets a hook
        startup(context -> {
            assertNull(context.getStartupFailure());
            var hooks = context.getBean(OutgoingAuth.class).hooks();
            assertEquals(Set.of("account"), hooks.keySet());
            assertInstanceOf(RelayHook.class, hooks.get("account"));
        }, new Class<?>[0]);
    }

    @Test
    void ac_ext_57_a_host_without_spring_security_starts_and_sends_no_authorization() throws Exception {
        FilteredClassLoader absent = new FilteredClassLoader("org.springframework.security");
        Consumer<AccountContract> unsent = account -> {
            as(jwt("alice"));
            assertNotEquals(ALICE, account.find("auth"));
            assertNull(SeenAuth.AUTHORIZATION.get());
        };
        loopback(unsent, absent, new Class<?>[0]);
        external(unsent, absent);
        startup(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(OutgoingAuth.class).hooks().isEmpty());
        }, absent, new Class<?>[0]);
    }
}
