# MCP Red Hat Knowledge Base

MCP Server for searching the Red Hat Knowledge Base (Hydra API).

## Requirements

- Java 21+
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
- **getSolution**: Get the full content of a Knowledge Base article

## Usage

```bash
# stdio mode (default)
npx mcp-redhat-kb

# SSE mode
npx mcp-redhat-kb --port 9081
```

## License

MIT
