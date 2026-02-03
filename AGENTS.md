# Project Agents.md for Red Hat Knowledge Base MCP Server

This Agents.md file provides comprehensive guidance for AI assistants and coding agents (like Gemini, Cursor, and others) to work with this codebase.

This repository contains the mcp-redhat-kb project,
a Java-based Model Context Protocol (MCP) server for searching the Red Hat Knowledge Base.
Built with Quarkus MCP Server, this enables AI assistants to search and retrieve Red Hat knowledge base articles using the Model Context Protocol (MCP).

## Project Structure and Repository Layout

- Java/Maven project structure:
  - `src/main/java/com/redhat/kb/` - main application source code.
    - `application/service/` - application services (KnowledgeBaseService).
    - `infrastructure/client/` - HTTP clients (KnowledgeBaseClient, RedHatAuthClient).
    - `infrastructure/config/` - configuration classes (RedHatApiConfig).
    - `infrastructure/dto/` - data transfer objects for API responses.
    - `mcp/` - MCP tool definitions (KnowledgeBaseTools).
    - `util/` - utility classes.
    - `KnowledgeBaseConstants.java` - shared constants.
  - `src/main/resources/` - application configuration files.
  - `src/test/` - test sources.
- `.github/` - GitHub-related configuration (Actions workflows, Dependabot).
- `.mvn/` - Maven wrapper configuration.
- `npm/` - Node packages that wrap the compiled binary for distribution through npmjs.com.
- `Dockerfile` - Container image description file.
- `pom.xml` - Maven project configuration and dependencies.

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

Use the Maven wrapper:

```bash
# Build the project (compile, test, package)
./mvnw package

# Build without tests
./mvnw package -DskipTests

# Build native executable (requires GraalVM)
./mvnw package -Pnative
```

The resulting executable JAR is in `target/`.

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

## Dependencies

Dependencies are managed in `pom.xml`. When adding new dependencies, ensure they are compatible with the Quarkus framework version in use.

## Coding Style

- Java 21+ (see `pom.xml`).
- Built with Quarkus framework.
- Follow standard Java conventions for naming, formatting, and error handling.
- Use CDI (Contexts and Dependency Injection) for service wiring.

## Distribution Methods

- An **npm** package is available at [npmjs.com](https://www.npmjs.com/package/mcp-redhat-kb).
  It wraps the platform-specific binary and provides a convenient way to run the server using `npx`.
- A **container image** is built and pushed to `ghcr.io/jeanlopezxyz/mcp-redhat-kb`.
- **Native binaries** for Linux, macOS, and Windows are available in the GitHub releases.
