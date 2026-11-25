package jakarta.persistence;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Name-only stand-in so the contract-type rule can be tested without a JPA provider. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Entity {
}
