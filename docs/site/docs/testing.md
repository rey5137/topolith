# Testing

`topolith-spring-test` (test scope) supplies helpers for choosing modes per test and for stubbing external modules.

## Choosing a mode per test

`@TopolithModuleMode` selects `INTERNAL`, `EXTERNAL` or `NONE` for one module in a test class. It is repeatable.

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

- The annotation overrides application configuration. If inlined properties, dynamic properties or a
  `@TestPropertySource` set the same `mode`, `remote.url` or `remote.service-id` to a different value, the context
  fails to load with an `IllegalStateException` naming the module, the key and both values.
- Selecting `external` clears `remote.service-id`, so a host that configures a service id still starts and the mock
  URL wins.
- Selecting `none` leaves no contributions of the module in the context.
- The `TopolithTestModes` builder offers the same selection in code.

## Stubbing an external module

`@TopolithMock(id = "payment")` injects a `TopolithMockServer` and points the module's client at it.

```mermaid
sequenceDiagram
    participant Test
    participant Mock as TopolithMockServer
    participant Client as Contract client
    Test->>Mock: stub("POST", "/charges", 200, body)
    Test->>Client: userApi.placeOrder(...)
    Client->>Mock: POST /payment/charges
    Mock-->>Client: 200 body
    Test->>Mock: requests()
```

- `stub(method, path, status, body)` registers a response; `requests()` returns the recorded calls.
- Stubs and recorded requests are cleared before and after each test method; `@BeforeEach` stubs survive, `@BeforeAll`
  stubs do not.
- `problem(method, path, type, message)` writes the same problem response a provider would write for a declared
  exception, so you can test the caller's exception mapping. `unavailable(method, path, downstreamModuleId)` writes the
  provider's 503. Pass the declared contract exception type.
- The mock serves, but does not record, the readiness version check.

## Boundary tests

Add `TopolithArchitecture.verify()` to a test in each module. See [Boundaries](boundaries.md).

## The same tests across topologies

The samples run the same payment test cases against the monolith, hybrid and `none` hosts. See [Samples](samples.md).
