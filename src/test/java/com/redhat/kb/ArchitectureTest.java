package com.redhat.kb;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the structure AGENTS.md describes.
 *
 * <p>Those rules were prose, and prose does not fail a build: the sealed rendering pipeline
 * and the one-way dependency between the two layers held only while everyone remembered
 * them. Each rule here is a claim that document makes, so a change that contradicts it
 * fails at the point the change is made rather than at review.
 */
class ArchitectureTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.redhat.kb");
    }

    @Test
    @DisplayName("infrastructure never depends on the MCP layer")
    void infrastructureDoesNotDependOnMcp() {
        noClasses()
                .that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("..mcp..")
                .because("the two layers are mcp/ over infrastructure/, and the arrow points "
                        + "one way: infrastructure describes upstream APIs and cannot know how "
                        + "a model is spoken to")
                .check(productionClasses);
    }

    @Test
    @DisplayName("the rendering pipeline stays sealed inside mcp/")
    void renderingPipelineStaysPackagePrivate() {
        classes()
                .that().haveSimpleName("ContentSanitizer")
                .or().haveSimpleName("UntrustedFence")
                .or().haveSimpleName("ArticleFormatter")
                .or().haveSimpleName("SecurityFormatter")
                .or().haveSimpleName("ToolErrors")
                .or().haveSimpleName("ToolGuards")
                .should().notBePublic()
                .because("nothing outside mcp/ may render remote content without going "
                        + "through sanitization; making one of these public opens a path "
                        + "around the fence")
                .check(productionClasses);
    }

    @Test
    @DisplayName("only the Knowledge Base client can build a Hydra query")
    void solrQueryStaysPackagePrivate() {
        classes()
                .that().haveSimpleName("SolrQuery")
                .should().notBePublic()
                .because("a caller who could assemble a query without SolrQuery would "
                        + "assemble one without the Lucene escaping")
                .check(productionClasses);
    }

    @Test
    @DisplayName("upstream text never reaches the model unsanitized")
    void toolsDoNotTouchUpstreamModelsDirectly() {
        noClasses()
                .that().haveSimpleNameEndingWith("Tools")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure.model..")
                .andShould().dependOnClassesThat().haveSimpleName("KnowledgeBaseArticle")
                .because("infrastructure/model holds raw upstream text; a tool renders "
                        + "mcp/model, which is what has already been through ContentSanitizer")
                .check(productionClasses);
    }

    @Test
    @DisplayName("no layer below the tools reaches for the MCP server API")
    void onlyToolsDependOnTheMcpServerApi() {
        noClasses()
                .that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("io.quarkiverse.mcp..")
                .because("the protocol is the tools' concern; a client that knew about "
                        + "ToolResponse could not be reused outside an MCP server")
                .check(productionClasses);
    }

    @Test
    @DisplayName("nothing logs through standard output")
    void noStandardStreamLogging() {
        noClasses()
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("stdio is a transport here: anything written to System.out is "
                        + "framed as a protocol message and corrupts the session")
                .check(productionClasses);
    }
}
