# Project Agents.md for Red Hat Knowledge Base MCP Server

This Agents.md file provides comprehensive guidance for AI assistants and coding agents (like Gemini, Cursor, and others) to work with this codebase.

This repository contains the mcp-redhat-kb project,
a Java-based Model Context Protocol (MCP) server for searching the Red Hat Knowledge Base.
Built with Quarkus MCP Server, this enables AI assistants to search and retrieve Red Hat knowledge base articles using the Model Context Protocol (MCP).

## Project Structure and Repository Layout

- Java/Maven project structure:
  - `src/main/java/com/redhat/kb/` - main application source code.
    - `application/service/` - application services (KnowledgeBaseService).
    - `infrastructure/client/` - HTTP clients (KnowledgeBaseClient, RedHatAuthClient),
      their typed exceptions, SolrQuery (Hydra query escaping), and the per-request
      credential model (RedHatCredential, CredentialResolver).
    - `infrastructure/config/` - configuration classes (RedHatApiConfig).
    - `infrastructure/dto/` - data transfer objects for API responses.
    - `mcp/` - MCP tool definitions (KnowledgeBaseTools), plus ArticleFormatter and
      ContentSanitizer, which render article content for the model.
    - `KnowledgeBaseConstants.java` - shared constants.
  - `src/main/resources/` - application configuration files.
  - `src/test/java/` - test sources, mirroring the main package layout.
- `charts/mcp-redhat-kb/` - Helm chart for Kubernetes/OpenShift deployment.
- `evals/` - mcpchecker LLM evaluations (agent task success, not deterministic tests).
- `.github/` - GitHub-related configuration (Actions workflows, Dependabot).
- `.mvn/` - Maven wrapper configuration.
- `npm/` - Node packages that wrap the compiled binary for distribution through npmjs.com.
- `Dockerfile` - Container image description file.
- `pom.xml` - Maven project configuration and dependencies.

There is deliberately no `domain/` layer: this server is a thin proxy over the Hydra API
with no business invariants of its own, so mirror entities would be ceremony. Likewise,
the HTTP client is injected directly rather than behind a port interface — there is only
one implementation, and constructor injection already makes it testable.

## Credentials

Two identities must never be conflated:

- **Inbound**: the Keycloak bearer token says *who may call this server*. It is validated
  (signature, issuer, audience) and never leaves the process.
- **Outbound**: a Red Hat offline token says *whose entitlements read the Knowledge Base*.
  Callers supply their own in the `X-Red-Hat-Token` header; `CredentialResolver` picks it
  over the server's shared token, and `redhat.api.require-user-token` removes the shared
  fallback entirely.

Forwarding the inbound token upstream ("token passthrough") is forbidden by the
specification and by this design. Access tokens and Knowledge Base results are cached per
credential, keyed by `RedHatCredential.fingerprint()` — a SHA-256 prefix, so cache keys and
log lines never contain a token.

## Feature Development

Implement new functionality in the Java sources under `src/main/java/`.
The JavaScript (`npm/`) directory only wraps the compiled binary for distribution (npm).
Most changes will not require touching it unless the version or packaging needs to be updated.

### Adding New MCP Tools

The project uses Quarkus MCP Server annotations for defining tools:

- **Tool definitions** are annotated methods in `src/main/java/com/redhat/kb/mcp/KnowledgeBaseTools.java`.
- Use `@Tool` annotation to define new MCP tools.
- Use `@ToolArg` annotation to define tool arguments.

When adding a new tool:
1. Add a new method annotated with `@Tool` in `KnowledgeBaseTools.java` (or create a new tools class).
2. Define the tool's parameters with `@ToolArg` annotations.
3. Implement the tool's logic, using injected services for business operations.

## Building

Requires **JDK 25**. `.sdkmanrc` pins it, so run `sdk env` first — a machine-wide
`JAVA_HOME` pointing at an older JDK fails with "release version 25 not supported".

```bash
# Build the project (compile, test, package)
./mvnw package

# Build without tests
./mvnw package -DskipTests

# Build native executable (requires GraalVM)
./mvnw package -Pnative
```

The resulting executable JAR is in `target/`.

## Releasing

A `v*` tag drives four channels: GitHub Release (JAR + `checksums.txt`), npm, the container
image on ghcr.io, the Helm chart, and the MCP Registry.

Things that are easy to break, and are wired the way they are on purpose:

- **The tag is the only source of version truth.** `release.yaml` runs `versions:set` from
  it, so `pom.xml` is not bumped by hand and `application.properties` deliberately does not
  set `quarkus.application.version` — otherwise MCP `serverInfo` reports a stale version.
- **`checksums.txt` is mandatory.** `npm/cli.js` refuses to run a JAR whose digest is not
  listed, so JAR and launcher must ship in the same release.
- **Image tags carry no `v` prefix.** The chart uses `appVersion` as the image tag, so
  `release-helm.yaml` strips it. Leaving it in pulls a tag that never exists.
- **`npm/package.json` must keep `mcpName`** matching `server.json`; the MCP Registry
  verifies package ownership through it.

## Running

```bash
# Using npx (Node.js package runner)
npx -y mcp-redhat-kb@latest

# Using the MCP Inspector
./mvnw package -DskipTests
npx @modelcontextprotocol/inspector@latest java -jar target/quarkus-app/quarkus-run.jar

# Direct execution
java -jar target/quarkus-app/quarkus-run.jar

# Development mode with live reload
./mvnw quarkus:dev
```

## Tests

Run all tests with:

```bash
./mvnw test
```

Two layers, both deterministic and offline:

- **Unit tests** for the logic with real decisions: `SolrQueryTest` (Lucene escaping and
  article-ID validation), `ContentSanitizerTest` (HTML stripping, marker neutralization,
  truncation), `ArticleFormatterTest` (rendering and URL trust), `KnowledgeBaseArticleDtoTest`
  (the polymorphic `solution_*` fields Hydra returns).
- **Protocol tests** with McpAssured (`quarkus-mcp-server-test`): `KnowledgeBaseToolsProtocolTest`
  drives a real `@QuarkusTest` server over MCP and asserts the tool catalogue, the generated
  JSON Schema and the tool annotations. Treat it as a contract test — the schema and
  descriptions are the interface a model reasons about, so changing them should fail here.

`evals/` is a separate concern: mcpchecker runs an LLM agent against the server to judge
whether tasks succeed. It is non-deterministic and costs API credits, so run it before a
release rather than on every commit.

## Dependencies

Dependencies are managed in `pom.xml`. When adding new dependencies, ensure they are compatible with the Quarkus framework version in use.

## Coding Style

- Java 25+ (see `pom.xml`).
- Built with Quarkus framework.
- Follow standard Java conventions for naming, formatting, and error handling.
- Use CDI (Contexts and Dependency Injection) for service wiring.

## Distribution Methods

- An **npm** package is available at [npmjs.com](https://www.npmjs.com/package/mcp-redhat-kb).
  It wraps the platform-specific binary and provides a convenient way to run the server using `npx`.
- A **container image** is built and pushed to `ghcr.io/jeanlopezxyz/mcp-redhat-kb`.
- **Native binaries** for Linux, macOS, and Windows are available in the GitHub releases.
