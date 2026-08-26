# Red Hat Knowledge Base MCP Server

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![npm version](https://img.shields.io/npm/v/mcp-redhat-kb)](https://www.npmjs.com/package/mcp-redhat-kb)
[![Java](https://img.shields.io/badge/Java-25+-orange)](https://adoptium.net/)
[![GitHub release](https://img.shields.io/github/v/release/jeanlopezxyz/mcp-redhat-kb)](https://github.com/jeanlopezxyz/mcp-redhat-kb/releases/latest)
[![Docker](https://img.shields.io/badge/ghcr.io-latest-blue?logo=docker)](https://github.com/jeanlopezxyz/mcp-redhat-kb/pkgs/container/mcp-redhat-kb)

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for searching the Red Hat Knowledge Base (Hydra API).

Built with [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html).

Ask your AI assistant about Red Hat products in plain language, and it searches the official
Knowledge Base for you — solutions to error messages, root causes for alerts, and how-to
documentation for RHEL, OpenShift and the rest of the portfolio.

## Quick start

**1. Get a Red Hat API token** at
[access.redhat.com/management/api](https://access.redhat.com/management/api) (a Red Hat
account is required; a free developer subscription works).

**2. Add the server to your MCP client.** For Claude Desktop, VS Code, Cursor or Windsurf,
this is the whole configuration:

```json
{
  "mcpServers": {
    "redhat-kb": {
      "command": "npx",
      "args": ["-y", "mcp-redhat-kb@latest"],
      "env": {
        "REDHAT_TOKEN": "your-token-here"
      }
    }
  }
}
```

**3. Ask a question.** No commands or tool names to memorise:

> "My OpenShift pods are in CrashLoopBackOff. Search the Red Hat Knowledge Base for what
> causes this."

The assistant searches, picks the relevant articles and reads them in full. Requires
[Java 25+](https://adoptium.net/).

## Table of Contents

- [Quick start](#quick-start)
- [Installation](#installation)
- [Example prompts](#example-prompts)
- [Tools](#tools)
- [Configuration](#configuration)
- [Authentication (Keycloak)](#authentication-keycloak)
- [Transport modes](#transport-modes)
- [Development](#development)
- [Kubernetes / OpenShift](#kubernetes--openshift-deployment)

---

## Requirements

- **Java 25+** - [Download](https://adoptium.net/)
- **Red Hat API Token** - [Generate here](https://access.redhat.com/management/api)

---

## Installation

### npx

```bash
npx -y mcp-redhat-kb@latest
```

### VS Code

```shell
code --add-mcp '{"name":"redhat-kb","command":"npx","args":["-y","mcp-redhat-kb@latest"],"env":{"REDHAT_TOKEN":"your-token-here"}}'
```

### Cursor

Add to `mcp.json`:

```json
{
  "mcpServers": {
    "redhat-kb": {
      "command": "npx",
      "args": ["-y", "mcp-redhat-kb@latest"],
      "env": {
        "REDHAT_TOKEN": "your-token-here"
      }
    }
  }
}
```

### Windsurf

Add to MCP configuration:

```json
{
  "mcpServers": {
    "redhat-kb": {
      "command": "npx",
      "args": ["-y", "mcp-redhat-kb@latest"],
      "env": {
        "REDHAT_TOKEN": "your-token-here"
      }
    }
  }
}
```

### Goose CLI

Add to `config.yaml`:

```yaml
extensions:
  redhat-kb:
    command: npx
    args:
      - -y
      - mcp-redhat-kb@latest
    env:
      REDHAT_TOKEN: your-token-here
```

### Docker

```bash
docker run -e REDHAT_TOKEN="your-token" ghcr.io/jeanlopezxyz/mcp-redhat-kb:latest
```

### HTTP Mode

Run as a standalone server:

```bash
REDHAT_TOKEN="your-token" npx mcp-redhat-kb --port 9081
```

Endpoint: `http://127.0.0.1:9081/mcp`

The server binds to `127.0.0.1` by default. It has no authentication of its own and holds
your Red Hat token, so only widen the binding (`--host 0.0.0.0`) when something else — a
reverse proxy that authenticates, a NetworkPolicy — controls who can reach the port.

---

## Configuration

### Command Line Options

| Option | Description |
|--------|-------------|
| `--port <PORT>` | Start in HTTP mode on the given port |
| `--host <HOST>` | Interface to bind in HTTP mode (default: `127.0.0.1`) |
| `--help` | Show help message |
| `--version` | Show version |

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `REDHAT_TOKEN` | Shared Red Hat offline token; fallback for callers who send none | Unless per-user |
| `MCP_REQUIRE_USER_TOKEN` | Require each caller's own token in `X-Red-Hat-Token`; HTTP only (default `false`) | Multi-user |
| `MCP_OIDC_ENABLED` | Require OAuth 2.1 bearer tokens (default `false`) | HTTP mode |
| `KEYCLOAK_URL` | Keycloak base URL, e.g. `https://sso.example.com` | If OIDC on |
| `KEYCLOAK_REALM` | Realm name (default `mcp`) | If OIDC on |
| `KEYCLOAK_CLIENT_ID` | Client ID of this server (default `mcp-redhat-kb`) | If OIDC on |
| `MCP_ALLOWED_ORIGINS` | Comma-separated CORS origins. Never `*` | HTTP mode |
| `MCP_RATE_LIMIT` | Calls per minute per caller, `0` disables (default `60`) | No |
| `MCP_LEGACY_SSE_POLICY` | `permit` re-opens the deprecated `/mcp/sse` (default `deny`) | No |

---

## Authentication (Keycloak)

In stdio mode the OS process boundary is the trust boundary and no authentication is needed.

**In HTTP mode, enable authentication.** This server holds a Red Hat offline token, so an
open port means anyone who reaches it can spend your subscription under your identity.

```bash
MCP_OIDC_ENABLED=true \
KEYCLOAK_URL=https://sso.example.com \
KEYCLOAK_REALM=mcp \
KEYCLOAK_CLIENT_ID=mcp-redhat-kb \
REDHAT_TOKEN=your-token \
npx mcp-redhat-kb --port 9081
```

The server then acts as an **OAuth 2.1 resource server** (never an authorization server):

- Validates the bearer token's signature, issuer and expiry against the realm's JWKS.
- **Validates the audience** (RFC 8707). A token minted for a different client in the same
  realm is rejected — without this check any realm token would grant access to your
  subscription.
- Publishes Protected Resource Metadata (RFC 9728) at
  `/.well-known/oauth-protected-resource` and returns a `WWW-Authenticate` challenge
  pointing at it, so compliant clients can discover where to authenticate.

### Keycloak setup

1. Create a client `mcp-redhat-kb` for this server.
2. Create a client scope `mcp-redhat-kb-aud` with an **Audience** mapper
   (*Included Client Audience* = `mcp-redhat-kb`, *Add to access token* = ON), and assign it
   as a Default scope to every client that will call this server.
3. Create the caller's client: public + Standard Flow + PKCE (`S256`) for interactive
   agents, or confidential with Service Accounts for machine-to-machine.
4. Verify with **Client scopes → Evaluate** that the access token carries
   `"aud": "mcp-redhat-kb"`.

### Credential model

Two separate identities, deliberately:

| | Identity | Purpose |
|---|---|---|
| **Inbound** | Keycloak token | *Who may use this server* |
| **Outbound** | Red Hat offline token | *Whose entitlements read the Knowledge Base* |

The inbound token is **never** forwarded to the Red Hat API. The specification forbids that
pattern ("token passthrough") because the upstream cannot tell who really called, breaking
rate limiting and audit trails.

#### Per-user Red Hat credentials

Each caller sends their own Red Hat offline token in the `X-Red-Hat-Token` header:

```http
POST /mcp
Authorization: Bearer <keycloak-token>     # who you are
X-Red-Hat-Token: <your-offline-token>      # whose subscription reads the KB
```

Every user generates their own token at
[access.redhat.com/management/api](https://access.redhat.com/management/api). The server
exchanges it against Red Hat SSO and caches the resulting access token **per credential**,
keyed by a SHA-256 fingerprint — one caller's token is never handed to another, and the
fingerprint (not the token) is what appears in logs and cache keys.

Search and article caches are partitioned by credential too, so a result fetched with one
subscription is never served to a different one.

Set `MCP_REQUIRE_USER_TOKEN=true` (the Helm default) to **require** it. The shared
`REDHAT_TOKEN` is then never used to serve a request:

| `MCP_REQUIRE_USER_TOKEN` | Caller sends a token | Caller sends none |
|---|---|---|
| `true` | Uses the caller's token | Refused, with instructions |
| `false` | Uses the caller's token | Falls back to `REDHAT_TOKEN` |

With it on, nobody can read subscription content through another account's entitlements, and
Red Hat's own audit trail attributes each call to the person who made it.

**The requirement applies to the HTTP transport only.** Over stdio the client launches this
process and passes `REDHAT_TOKEN` through its own environment, so that value is already the
caller's personal credential — there is no second user to isolate them from, and no header
to carry a different token. The setting is ignored there (with a warning at startup), so the
same configuration works for both transports.

### Rate limiting and audit

Calls are capped per caller — by authenticated identity where there is one, by credential
otherwise — so a runaway agent loop cannot exhaust the Red Hat quota for everyone. Tune with
`MCP_RATE_LIMIT` (default 60/minute, `0` disables).

Every invocation is logged under the `com.redhat.kb.audit` category:

```
tool=searchKnowledgeBase subject=alice@example.com source=10.1.2.3 credential=a3f9c1e2b7d40856 arg="CrashLoopBackOff"
```

Identifiers only: the credential appears as a SHA-256 fingerprint, never as a token, and the
query is truncated. Route that category to your log store to answer who searched for what.

---

## Tools

This server provides **4 tools**, all annotated as read-only and idempotent.

The first two read the Knowledge Base with your Red Hat credential. The last two use Red
Hat's public product APIs, so they need no token and spend no subscription.

#### `searchKnowledgeBase`
Search for solutions, documentation and articles. Works with error messages, log excerpts,
Prometheus/OpenShift alert names and general topics.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search keywords, error message, or alert name |
| `maxResults` | integer | No | Max results 1-25 (default: `10`) |
| `product` | string | No | e.g. `Red Hat OpenShift Container Platform`. Omit to search all products |
| `documentType` | string | No | `Solution`, `Documentation` or `Article` |

Use `documentType: Solution` when troubleshooting a failure, and `documentType: Documentation`
for how-to guides — these replace the former `troubleshootError`, `findSolutionForAlert` and
`searchDocumentation` tools, which were all the same search behind different names.

Results report how many articles matched in total, so the assistant can tell a precise
search from one that needs narrowing.

#### `getArticle`
Retrieve the full content of an article: environment, issue, root cause, diagnostic steps and
resolution. Long articles are truncated to keep context usage bounded.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `articleId` | string | Yes | Numeric article ID from search results |

#### `lookupCve`
Look up a CVE in Red Hat's security database: severity, CVSS v3 score, which products have a
released fix and which are still affected, plus any mitigation. Prefer it over a search
whenever a CVE identifier is involved — it returns structured data, not prose.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `cveId` | string | Yes | e.g. `CVE-2024-6387` (the `CVE-` prefix is optional) |

#### `getProductLifecycle`
Every released version of a product with its support phase, GA date and end of maintenance.
Answers end-of-life and upgrade-deadline questions.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `product` | string | Yes | Full product name, e.g. `Red Hat Enterprise Linux` |

---

## Example prompts

Talk to your assistant normally — it picks the tools and arguments itself.

**Troubleshooting an error**

> "My OpenShift pods keep hitting `ImagePullBackOff`. Search the Red Hat Knowledge Base for
> the common causes and how to fix them."

> "I'm seeing kernel panics on a RHEL 9 box. Find Red Hat solutions for kernel panic
> crashes."

> "Search for solutions to SELinux denials affecting my web server, and tell me how to
> configure the policy properly."

**Investigating an alert**

> "The `KubePodCrashLooping` alert is firing. What does the Red Hat Knowledge Base say
> about it?"

> "Find KB articles about troubleshooting degraded cluster operators in OpenShift."

**Planning work**

> "What are the known issues when upgrading from RHEL 8 to RHEL 9?"

> "Search for known failure points during OpenShift cluster installation."

> "Find Red Hat documentation on performance tuning for RHEL running database workloads."

**Checking a vulnerability**

> "Does CVE-2024-6387 affect RHEL 9, and is there a fix?"

> "What's the severity of CVE-2024-3094 and which products are still exposed?"

**Support windows**

> "When does OpenShift 4.14 reach end of maintenance?"

> "Which RHEL versions are still in full support?"

**Reading a specific article**

> "Get the full content of Red Hat solution 7129807."

Narrowing helps: naming the product (`RHEL 9`, `OpenShift 4.16`) or asking for
documentation rather than solutions changes what comes back.

---

## Transport modes

| Mode | Endpoint | Use case |
|------|----------|----------|
| **stdio** | — | Default for Claude Desktop, VS Code, Cursor, Windsurf |
| **Streamable HTTP** | `/mcp` | Standalone server shared by several clients |

Most users want stdio, which is what the Quick start configures: the client launches the
process and talks to it over standard input and output, with no ports involved.

Built against MCP specification **`2026-07-28`**, negotiated per request, so stateless
clients (`server/discover`) and older stateful ones (`initialize`) both work against the
same endpoint. Supported revisions: `2026-07-28`, `2025-11-25`, `2025-06-18`, `2025-03-26`,
`2024-11-05`.

> **HTTP+SSE is disabled.** That transport was deprecated in revision `2025-03-26`; leaving
> it listening would be an unmaintained second way into the same tools, so `/mcp/sse`
> returns `403`. If a client cannot speak Streamable HTTP yet, re-open it temporarily with
> `MCP_LEGACY_SSE_POLICY=permit`.

---

## Development

### Run in dev mode

```bash
export REDHAT_TOKEN="your-token"
./mvnw quarkus:dev
```

### Build

```bash
./mvnw package -DskipTests
```

### Test with MCP Inspector

```bash
# stdio mode
REDHAT_TOKEN="your-token" npx @modelcontextprotocol/inspector npx mcp-redhat-kb

# HTTP mode
REDHAT_TOKEN="your-token" npx mcp-redhat-kb --port 9081
# Then connect inspector to http://127.0.0.1:9081/mcp
```

---

## Kubernetes / OpenShift Deployment

### Container Image

The container image is available on GitHub Container Registry:

```
ghcr.io/jeanlopezxyz/mcp-redhat-kb:latest
```

### Helm Chart

Deploy using the included Helm chart:

```bash
# Create secret with Red Hat token
kubectl create secret generic mcp-redhat-kb-secrets \
  --namespace mcp-servers \
  --from-literal=REDHAT_TOKEN=your-token-here

# Deploy with Helm
helm upgrade --install mcp-redhat-kb ./charts/mcp-redhat-kb \
  --namespace mcp-servers \
  --create-namespace \
  --set openshift=true \
  --set redhat.existingSecret=mcp-redhat-kb-secrets
```

#### Helm Values

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.registry` | Container registry | `ghcr.io` |
| `image.repository` | Image repository | `jeanlopezxyz/mcp-redhat-kb` |
| `image.version` | Image tag or `sha256:` digest; empty uses the chart `appVersion` | `""` |
| `openshift` | Enable OpenShift Routes | `false` |
| `service.port` | Service port | `8080` |
| `redhat.requireUserToken` | Require each caller's own Red Hat token | `true` |
| `redhat.existingSecret` | Name of existing secret with REDHAT_TOKEN (fallback only) | `""` |
| `redhat.token` | Red Hat API token (if not using existingSecret) | `""` |
| `oidc.enabled` | Require OAuth 2.1 bearer tokens | `true` |
| `oidc.url` | Keycloak base URL (required when `oidc.enabled`) | `""` |
| `oidc.realm` | Keycloak realm | `mcp` |
| `oidc.clientId` | Client ID and expected token audience | `mcp-redhat-kb` |
| `allowedOrigins` | CORS origins; never `*` | `[]` |
| `ingress.enabled` | Publish the server. Requires `oidc.enabled` and `ingress.tls` | `false` |
| `networkPolicy.enabled` | Restrict ingress to `networkPolicy.allowedSources` | `true` |
| `networkPolicy.allowedSources` | NetworkPolicy `from` selectors; empty denies all | `[]` |

The chart refuses to render an Ingress without TLS, or with `oidc.enabled=false` — an
unauthenticated public endpoint would let anyone spend the Red Hat subscription.

#### Example with inline token (not recommended for production)

```bash
helm upgrade --install mcp-redhat-kb ./charts/mcp-redhat-kb \
  --namespace mcp-servers \
  --set openshift=true \
  --set redhat.token=your-token-here
```

---

## Related Projects

- [mcp-redhat-cases](https://github.com/jeanlopezxyz/mcp-redhat-cases) - MCP Server for Red Hat Support Cases

---

## License

[MIT](LICENSE) - Free to use, modify, and distribute.
