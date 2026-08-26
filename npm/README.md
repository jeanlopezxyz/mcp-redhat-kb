# MCP Red Hat Knowledge Base

MCP Server for searching the Red Hat Knowledge Base (Hydra API).

## Requirements

- Java 25+
- Red Hat API Token

## Installation

```bash
npx mcp-redhat-kb
```

## Configuration

### Environment Variable

Set your Red Hat API token:

```bash
export REDHAT_TOKEN="your-offline-token"
```

Get your token at: https://access.redhat.com/management/api

### MCP Client Configuration

Add to your MCP client configuration (VS Code, Cursor, Windsurf, etc.):

```json
{
  "mcpServers": {
    "redhat-kb": {
      "command": "npx",
      "args": ["-y", "mcp-redhat-kb@latest"],
      "env": {
        "REDHAT_TOKEN": "your-token"
      }
    }
  }
}
```

## Available Tools

- **searchKnowledgeBase**: Search Red Hat Knowledge Base for solutions and articles
- **getArticle**: Get the full content of a Knowledge Base article
- **lookupCve**: Look up a CVE — severity, CVSS score, affected products and fixes
- **getProductLifecycle**: Support phases and end-of-life dates for a product

## Usage

```bash
# stdio mode (default)
npx mcp-redhat-kb

# HTTP mode - Streamable HTTP endpoint at http://127.0.0.1:9081/mcp
npx mcp-redhat-kb --port 9081
```

In HTTP mode the server binds to `127.0.0.1` and has no authentication of its own. Before
exposing it, enable OAuth 2.1 with `MCP_OIDC_ENABLED=true` and the `KEYCLOAK_*` variables —
otherwise anyone who reaches the port can use your Red Hat subscription.

## License

MIT
