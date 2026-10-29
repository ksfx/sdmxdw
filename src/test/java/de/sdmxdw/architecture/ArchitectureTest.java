package de.sdmxdw.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.sdmxdw");

  @Test
  void controllersDoNotAccessRepositoriesDirectly() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat(
                new com.tngtech.archunit.base.DescribedPredicate<>("are our own repositories") {
                  @Override
                  public boolean test(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                    return javaClass.getSimpleName().endsWith("Repository")
                        && javaClass.getPackageName().startsWith("de.sdmxdw");
                  }
                });
    rule.check(CLASSES);
  }

  @Test
  void passwordHashingOnlyInSecurityPackage() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("de.sdmxdw.security..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.security.crypto.password.PasswordEncoder");
    rule.check(CLASSES);
  }
}
