# Red Hat Knowledge Base MCP Server

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![npm version](https://img.shields.io/npm/v/mcp-redhat-kb)](https://www.npmjs.com/package/mcp-redhat-kb)
[![Java](https://img.shields.io/badge/Java-21+-orange)](https://adoptium.net/)
[![GitHub release](https://img.shields.io/github/v/release/jeanlopezxyz/mcp-redhat-kb)](https://github.com/jeanlopezxyz/mcp-redhat-kb/releases/latest)
[![Docker](https://img.shields.io/badge/ghcr.io-latest-blue?logo=docker)](https://github.com/jeanlopezxyz/mcp-redhat-kb/pkgs/container/mcp-redhat-kb)

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server for searching the Red Hat Knowledge Base (Hydra API).

Built with [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html).

## Transport Modes

This server supports two MCP transport modes:

| Mode | Description | Use Case |
|------|-------------|----------|
| **stdio** | Standard input/output communication | Default for Claude Desktop, Claude Code, Cursor, VS Code |
| **SSE** | Server-Sent Events over HTTP | Standalone server, web integrations, multiple clients |

## Table of Contents

- [Transport Modes](#transport-modes)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Tools](#tools)
- [Examples](#examples)
- [Development](#development)
- [Related Projects](#related-projects)

---

## Requirements

- **Java 21+** - [Download](https://adoptium.net/)
- **Red Hat API Token** - [Generate here](https://access.redhat.com/management/api)

---

## Installation

### Quick Install (Claude Code CLI)

```bash
claude mcp add redhat-kb -e REDHAT_TOKEN="your-token-here" -- npx -y mcp-redhat-kb@latest
```

### Claude Code

Add to `~/.claude/settings.json`:

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

### Claude Desktop

Add to `claude_desktop_config.json`:

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

### SSE Mode

Run as standalone server:

```bash
REDHAT_TOKEN="your-token" npx mcp-redhat-kb --port 9081
```

Endpoint: `http://localhost:9081/mcp/sse`

---

## Configuration

### Command Line Options

| Option | Description |
|--------|-------------|
| `--port <PORT>` | Start in SSE mode on specified port |
| `--help` | Show help message |
| `--version` | Show version |

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `REDHAT_TOKEN` | Red Hat API offline token | Yes |

---

## Tools

This server provides **5 tools** for searching Red Hat Knowledge Base:

### Search

#### `searchKnowledgeBase`
Search Red Hat Knowledge Base for solutions and articles. Use error messages or technical keywords.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search keywords |
| `maxResults` | integer | No | Max results 1-50 (default: `10`) |
| `product` | string | No | Filter: `OpenShift`, `RHEL` |
| `documentType` | string | No | Type: `Solution`, `Documentation`, `Article` |

#### `getSolution`
Get full content of a Knowledge Base article. Use article ID from search results.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `solutionId` | string | Yes | Article ID (numeric) |

---

### Troubleshooting

#### `troubleshootError`
Search for solutions to an error message. Optimized for troubleshooting.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `errorMessage` | string | Yes | Error message to search |
| `product` | string | No | Product (default: `OpenShift`) |

#### `findSolutionForAlert`
Find KB solutions for a Prometheus/OpenShift alert name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `alertName` | string | Yes | Alert name (e.g., `KubePodCrashLooping`) |
| `product` | string | No | Product (default: `OpenShift`) |

#### `searchDocumentation`
Search Red Hat documentation for how-to guides and best practices.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `topic` | string | Yes | Topic to search |
| `product` | string | No | Product (default: `OpenShift`) |

---

## Example Prompts

Use natural language to search the Knowledge Base:

```
"Search knowledge base for CrashLoopBackOff"
"Find solutions for etcd timeout errors"
"Search KB for oauth authentication error in OpenShift"
"Get the full solution for article 7129807"
"Find documentation about OpenShift networking"
"Search for RHEL storage issues"
```

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

# SSE mode
REDHAT_TOKEN="your-token" npx mcp-redhat-kb --port 9081
# Then connect inspector to http://localhost:9081/mcp/sse
```

---

## Related Projects

- [mcp-redhat-cases](https://github.com/jeanlopezxyz/mcp-redhat-cases) - MCP Server for Red Hat Support Cases

---

## License

[MIT](LICENSE) - Free to use, modify, and distribute.
