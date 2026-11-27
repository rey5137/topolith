# Worked Example: Orders and Payments

This page builds one feature end to end with the code in `samples/`. A `user` module places orders, and each order
charges a `payment` module. The same two modules then run as one process, as two services, and as a payment-only
host. Only configuration and dependencies change between them.

Every code block on this page is copied from `samples/`, and the build fails if a block drifts from its source. The
responses under [Run it](#5-run-it-and-watch-the-calls) were recorded from the sample hosts.

## The Pieces

Each module is three Maven artifacts. Hosts pick which ones they put on the classpath.

| Artifact | Holds | Depends on |
|---|---|---|
| `payment-api` | `PaymentApi`, its request and result records, its declared exceptions | Spring Web annotations only |
| `payment-module` | `@TopolithModule` descriptor, `PaymentController`, properties, migrations | `payment-api` |
| `payment-service` | A Boot application that runs `payment` alone | `payment-module` |
| `user-api` | `UserApi` and its records | Spring Web annotations only |
| `user-module` | descriptor, `UserController`, migrations | `user-api`, `payment-api` |
| `user-service` | A Boot application that runs `user` alone, calling payment remotely | `user-module` |

```text
samples/
├── payment/
│   ├── payment-api/       io.github.rey5137.topolith.samples.payment.api
│   ├── payment-module/    io.github.rey5137.topolith.samples.payment
│   └── payment-service/
├── user/
│   ├── user-api/          io.github.rey5137.topolith.samples.user.api
│   ├── user-module/       io.github.rey5137.topolith.samples.user
│   └── user-service/
├── payment-tests/         test cases shared by every host
└── hosts/
    ├── monolith/          user internal, payment internal
    ├── hybrid/            user internal, payment external
    └── payment-host/      payment internal, user none
```

`user-module` depends on `payment-api`, never on `payment-module`. That one rule is what lets a host leave the payment
implementation out.

## 1. Write the Contract (`payment-api`)

The contract is a Spring HTTP interface. Topolith calls it in-process when payment is internal, and over HTTP when it
is external, so it has to make sense both ways:

<!-- snippet: samples/payment/payment-api/src/main/java/io/github/rey5137/topolith/samples/payment/api/PaymentApi.java#payment-api -->
```java
/** The payment contract every topology consumes. */
@HttpExchange
public interface PaymentApi {
    /** Charges a payment and returns the stored result. */
    @PostExchange("/charges")
    ChargeResult charge(@RequestBody ChargeRequest request);

    /** Finds a charge by id. */
    @GetExchange("/charges/{id}")
    ChargeResult find(@PathVariable("id") String id);

    /** Reports the {@code requestId} MDC value and the tenant the provider sees for this call. */
    @GetExchange("/context")
    CallContext context();
}
```

Arguments and results are values. A record whose fields are immutable behaves the same whether the call is a method
call or a JSON round trip:

<!-- snippet: samples/payment/payment-api/src/main/java/io/github/rey5137/topolith/samples/payment/api/ChargeRequest.java#charge-request -->
```java
/**
 * A charge request, carried by value in every mode. The tag list is copied into an unmodifiable list, so neither the
 * caller's later edits nor a provider can change what the other side holds (INV-6).
 */
public record ChargeRequest(String reference, BigDecimal amount, List<String> tags) {
    public ChargeRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public ChargeRequest(String reference, BigDecimal amount) {
        this(reference, amount, List.of());
    }
}
```

The `List.copyOf` matters. In internal mode the caller and the provider hold the same `ChargeRequest` object. Without
the copy, a caller that edits its list after the call would change what payment stored, which can never happen over
HTTP. The shared test `ac_test_05_caller_edit_after_charge_does_not_change_the_stored_tags` checks this in every host.
See [Semantic parity](../parity.md) for the full list of rules.

Business failures are declared exceptions in the api package. `@ResponseStatus` fixes the HTTP status they cross with:

<!-- snippet: samples/payment/payment-api/src/main/java/io/github/rey5137/topolith/samples/payment/api/PaymentDeclinedException.java#payment-declined -->
```java
/** A declined charge; a declared contract exception crossing HTTP as ProblemDetail. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
```

A remote caller gets a `PaymentDeclinedException` back, not a generic HTTP error, so `catch (PaymentDeclinedException
e)` works in every mode.

Keep out of the api package anything that only works in one process: JPA entities, `Page`, `Stream`, mutable
collections, `ThreadLocal` context.

## 2. Implement the Module (`payment-module`)

Mark the module's root package with `@TopolithModule`. The `api` attribute names the contract package:

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/package-info.java#payment-module-descriptor -->
```java
@TopolithModule(id = "payment", api = "io.github.rey5137.topolith.samples.payment.api")
package io.github.rey5137.topolith.samples.payment;

import io.github.rey5137.topolith.annotation.TopolithModule;
```

The implementation is a controller that implements the contract. Its routes are served under the module's fixed
`/payment` prefix:

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/PaymentController.java#payment-controller-header -->
```java
/** Serves the payment contract under the module's fixed {@code /payment} prefix. */
@RestController
public class PaymentController implements PaymentApi {
```

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/PaymentController.java#payment-charge -->
```java
@Override
public ChargeResult charge(ChargeRequest request) {
    BigDecimal amount = request.amount();
    if (amount == null || amount.signum() <= 0) {
        throw new PaymentDeclinedException("amount must be positive");
    }
    if (amount.compareTo(properties.getMaxAmount()) > 0) {
        throw new PaymentDeclinedException("amount exceeds the configured maximum");
    }
    String id = UUID.randomUUID().toString();
    jdbc.update("insert into payment_charge (id, reference, amount, status, tags) values (?, ?, ?, ?, ?)",
            id, request.reference(), amount, "CHARGED", String.join(TAG_SEPARATOR, request.tags()));
    return new ChargeResult(id, request.reference(), amount, "CHARGED", request.tags());
}
```

The module ships its own defaults in code, because a module JAR carries no root `application.yml`. A host overrides
them with ordinary properties, for example `payment.max-amount: 500`:

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/PaymentProperties.java#payment-properties -->
```java
/** The payment module's own defaults; a module JAR carries no root {@code application.yml}. */
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
    private BigDecimal maxAmount = new BigDecimal("1000");

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public void setMaxAmount(BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }
}
```

Migrations live under `db/modules/<id>/`. They run only in a host where the module is internal, with a history table
of their own:

<!-- snippet: samples/payment/payment-module/src/main/resources/db/modules/payment/V1__payment.sql#payment-migration -->
```sql
create table payment_charge (
    id varchar(64) primary key,
    reference varchar(255) not null,
    amount decimal(19, 2) not null,
    status varchar(32) not null,
    tags varchar(1024) not null default ''
);
```

## 3. Call Another Module (`user-module`)

`user` declares that it needs payment with `requires`:

<!-- snippet: samples/user/user-module/src/main/java/io/github/rey5137/topolith/samples/user/package-info.java#user-module-descriptor -->
```java
@TopolithModule(id = "user", api = "io.github.rey5137.topolith.samples.user.api", requires = "payment")
package io.github.rey5137.topolith.samples.user;

import io.github.rey5137.topolith.annotation.TopolithModule;
```

The caller injects `PaymentApi` and calls it. Nothing in this class says where payment runs:

<!-- snippet: samples/user/user-module/src/main/java/io/github/rey5137/topolith/samples/user/UserController.java#user-place-order -->
```java
/** Serves the user contract under the module's fixed {@code /user} prefix and calls payment through its api. */
@RestController
public class UserController implements UserApi {
    private final JdbcTemplate jdbc;
    private final PaymentApi payments;

    public UserController(JdbcTemplate jdbc, PaymentApi payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @Override
    public OrderView placeOrder(PlaceOrderRequest request) {
        BigDecimal amount = request.amount();
        ChargeResult charge = payments.charge(new ChargeRequest(request.reference(), amount));
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into user_order (id, payment_id, reference, amount) values (?, ?, ?, ?)",
                id, charge.id(), request.reference(), amount);
        return new OrderView(id, charge.id(), request.reference(), amount);
    }
```

Topolith supplies the `PaymentApi` bean from the host's configuration:

| `payment` mode in the host | The injected `PaymentApi` is | `payments.charge(...)` becomes |
|---|---|---|
| `internal` | the payment module's own `PaymentController` | a method call in the same JVM |
| `external` | an HTTP client built from the contract's `@HttpExchange` methods | `POST {remote.url}/payment/charges` |
| `none` | not registered: a host with `user` internal fails at startup with TL0004, because `user` requires payment | nothing |

## 4. Choose a Topology per Host

A host is an ordinary Spring Boot application. It imports the [Platform BOM](getting-started.md#1-import-the-platform-bom),
adds the module JARs it runs, and sets one mode per module.

### Monolith: everything in one process

The monolith depends on `user-module` and `payment-module`, and sets both internal:

<!-- snippet: samples/hosts/monolith/src/main/resources/application.yml#monolith-modes -->
```yaml
topolith:
  modules:
    user:
      mode: internal
      expose:
        enabled: true
    payment:
      mode: internal
```

`user.expose.enabled` publishes `/user/**` to outside clients. Payment is not exposed, so in the monolith
`POST /payment/charges` answers 404, and only `user` can reach payment.

### Hybrid: user here, payment in its own service

The hybrid host puts `payment-api` on its classpath, but not `payment-module`:

<!-- snippet: samples/hosts/hybrid/pom.xml#hybrid-module-deps -->
```xml
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>user-api</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>user-module</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>io.github.rey5137</groupId>
    <artifactId>payment-api</artifactId>
    <version>${project.version}</version>
</dependency>
```

It marks payment external and gives its address:

<!-- snippet: samples/hosts/hybrid/src/main/resources/application.yml#hybrid-modes -->
```yaml
topolith:
  modules:
    user:
      mode: internal
      expose:
        enabled: true
    payment:
      mode: external
      remote:
        # payment-service's port (8081); HybridPaymentTest overrides it with the container's mapped port
        url: http://localhost:8081
        connect-timeout: 2s
        read-timeout: 5s
```

On the other side, `payment-service` runs the payment module alone and exposes its routes:

<!-- snippet: samples/payment/payment-service/src/main/resources/config/payment-service/application.yml#payment-service-modes -->
```yaml
topolith:
  standalone: payment
  modules:
    payment:
      expose:
        enabled: true
```

`topolith.standalone: payment` makes payment the internal module by default, so a standalone service does not list
every other module. `user-service` is the mirror image: `user` runs here, and payment is external:

<!-- snippet: samples/user/user-service/src/main/resources/config/user-service/application.yml#user-service-modes -->
```yaml
topolith:
  standalone: user
  modules:
    user:
      expose:
        enabled: true
    payment:
      mode: external
      remote:
        url: http://localhost:8081
        connect-timeout: 2s
        read-timeout: 5s
```

To find payment through Consul instead of a fixed URL, see [External mode](../external-mode.md).

### Payment host: user switched off

<!-- snippet: samples/hosts/payment-host/src/main/resources/application.yml#payment-host-modes -->
```yaml
topolith:
  modules:
    payment:
      mode: internal
    user:
      mode: none
```

`user-module` is still on this host's classpath, but `none` leaves no trace of it: no beans, no `/user` routes, no
`user_order` table and no user migration history. `PaymentHostPaymentTest` asserts each of these.

### Summary

| Host | Classpath | `user` | `payment` | `POST /user/orders` | `POST /payment/charges` |
|---|---|---|---|---|---|
| `monolith` | user-module, payment-module | internal, exposed | internal | 200, in-process charge | 404 |
| `hybrid` | user-module, payment-api | internal, exposed | external, `localhost:8081` | 200, charge over HTTP | 404 |
| `payment-service` | payment-module | absent | internal, exposed | 404 | 200 |
| `payment-host` | user-module, payment-module | none | internal | 404 | 404 |

## 5. Run It and Watch the Calls

Start `payment-service` (port 8081), then the hybrid host (port 8080):

```bash
./mvnw -q install -DskipTests
./mvnw -pl samples/payment/payment-service spring-boot:run
./mvnw -pl samples/hosts/hybrid spring-boot:run
```

Place an order on the hybrid host. `user` charges payment over HTTP and stores the payment id:

```console
$ curl -s -H 'Content-Type: application/json' \
    -d '{"reference":"order-42","amount":25.00}' localhost:8080/user/orders
{"id":"7e828dbf-4aed-432e-a634-1a06da23b2c8","paymentId":"7a62e59f-0b06-4827-853f-4d249f4f9699","reference":"order-42","amount":25.00}
```

Ask for more than `payment.max-amount` (1000 by default). Payment-service throws `PaymentDeclinedException`, it
crosses HTTP as a 422 problem, and the user host gets a `PaymentDeclinedException` back:

```console
$ curl -s -i -H 'Content-Type: application/json' \
    -d '{"reference":"order-43","amount":5000}' localhost:8080/user/orders
HTTP/1.1 422
Content-Type: application/problem+json

{"detail":"amount exceeds the configured maximum","status":422,"title":"Unprocessable Content","type":"urn:topolith:problem:contract-exception","exception":"io.github.rey5137.topolith.samples.payment.api.PaymentDeclinedException","module":"user"}
```

Run the same two requests against the monolith and the responses match, apart from the generated ids. Only the
topology changed.

Stop payment-service and place another order. Topolith raises `ModuleUnavailableException`, which the user module can
catch. Uncaught, it answers 503:

```console
$ curl -s -i -H 'Content-Type: application/json' \
    -d '{"reference":"order-44","amount":10}' localhost:8080/user/orders
HTTP/1.1 503
Content-Type: application/problem+json

{"detail":"module 'payment' could not be reached (unavailable downstream)","status":503,"title":"Service Unavailable","type":"urn:topolith:problem:contract-exception","exception":"io.github.rey5137.topolith.contract.ModuleUnavailableException","module":"payment"}
```

## 6. Test Every Topology with One Test Class

The payment cases are written once, against `PaymentApi`, in `samples/payment-tests`:

<!-- snippet: samples/payment-tests/src/test/java/io/github/rey5137/topolith/samples/paymenttests/AbstractPaymentTest.java#shared-payment-test -->
```java
/** The payment contract every sample host must satisfy (AC-TEST-05); shipped as this module's test-jar. */
@Import(PaymentTenants.class)
@TestPropertySource(properties = "topolith.context.mdc-keys=requestId")
public abstract class AbstractPaymentTest {
    @Autowired
    protected PaymentApi paymentApi;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected Environment environment;

    @Test
    void ac_test_05_charge_then_find_returns_the_stored_charge() {
        String reference = "charge-" + UUID.randomUUID();
        ChargeRequest request = new ChargeRequest(reference, new BigDecimal("12.50"));
        ChargeResult charged = paymentApi.charge(request);
        assertNotNull(charged.id());
        assertEquals("CHARGED", charged.status());
        ChargeResult found = paymentApi.find(charged.id());
        assertEquals(charged.id(), found.id());
        assertEquals(reference, found.reference());
        assertEquals(0, request.amount().compareTo(found.amount()));
    }
```

Each host runs them by extending the class and naming the mode payment runs in; a shared case checks that payment
really runs in it. The monolith runs them twice. First with payment internal behind the by-value proxy, which passes
every argument and result as a JSON copy and fails the test when payment changes an object it shares with its caller,
as an HTTP call would never let it:

<!-- snippet: samples/hosts/monolith/src/test/java/io/github/rey5137/topolith/samples/hosts/monolith/MonolithPaymentTest.java#monolith-payment-test -->
```java
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TopolithByValue("payment")
class MonolithPaymentTest extends AbstractPaymentTest {
    @Override
    protected Mode paymentMode() {
        return Mode.INTERNAL;
    }
```

Then with payment in loopback mode: still in the same JVM, but every call goes over HTTP to the test's own server:

<!-- snippet: samples/hosts/monolith/src/test/java/io/github/rey5137/topolith/samples/hosts/monolith/MonolithLoopbackPaymentTest.java#monolith-loopback-payment-test -->
```java
@SpringBootTest(classes = MonolithApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:monolith-loopback;DB_CLOSE_DELAY=-1")
@AutoConfigureTestRestTemplate
@TopolithModuleMode(id = "payment", mode = Mode.LOOPBACK)
class MonolithLoopbackPaymentTest extends AbstractPaymentTest {
    @Override
    protected Mode paymentMode() {
        return Mode.LOOPBACK;
    }
```

The hybrid test starts the real `payment-service` JAR in a Testcontainers container and points the host at it:

<!-- snippet: samples/hosts/hybrid/src/test/java/io/github/rey5137/topolith/samples/hosts/hybrid/HybridPaymentTest.java#hybrid-payment-test -->
```java
@SpringBootTest(classes = HybridApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class HybridPaymentTest extends AbstractPaymentTest {
    private static final String PAYMENT_PACKAGE = "io.github.rey5137.topolith.samples.payment.";
    private static final int PAYMENT_PORT = PaymentServiceContainers.freePort();

    private static GenericContainer<?> paymentService;

    @Autowired
    ConfigurableApplicationContext context;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        if (paymentService == null) {
            paymentService = PaymentServiceContainers.paymentService(PAYMENT_PORT);
            paymentService.start();
        }
        registry.add("topolith.modules.payment.remote.url", () -> "http://localhost:" + PAYMENT_PORT);
    }
```

The same test methods run in all three hosts, and payment runs internal, loopback and external among them: a contract
that only works in-process fails one of the runs. The order-route case runs only where `user` is internal, so the
payment host skips it. To test `user` without running payment at all, use mock mode; see
[Testing](../testing.md).

## Apply It to Your Project

1. Split each module into `<id>-api` (contract, records, declared exceptions) and `<id>-module` (descriptor,
   implementation, `db/modules/<id>/` migrations).
2. Make every caller depend on the other module's `-api` only, and declare the dependency in `requires`.
3. Start with every module `internal` in one host, as in the monolith.
4. To move a module out, add a `*-service` application with `topolith.standalone: <id>` and `expose.enabled: true`,
   then switch the module to `external` in the host, with a `remote.url`.
5. Write the module's tests once against its contract and run them in each host.

For an existing application, [Legacy adoption](legacy-adoption.md) covers the order of these steps. Every key used
here is listed in [Configuration](configuration.md).
