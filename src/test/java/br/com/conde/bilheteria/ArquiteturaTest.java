package br.com.conde.bilheteria;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/** Regras de dependência entre camadas: o domínio não conhece Spring nem infra; a aplicação não conhece a infra. */
@AnalyzeClasses(packages = "br.com.conde.bilheteria", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

    @ArchTest
    static final ArchRule dominioNaoDependeDeFrameworkNemDeCamadasExternas = noClasses()
        .that().resideInAPackage("..dominio..")
        .should().dependOnClassesThat().resideInAnyPackage("..aplicacao..", "..infra..", "org.springframework..", "org.apache.kafka..", "com.fasterxml..");

    @ArchTest
    static final ArchRule aplicacaoNaoDependeDaInfra = noClasses()
        .that().resideInAPackage("..aplicacao..")
        .should().dependOnClassesThat().resideInAnyPackage("..infra..", "org.apache.kafka..", "org.springframework.kafka..", "org.springframework.data.redis..", "org.springframework.web..");

    @ArchTest
    static final ArchRule camadas = layeredArchitecture().consideringOnlyDependenciesInLayers()
        .layer("Dominio").definedBy("..dominio..")
        .layer("Aplicacao").definedBy("..aplicacao..")
        .layer("Infra").definedBy("..infra..")
        .whereLayer("Infra").mayNotBeAccessedByAnyLayer()
        .whereLayer("Aplicacao").mayOnlyBeAccessedByLayers("Infra")
        .whereLayer("Dominio").mayOnlyBeAccessedByLayers("Aplicacao", "Infra");

    @ArchTest
    static final ArchRule controladoresFicamNaWeb = classes()
        .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .should().resideInAnyPackage("..infra.web..", "..infra.seguranca..", "..infra.pagamento..");
}
