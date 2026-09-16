package com.deepon.smartdocs;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Structural rules the design doc calls out by name (section 6.2, 6.5):
 * services stay free of servlet types, and {@code DocumentRepository} never
 * grows an unscoped finder that would let a fetch skip the owner check.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.deepon.smartdocs");
    }

    @Test
    void documentRepositoryExposesNoFindByIdWithoutOwnerId() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat(simpleName("DocumentRepository"))
                .should().haveName("findById")
                .because("every single-document lookup must be owner-scoped — see findByIdAndOwnerIdAndDeletedAtIsNull");
        rule.check(classes);
    }

    @Test
    void servicesDoNotDependOnServletTypes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("services must not know about HttpServletRequest or cookies — that's the controller/filter layer's job");
        rule.check(classes);
    }

    @Test
    void controllersDoNotDependOnRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("controllers stay thin — they delegate to a service, never touch persistence directly");
        rule.check(classes);
    }

    @Test
    void entitiesDoNotDependOnRepositoriesOrServices() {
        ArchRule rule = classes()
                .that().resideInAPackage("..entity..")
                .should().onlyDependOnClassesThat()
                .resideOutsideOfPackages("..repository..", "..service..", "..controller..");
        rule.check(classes);
    }

    private static final Pattern SELECT_STAR = Pattern.compile("select\\s+\\*", Pattern.CASE_INSENSITIVE);

    @Test
    void repositoryQueriesNeverSelectStar() {
        ArchRule rule = methods()
                .that().areAnnotatedWith(Query.class)
                .should(new ArchCondition<JavaMethod>("not use SELECT *") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        String jpql = method.getAnnotationOfType(Query.class).value();
                        boolean selectStar = SELECT_STAR.matcher(jpql).find();
                        events.add(new SimpleConditionEvent(method, !selectStar,
                                method.getFullName() + (selectStar ? " uses SELECT *" : " does not use SELECT *")));
                    }
                })
                .because("SELECT * silently breaks when a column is added and hides exactly which columns a query needs");
        rule.check(classes);
    }
}
