# MCP Red Hat Knowledge Base

MCP (Model Context Protocol) Server for searching the Red Hat Knowledge Base using the Hydra API.

## Features

- **searchKnowledgeBase**: Search Red Hat Knowledge Base for solutions, articles, and documentation
- **getSolution**: Get the full content of a Knowledge Base article including root cause, diagnostic steps, and resolution

## Requirements

- Java 21+
- Red Hat API Token (offline token)

## Quick Start

### Using npx (Recommended)

```bash
export REDHAT_TOKEN="your-offline-token"
npx mcp-redhat-kb
```

### Using Docker

```bash
docker run -i --rm -p 9081:9081 \
  -e REDHAT_TOKEN="your-token" \
  ghcr.io/jeanlopezxyz/mcp-redhat-kb:latest
```

## Getting Your Token

1. Go to https://access.redhat.com/management/api
2. Click "Generate Token"
3. Copy the offline token

## Configuration

### Claude Code

Add to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "redhat-kb": {
      "command": "npx",
      "args": ["-y", "mcp-redhat-kb@latest"],
      "env": {
        "REDHAT_TOKEN": "your-offline-token"
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
        "REDHAT_TOKEN": "your-offline-token"
      }
    }
  }
}
```

## Available Tools

### searchKnowledgeBase

Search Red Hat Knowledge Base for solutions and articles.

**Parameters:**
- `query` (required): Search keywords (use error messages or technical terms)
- `maxResults` (optional, default: 10): Maximum number of results
- `product` (optional): Filter by product (e.g., "OpenShift", "RHEL")
- `documentType` (optional): Filter by type: "Solution", "Documentation", "Article"

**Example:**
```
searchKnowledgeBase query="CrashLoopBackOff openshift" maxResults=5 documentType="Solution"
```

### getSolution

Get the full content of a Knowledge Base article or solution.

**Parameters:**
- `solutionId` (required): Article/Solution ID from searchKnowledgeBase results

**Example:**
```
getSolution solutionId="7129807"
```

## Running Modes

### stdio Mode (Default)

For use with Claude Code and Claude Desktop:

```bash
npx mcp-redhat-kb
```

### SSE Mode

For use with MCP Inspector or web clients:

```bash
npx mcp-redhat-kb --port 9081
```

SSE endpoint will be available at: `http://localhost:9081/mcp/sse`

## Building from Source

```bash
# Clone the repository
git clone https://github.com/jeanlopezxyz/mcp-redhat-kb.git
cd mcp-redhat-kb

# Build
./mvnw package -DskipTests

# Run
java -jar target/quarkus-app/quarkus-run.jar
```

## Related Projects

- [mcp-redhat-cases](https://github.com/jeanlopezxyz/mcp-redhat-cases) - MCP Server for Red Hat Support Cases

## License

MIT
