# Getting Started

Topolith lets a Spring Boot business module run in-process (`internal`), remote (`external`) or not at all (`none`),
chosen per host in configuration, without changing the module's code.

## 1. Import the Platform BOM

An adopter project sets `<topolith.version>` to the Topolith release (for example, `0.1.0`) and imports `topolith-bom`
in `<dependencyManagement>`. The BOM manages Topolith modules together with their baseline Spring Boot and Spring Cloud
versions:

<!-- snippet: samples/hosts/pom.xml#bom-import -->
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.rey5137</groupId>
            <artifactId>topolith-bom</artifactId>
            <version>${topolith.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## 2. Declare a Module (`*-api` and `*-module`)

Split a module into a contract JAR (`<id>-api`) and an implementation JAR (`<id>-module`). The contract interface
carries Spring's `@HttpExchange` methods so it works both in-process and over HTTP:

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

In `<id>-module`, place `@TopolithModule` on `package-info.java` in the module's root package:

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/package-info.java#payment-module-descriptor -->
```java
@TopolithModule(id = "payment", api = "io.github.rey5137.topolith.samples.payment.api")
package io.github.rey5137.topolith.samples.payment;

import io.github.rey5137.topolith.annotation.TopolithModule;
```

When a module depends on another module's contract, list the dependency in `requires`:

<!-- snippet: samples/user/user-module/src/main/java/io/github/rey5137/topolith/samples/user/package-info.java#user-module-descriptor -->
```java
@TopolithModule(id = "user", api = "io.github.rey5137.topolith.samples.user.api", requires = "payment")
package io.github.rey5137.topolith.samples.user;

import io.github.rey5137.topolith.annotation.TopolithModule;
```

## 3. Configure the Annotation Processor

Configure `topolith-processor` and `topolith-core` (at `${topolith.version}`) on `maven-compiler-plugin`'s
`<annotationProcessorPaths>` in both `<id>-api` and `<id>-module`, passing `-Atopolith.apiVersion=${project.version}` so
the processor writes `META-INF/topolith/apis/<id>.properties` and `META-INF/topolith/modules/<id>.properties` at compile
time:

<!-- snippet: samples/payment/payment-module/pom.xml#processor-config -->
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.rey5137</groupId>
                <artifactId>topolith-processor</artifactId>
                <version>${topolith.version}</version>
            </path>
            <path>
                <groupId>io.github.rey5137</groupId>
                <artifactId>topolith-core</artifactId>
                <version>${topolith.version}</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-Atopolith.apiVersion=${project.version}</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

## 4. Choose Modes in a Host (`monolith` vs `hybrid` vs `none`)

A host is a standard Spring Boot application that depends on `topolith-spring-boot` and states the mode of every known
module on its classpath. Any host that configures an `external` module must also include
`org.springframework.boot:spring-boot-starter-restclient` (otherwise startup fails with `TL0026`); service-id
discovery via `remote.service-id` additionally requires `org.springframework.cloud:spring-cloud-starter-loadbalancer`
(`TL0009`).

In a **monolith** host, both `user` and `payment` run `internal`:

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

In a **hybrid** host, `user` runs `internal` while `payment` is `external` over HTTP (`remote.url` or
`remote.service-id`):

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

In a **payment-only** host, `payment` is `internal` and `user` is `none` (contributing no beans, routes, entities or
migrations even when `user-module` is on the classpath):

<!-- snippet: samples/hosts/payment-host/src/main/resources/application.yml#payment-host-modes -->
```yaml
topolith:
  modules:
    payment:
      mode: internal
    user:
      mode: none
```

## 5. Running Monolith vs Hybrid

Install the reactor modules once, then run a host directly with `spring-boot:run`. For the monolith host (runs on port
8080 with both modules in-process):

```bash
./mvnw -q install -DskipTests
./mvnw -pl samples/hosts/monolith spring-boot:run
```

For the hybrid topology, start `payment-service` first (listens on port 8081), then start the hybrid host (listens on
port 8080 and calls `payment` at `http://localhost:8081`):

```bash
./mvnw -pl samples/payment/payment-service spring-boot:run
./mvnw -pl samples/hosts/hybrid spring-boot:run
```

## 6. Testing with `topolith-spring-test`

Add `topolith-spring-test` in `<scope>test</scope>` to override module modes per test class with `@TopolithModuleMode`
and stub external modules with `@TopolithMock` and `TopolithMockServer`:

<!-- snippet: samples/hosts/monolith/src/test/java/io/github/rey5137/topolith/samples/hosts/monolith/MonolithMockPaymentTest.java#test-helpers-mock -->
```java
@SpringBootTest(classes = MonolithApplication.class,
        properties = "spring.datasource.url=jdbc:h2:mem:monolith-mock;DB_CLOSE_DELAY=-1")
@TopolithModuleMode(id = "user", mode = Mode.INTERNAL)
@TopolithModuleMode(id = "payment", mode = Mode.EXTERNAL)
class MonolithMockPaymentTest {
    @TopolithMock(id = "payment")
    TopolithMockServer paymentMock;

    @Autowired
    UserApi userApi;

    @Test
    void user_places_order_against_mocked_external_payment() {
        paymentMock.stub("POST", "/charges", 200,
                "{\"id\":\"chg-1\",\"reference\":\"ord-1\",\"amount\":10.00,\"status\":\"CHARGED\",\"tags\":[]}");

        OrderView order = userApi.placeOrder(new PlaceOrderRequest("ord-1", new BigDecimal("10.00")));

        assertEquals("chg-1", order.paymentId());
        assertEquals(1, paymentMock.requests().size());
        TopolithMockServer.Request recorded = paymentMock.requests().get(0);
        assertEquals("POST", recorded.method());
        assertEquals("/payment/charges", recorded.path());
        assertEquals("{\"reference\":\"ord-1\",\"amount\":10.00,\"tags\":[]}", recorded.body());
    }
}
```

## Next

The [Worked example](worked-example.md) walks through every piece of the samples step by step: contract values,
declared exceptions, the calling module, each host's configuration, recorded requests and responses, and the shared
tests.
