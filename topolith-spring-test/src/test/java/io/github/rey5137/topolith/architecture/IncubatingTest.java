package io.github.rey5137.topolith.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.github.rey5137.topolith.core.Incubating;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncubatingTest {
    private static final String BASE = "io.github.rey5137.topolith";

    private static final ArchRule INCUBATING = classes().that()
            .resideInAPackage(BASE + "..")
            .and().areTopLevelClasses()
            .and().haveModifier(JavaModifier.PUBLIC)
            .and().doNotHaveSimpleName("package-info")
            .and(DescribedPredicate.not(JavaClass.Predicates.equivalentTo(Incubating.class)))
            .should().beAnnotatedWith(Incubating.class)
            .allowEmptyShould(false);

    private JavaClasses production() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(BASE);
    }

    @Test
    void ac_build_15_public_types_carry_incubating() {
        INCUBATING.check(production());
    }

    @Test
    void ac_build_15_fails_on_unannotated_public_fixture() {
        EvaluationResult result = INCUBATING.evaluate(
                new ClassFileImporter().importPackages(BASE + ".core.unannotated"));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains("UnannotatedPublic"));
    }
}
