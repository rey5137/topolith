# Legacy Service Adoption Checklist

This checklist covers the seven steps for refactoring an existing one-service-per-repo Spring Boot
application into a Topolith module that can run as its own standalone service or bundled inside a composed host. Before step 7, or whenever a service is not ready to bundle in-JVM, other hosts consume it as `external`
from day one (it only needs to publish `<id>-api`, or consumers write a client). In 0.1 the refactor is manual; a
`topolith-maven-plugin` `adopt` goal mentioned in RFC 0001 §4.3 is planned for a later release.

## 1. Split into a library JAR and a thin `main`

- **What to change:** Move the service's contracts into `<id>-api` and its implementation into `<id>-module` (plain
  library JARs without Boot's repackage goal). Keep `<id>-service` as a thin runner with a `main` class and
  `topolith.standalone=<id>`.
- **How to check:** `<id>-module` builds a normal library JAR that composed hosts can depend on; startup fails with
  `TL0002` if a host sets `topolith.modules.<id>.mode=internal` without `<id>-module` on the classpath, or `TL0025` if a
  `topolith.standalone` runner sets another module with a descriptor to `internal`.

<!-- snippet: samples/payment/payment-service/src/main/java/io/github/rey5137/topolith/samples/payment/service/PaymentServiceApplication.java#payment-service-main -->
```java
/** Standalone payment service: only payment defaults to internal. */
@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(PaymentServiceApplication.class)
                .properties("spring.config.location=classpath:/config/payment-service/")
                .run(args);
    }
}
```

## 2. Add `@TopolithModule` in `package-info.java`

- **What to change:** Annotate the module's root `package-info.java` with `@TopolithModule(id = "<id>", api = "...")`
  and configure `topolith-processor` with `-Atopolith.apiVersion=${project.version}`.
- **How to check:** The build generates `META-INF/topolith/modules/<id>.properties` and
  `META-INF/topolith/apis/<id>.properties`. Malformed descriptors or missing `-Atopolith.apiVersion` fail with `TL0013`;
  duplicate ids fail with `TL0006`; nested module packages fail with `TL0017`; mismatched API and module `provides` fail
  with `TL0022`.

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/package-info.java#payment-module-descriptor -->
```java
@TopolithModule(id = "payment", api = "io.github.rey5137.topolith.samples.payment.api")
package io.github.rey5137.topolith.samples.payment;

import io.github.rey5137.topolith.annotation.TopolithModule;
```

## 3. Move the root `application.yml` defaults into `@ConfigurationProperties(prefix="<id>")` or `config/<id>.yml`

- **What to change:** Remove `application.yml` / `application.properties` from the root of `<id>-module` so resources at
  the classpath root do not shadow each other in a bundle. Move module defaults into
  `@ConfigurationProperties(prefix="<id>")`, or into `config/<id>.yml` imported by the module (while keeping the
  standalone `<id>-service` runner's configuration under `classpath:/config/<id>-service/application.yml`).
- **How to check:** Verify `<id>-module` has no root `application.*` resource, and that when `<id>` is `none` or
  `external` its `@ConfigurationProperties` bean is absent (`TL0007` fails startup if a host scan registers a
  non-internal module's configuration properties).

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

## 4. Module-owned `DataSource`/EMF/TX manager (pattern A), or shared JPA with entities filtered

- **What to change:** Choose shared JPA and Spring Data JDBC (Topolith filters managed JPA entities and repositories per
  `internal` module) or Pattern A module-owned persistence (`DataSource`, `EntityManagerFactory`, transaction manager
  declared with `@Bean(defaultCandidate = false)` and a qualifier).
- **How to check:** Host auto-configuration or entity scans overlapping a module package fail with `TL0018`; an internal
  module contributing a default-candidate `DataSource`, `EntityManagerFactory`, transaction manager, Flyway, Liquibase
  or `AbstractJdbcConfiguration` (or another bean that makes auto-configuration back off) fails with `TL0019` or
  `TL0031`; unmanaged or doubly-managed entities fail with `TL0021`; uncovered repositories fail with `TL0024`.

## 5. Flyway location `db/migration/<id>` (`db/modules/<id>` in 0.1)

- **What to change:** Move the module's Flyway (or Liquibase) scripts out of `db/migration` into
  `classpath:db/modules/<id>` so each internal module runs its own migrations with its own tracking table after the
  host's migrations and those of required modules. Note: 0.1 uses `db/modules/<id>` in place of RFC 0001 §4.3's
  `db/migration/<id>` because Flyway scans `db/migration` recursively by default and would otherwise run `none`
  modules' scripts.
- **How to check:** Any module migration that would not run exactly once (missing runner or `DataSource`, both tools
  present, or a host migration location reaching `db/modules/<id>`) fails startup with `TL0020`. A `none` or `external`
  module runs no migrations and creates no tables.

## 6. Fixed path prefix `/<id>`

- **What to change:** Implement the `@HttpExchange` contract on the module's `@RestController` with paths relative to
  the module root. Topolith mounts exposed internal module controllers under `/<id>` when
  `topolith.modules.<id>.expose.enabled=true` and maps external clients to `<remote.url>/<id>` or
  `lb://<service-id>/<id>`.
- **How to check:** Routes appear under `/<id>/...` when `expose.enabled=true` and are absent when
  `expose.enabled=false` (default). Exposing a non-internal module fails with `TL0003`; exposing on a reactive WebFlux
  host fails with `TL0028`; a `final` controller provider class when a transaction manager is present fails with
  `TL0029`.

<!-- snippet: samples/payment/payment-module/src/main/java/io/github/rey5137/topolith/samples/payment/PaymentController.java#payment-controller-header -->
```java
/** Serves the payment contract under the module's fixed {@code /payment} prefix. */
@RestController
public class PaymentController implements PaymentApi {
```

## 7. Upgrade to the platform BOM's Boot major version

- **What to change:** Import `topolith-bom` and upgrade the module to the platform BOM's Spring Boot major version so
  bundled modules share the same JVM and libraries, and configure `maven-enforcer-plugin` with `dependencyConvergence`
  and `requireUpperBoundDeps` in the host build.
- **How to check:** `./mvnw verify` on the host POM passes the `dependencyConvergence` and `requireUpperBoundDeps`
  enforcer rules with no version conflicts across the bundled module JARs.

<!-- snippet: samples/hosts/pom.xml#host-enforcer -->
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <version>3.7.0</version>
    <executions>
        <execution>
            <id>host-enforce</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <dependencyConvergence/>
                    <requireUpperBoundDeps/>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```
