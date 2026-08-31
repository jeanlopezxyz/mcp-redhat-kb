# Project Agents.md for Red Hat Knowledge Base MCP Server

This Agents.md file provides comprehensive guidance for AI assistants and coding agents (like Gemini, Cursor, and others) to work with this codebase.

This repository contains the mcp-redhat-kb project,
a Java-based Model Context Protocol (MCP) server for searching the Red Hat Knowledge Base.
Built with Quarkus MCP Server, this enables AI assistants to search and retrieve Red Hat knowledge base articles using the Model Context Protocol (MCP).

## Project Structure and Repository Layout

- Java/Maven project structure:
  - `src/main/java/com/redhat/kb/` - main application source code.
    - `infrastructure/client/` - one class per upstream API plus SolrQuery (Hydra query
      escaping). SolrQuery stays package-private: only KnowledgeBaseClient builds Hydra
      queries, so no caller can assemble one without the Lucene escaping.
    - `infrastructure/model/` - what the upstream APIs return.
    - `infrastructure/credential/` - everything about *who* reads the Knowledge Base:
      RedHatCredential, CredentialResolver, RedHatAuthClient and their two failures.
    - `infrastructure/http/` - transport shared by every client: BoundedJsonHttp and the
      KnowledgeBaseException it raises. It knows nothing about any particular API.
    - `infrastructure/config/` - configuration classes (RedHatApiConfig).
    - `mcp/` - MCP tool definitions (KnowledgeBaseTools for the credentialed Knowledge
      Base, SecurityTools for Red Hat's public CVE and life cycle APIs), plus the
      formatters and ContentSanitizer that render content for the model. The checks and
      failures every tool shares live in `ToolGuards` (argument validation, rate limiting)
      and `ToolErrors` (exception to client error): both tool classes previously kept
      their own copy of each, and the copies drifted.

  A tool that declares `outputSchema` **must** return structured content on every success,
  including empty-result branches: the specification's obligation on servers is
  unconditional — *"If an output schema is provided: Servers MUST provide structured
  results that conform to this schema"*. Validation is only a SHOULD on clients, but
  several SDKs enforce it by erroring, and then the model never sees the message at all.
  `StructuredContentContractTest` guards this.
    - `KnowledgeBaseConstants.java` - shared constants.
  - `src/main/resources/` - application configuration files.
  - `src/test/java/` - test sources, mirroring the main package layout.
- `charts/mcp-redhat-kb/` - Helm chart for Kubernetes/OpenShift deployment.
- `evals/` - mcpchecker LLM evaluations (agent task success, not deterministic tests).
- `.github/` - GitHub-related configuration (Actions workflows, Dependabot).
- `.mvn/` - Maven wrapper configuration.
- `npm/` - Node packages that wrap the compiled binary for distribution through npmjs.com.
- `scripts/` - `mcp-inspect.sh`, which drives the packaged JAR through the official MCP
  Inspector, and `mcp-conformance.sh`, which runs the specification's conformance suite
  against it with `conformance-expected-failures.yaml` as its baseline (see Tests).
- `Dockerfile` - Container image description file.
- `pom.xml` - Maven project configuration and dependencies.
- `server.json` - the MCP Registry entry; its `name` must match `mcpName` in
  `npm/package.json` (see Releasing).
- `smithery.yaml` - listing metadata for the Smithery MCP directory.
- `.sdkmanrc` - pins the JDK the build expects (see Building).

There are deliberately two layers, `mcp/` over `infrastructure/`, and no `domain/` or
`application/` between them: this server is a thin proxy over the Hydra API with no
business invariants of its own. An `application/service/` package did exist and was removed
once it became clear it only forwarded calls — one of the two tool classes already bypassed
it. Likewise, the HTTP clients are injected directly rather than behind port interfaces.

Data types live in a `model/` package named after what they are, with no `Dto` suffix.
Neither the MCP specification nor the Quarkus extension prescribes a layout — Quarkus
leaves it to each team on purpose
([quarkusio/quarkus#39910](https://github.com/quarkusio/quarkus/discussions/39910)) — and
the official samples keep records unsuffixed
([quarkiverse/quarkus-mcp-servers](https://github.com/quarkiverse/quarkus-mcp-servers)),
so this follows the ecosystem instead of inventing a convention. The two `model/` packages
are kept apart on purpose: `infrastructure/model/` holds raw upstream text that has not been
through `ContentSanitizer`, `mcp/model/` holds what is already safe to show the model.

Within `mcp/`, the formatters, `ContentSanitizer` and `UntrustedFence` are package-private
on purpose. That keeps the pipeline sealed — nothing outside the package can render remote
content without going through sanitization — so resist splitting `mcp/` into sub-packages:
it would force those types public and trade a real security boundary for smaller folders.

## Untrusted content

Everything this server returns is third-party text. Three pieces handle that, and they are
the reason `mcp/` looks the way it does.

**`ContentSanitizer`** strips HTML and breaks up the structural markers this server itself
emits — `===`, `---`, `<<<` become `= ==`, `- --`, `< <<`. The run is broken from the inside
rather than shifted right with a space: a leading space is undone by the final `strip()`,
which used to let a marker that opens a field survive intact.

**`UntrustedFence`** wraps upstream text in markers carrying an 80-bit nonce generated per
render. With a fixed marker, content reproducing the closing string convinces the model the
block ended, and everything after it reads as the server's own voice.

**The formatters** keep our own text outside the fence and everything upstream inside it.
Getting this backwards is the easy mistake: a conclusion inside the fence is one the model
was told to ignore.

Why it is built this way:

- The specification makes it an obligation without saying how: *"Servers MUST … Sanitize
  tool outputs"* —
  [MCP 2026-07-28, Tools § Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/server/tools#security-considerations).
- The randomized delimiter is a published technique: the *delimiting* mode of **Spotlighting**
  (Hines et al., Microsoft Research, 2024), which reports attack success dropping from >50%
  to <2% —
  [paper](https://www.microsoft.com/en-us/research/publication/defending-against-indirect-prompt-injection-attacks-with-spotlighting/),
  [shipped in Azure AI Foundry](https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/better-detecting-cross-prompt-injection-attacks-introducing-spotlighting-in-azur/4458404),
  [recommended for MCP](https://developer.microsoft.com/blog/protecting-against-indirect-injection-attacks-mcp/).
  Another MCP server treats the same problem as load-bearing, though it solves it
  differently — Arcjet separates trusted from untrusted *fields* rather than fencing text,
  on the rule that "trusted guidance must never contain untrusted text":
  [Arcjet](https://blog.arcjet.com/how-we-defend-mcp-tool-outputs-from-prompt-injection/).
- **It is best-effort, not a hard control.** Adaptive attackers defeat spotlighting, and
  Anthropic states prompt injection is
  [far from solved](https://www.anthropic.com/research/prompt-injection-defenses). The
  durable defences stay with the host. Do not present this as a guarantee.
- The known cost is fidelity: Red Hat's AsciiDoc uses `====` for blocks, and sanitizing
  rewrites it. Accepted deliberately — the nonce already makes the fence unforgeable.

Both channels are sanitized, not just the prose: `structuredContent` is built from the same
cleaned values. A structured payload carrying raw upstream text is a bypass of everything
above.

## OWASP coverage

Audited against the [OWASP MCP Top 10](https://owasp.org/www-project-mcp-top-10/) (beta),
the [Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/) and
[MCP Tool Poisoning](https://owasp.org/www-community/attacks/MCP_Tool_Poisoning).

| Control | State | Where it is enforced |
|---|---|---|
| MCP01 Token mismanagement | Met | `RedHatCredential` — a SHA-256 fingerprint is the only identifier reaching logs and cache keys |
| MCP02 Privilege escalation | Met | Every tool is read-only and says so; audience validation (RFC 8707) |
| MCP03 Tool poisoning | Met, best-effort | `ContentSanitizer` + `UntrustedFence`; URLs accepted only over https under `redhat.com` or a subdomain of it |
| MCP04 Supply chain | Met | Actions pinned by SHA, Trivy gates the push, signed provenance, CycloneDX SBOM (`-Psbom`) |
| MCP05 Command injection | Met | No process execution; Lucene escaping, anchored id patterns, everything URL-encoded |
| MCP06 Intent flow subversion | Met, best-effort | Our text outside the fence, upstream text inside |
| MCP07 Authentication | Met | OAuth 2.1 resource server; `/q/*` and legacy SSE denied; `StartupConfigCheck` escalates when bound wider without auth |
| MCP08 Audit and telemetry | Met | `ToolAuditLog` records tool, subject, address and fingerprint; tokens never |
| MCP09 Shadow servers | N/A | A deployer-side control |
| MCP10 Context over-sharing | Met | Every cache partitioned by credential, guarded by contract tests |
| LLM02 Sensitive disclosure | Met | Non-2xx bodies are never read; only typed exceptions reach the client |
| LLM09 Misinformation | Met | `subscriberOnly` separates withheld content from absent content |
| LLM10 Unbounded consumption | Met | Per-caller rate limiting, streaming size caps, per-section budgets, timeouts |

One residual risk is accepted rather than fixed:

- A direct JWT's `exp` is trusted without signature verification — see `MAX_CACHE_DURATION`
  in `RedHatAuthClient` for why verifying it would add nothing, and why the reuse window is
  capped at five minutes instead.

## Credentials

Two identities must never be conflated:

- **Inbound**: the Keycloak bearer token says *who may call this server*. It is validated
  (signature, issuer, audience) and never leaves the process.
- **Outbound**: a Red Hat offline token says *whose entitlements read the Knowledge Base*.
  Callers supply their own in the `X-Red-Hat-Token` header; `CredentialResolver` picks it
  over the server's shared token, and `redhat.api.require-user-token` removes the shared
  fallback entirely.

A token also decides *how much* of an article comes back, not just whether the call
succeeds. Verified against the live API over 21 articles and 8 document kinds: search,
`title`, `abstract` and `issue` are served to any authenticated caller, while
`solution_rootcause`, `solution_resolution` and `solution_environment` come back as the
sentinel string `subscriber_only` in every case the subscription does not cover — with no
exceptions, including document kinds that look public. `KnowledgeBaseArticle` maps those
to `null` and records `isSubscriberOnly()`; keep that flag, because without it "withheld"
and "this article has no such section" are the same `null`, and a model that reads a
detailed problem with no resolution concludes there is no fix. `ArticleFormatter` therefore
emits its notice outside the untrusted-content fence — it is our text, not upstream's.

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

`package` produces `target/quarkus-app/quarkus-run.jar`. The single-file uber-JAR the
release publishes is a separate packaging (`-Dquarkus.package.jar.type=uber-jar`), built by
`release.yaml` rather than by a plain `package`.

## Releasing

A `v*` tag drives five channels: GitHub Release (JAR + `checksums.txt`), npm, the container
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

Five layers. The first three are deterministic and offline; the fourth needs Docker, and
the conformance suite needs a network (it is fetched with `npx`) but no credentials.

Which tool proves what, since several are easy to confuse:

| Tool | Answers |
|---|---|
| **McpAssured** | does the protocol surface match what a model reasons about? |
| **conformance** | does the wire format match the specification's own schemas? |
| **json-schema-validator** | does `structuredContent` actually conform to the published `outputSchema`? |
| **Keycloak (Testcontainers)** | is a token for another audience really refused? |
| **WireMock** | what happens when upstream stalls or cuts the body mid-transfer? |
| **ArchUnit** | do the rules in this document still hold? |
| **PIT** (`-Pmutation`) | would a test notice if the code changed? |

ArchUnit, WireMock and PIT are ordinary Java tooling, not MCP-specific — they are here because
they catch things this server gets wrong, not because the protocol asks for them.


- **Unit tests** for the logic with real decisions: `SolrQueryTest` (Lucene escaping and
  article-ID validation), `ContentSanitizerTest` (HTML stripping, marker neutralization,
  truncation), `ArticleFormatterTest` (rendering and URL trust), `KnowledgeBaseArticleTest`
  (the polymorphic `solution_*` fields Hydra returns), `ToolAuditLogTest` (the argument
  preview, which is what keeps the MCP08 promise that no token reaches the log).
- **Protocol tests** with McpAssured (`quarkus-mcp-server-test`): `KnowledgeBaseToolsProtocolTest`
  drives a real `@QuarkusTest` server over MCP and asserts the tool catalogue, the generated
  JSON Schema and the tool annotations. Treat it as a contract test — the schema and
  descriptions are the interface a model reasons about, so changing them should fail here.

- **Policy tests**, also `@QuarkusTest`, each pinning a security decision under the profile
  that makes it observable — they are why the OWASP table above can claim what it claims:
  `McpAuthenticationTest` (anonymous calls refused, `/q/*` and metadata policy),
  `LegacySseDisabledTest` (the deprecated SSE transport stays shut while Streamable HTTP
  works), `UserTokenRequiredTest` (`redhat.api.require-user-token` removes the shared
  fallback), `CachePartitioningTest` and `CacheKeyContractTest` (no cache entry is ever
  shared across credentials), `StartupConfigCheckTest` (the warn-to-error escalation, where
  the *level* is the assertion: the same settings are routine on loopback and dangerous once
  the port is published). The credential and transport units sit here too:
  `RedHatCredentialTest`, `CredentialResolverTest`, `RedHatAuthClientTest`,
  `RateLimiterTest`, `UntrustedFenceTest`, `SecurityFormatterTest`, `RedHatApiConfigTest`
  and the three `*HttpTest` clients, which run against `StubApiServer` rather than the
  network.

  Fixtures shared by more than one class live in `com.redhat.kb.testing`
  (`TestJwt.unsigned(...)`); `StubApiServer` stays package-private in
  `infrastructure.client`, alongside its three callers.

- **Conformance and structure tests**, which pin obligations rather than behaviour:

  `OutputSchemaConformanceTest` validates each tool's `structuredContent` against the
  `outputSchema` that same tool publishes in `tools/list`, using a JSON Schema 2020-12
  validator. Asserting the payload is merely present does not discharge *"Servers MUST
  provide structured results that conform to this schema"* — the empty-result branches are
  where that breaks, so they are the ones covered.

  `ToolsListInvarianceTest` opens two independent connections and requires the same tools in
  the same order: the catalogue *"MUST NOT vary per-connection"* and *"SHOULD"* be ordered
  deterministically.

  `ArchitectureTest` (ArchUnit) turns this document into build failures — the one-way
  dependency between the two layers, the package-private rendering pipeline, and that
  nothing writes to `System.out`, which under stdio would corrupt the protocol stream.

  `BoundedJsonHttpFaultTest` (WireMock) covers what `StubApiServer` cannot express: an
  upstream that accepts a request and stalls, one that cuts the body mid-transfer, one that
  exceeds the size cap, and the guarantee that an error body is never downloaded.

- **A test that needs Docker**: `AudienceValidationTest` starts a real Keycloak and proves
  that a token signed by the right realm, for the right user, unexpired, is still refused
  when its audience is another service. Every other `@QuarkusTest` here runs with
  `quarkus.oidc.enabled=false`, so before this the MCP07 control was documented but never
  executed. It provisions its own audience mappers through the admin API, because the
  default realm puts none on any client. Skip it with `-Dtest='!AudienceValidationTest'`
  where no container runtime is available.

- **Mutation testing**, `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage
  -Pmutation`. Coverage says a line ran; this says an assertion would have caught it
  changing. It is what surfaced that `ToolGuards` — the input validation every tool depends
  on — had no test of its own: 86% of mutations killed today, from 76% before that gap was
  closed. Report in `target/pit-reports/`. Not part of the normal build.

- **Manual protocol checks** with the official MCP Inspector, via `scripts/mcp-inspect.sh`:

  ```bash
  ./mvnw package -DskipTests
  scripts/mcp-inspect.sh                     # tools/list
  scripts/mcp-inspect.sh call searchKnowledgeBase query="CrashLoopBackOff"
  JAR=/path/to/released.jar scripts/mcp-inspect.sh   # check a published artefact
  ```

  Worth running against the packaged JAR before a release: it starts the real binary, so it
  surfaces problems the test suite cannot, such as configuration keys the runtime ignores.
  Two details the script handles, both of which cost time to discover: the Inspector
  appends its own flags to the server command (so `java` must be wrapped, or it prints its
  usage and the connection dies), and it exits with code 5 when a tool returns
  `isError: true` — that is the tool reporting, not the Inspector failing.

- **Official conformance suite**, [modelcontextprotocol/conformance](https://github.com/modelcontextprotocol/conformance),
  via `scripts/mcp-conformance.sh`. It checks the wire format against the specification's
  own schemas. McpAssured proves *these tools* behave; this proves *the protocol* does,
  including the parts the extension generates rather than this code — which is what makes
  it worth having when the SDK is upgraded. It runs in `build.yaml`.

  ```bash
  ./mvnw package -DskipTests
  scripts/mcp-conformance.sh                  # active suite against the baseline
  scripts/mcp-conformance.sh --scenario tools-list
  JAR=/path/to/released.jar scripts/mcp-conformance.sh
  ```

  The script starts the packaged JAR on 127.0.0.1:9099, polls `initialize` until it
  answers, runs the suite and shuts the server down. It overrides two settings the suite
  cannot satisfy — `quarkus.oidc.enabled=false` and the `/mcp` policy set to `permit` —
  because the suite is an unauthenticated client with no notion of Keycloak. Those are
  command-line overrides only: the shipped defaults stay authenticated, and
  `McpAuthenticationTest` is what pins that.

  Nine scenarios pass and twenty-two fail, and the failures are the reason the run needs
  `scripts/conformance-expected-failures.yaml`. The suite is written against the
  specification's reference server, which publishes `test_*` tools, `test://` resources and
  `test_*` prompts for it to drive; this server has its own tools and declares neither
  `resources` nor `prompts`, so those scenarios cannot apply. Verified case by case: each
  one fails with the *correct* JSON-RPC error (`-32602 Invalid tool name`, `-32002 Resource
  not found`), which is the right answer to a request for something absent.

  The baseline is what makes this a signal rather than noise — 22 red lines nobody reads is
  the usual reason this tool gets wired up and then ignored. It fails the build in **both**
  directions: a scenario failing outside the list, and a listed scenario that starts
  passing (a stale entry). Both were confirmed by running it against a deliberately wrong
  baseline. So the eight scenarios deliberately kept out of the list — `server-initialize`,
  `ping`, `logging-set-level`, `tools-list`, `server-sse-multiple-streams`,
  `resources-list`, `prompts-list`, `dns-rebinding-protection` — must always pass, and an
  SDK upgrade that breaks the handshake or the transport says so.

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
  `npm/cli.js` downloads the released JAR, verifies it against `checksums.txt` and runs it,
  so a JRE (25+) must be present on the machine.
- A **container image** is built and pushed to `ghcr.io/jeanlopezxyz/mcp-redhat-kb`.
- The **GitHub Release** carries the executable uber-JAR plus `checksums.txt`.

Native executables are **not** published today: `release.yaml` builds the uber-JAR only.
`./mvnw package -Pnative` works locally (see the `native` profile in `pom.xml`), and
wiring it into the release would drop the JRE requirement above — worth doing, but it is
a change to the release pipeline, not a documentation detail.
