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
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
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
        Hooks.SAW.set(null);
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
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
            return new DefaultOAuth2AuthorizedClientManager(repository(),
                    new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClients()));
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
        AtomicInteger issued = new AtomicInteger();
        HttpServer tokens = tokens(issued, 0);
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer provider = rejecting(status, challenge, seen);
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
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
        loopback(OutgoingAuthTest::assertUnsent, Basic.class, Changing.class);
        // the relay keeps the customizer's header when there is no token, and changes it when there is one
        loopback(account -> {
            account.find("auth");
            assertEquals("Basic dXNlcjpwdw==", SeenAuth.AUTHORIZATION.get());
            SeenAuth.clear();
            as(jwt("alice"));
            assertUnsent(account);
        }, Basic.class);
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
