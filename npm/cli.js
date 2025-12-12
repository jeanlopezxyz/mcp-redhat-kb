#!/usr/bin/env node

const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const https = require('https');

const GITHUB_REPO = 'jeanlopezxyz/mcp-redhat-kb';
const JAR_NAME = 'mcp-redhat-kb.jar';
const CACHE_DIR = path.join(require('os').homedir(), '.cache', 'mcp-redhat-kb');

// Parse command line arguments
function parseArgs() {
  const args = process.argv.slice(2);
  const options = {
    port: null,
    help: false,
    version: false,
    extraArgs: []
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--port' && args[i + 1]) {
      options.port = args[++i];
    } else if (arg.startsWith('--port=')) {
      options.port = arg.split('=')[1];
    } else if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--version' || arg === '-v') {
      options.version = true;
    } else {
      options.extraArgs.push(arg);
    }
  }

  return options;
}

// Show help
function showHelp() {
  console.log(`
mcp-redhat-kb - MCP Server for Red Hat Knowledge Base

USAGE:
  npx mcp-redhat-kb [OPTIONS]

OPTIONS:
  --port <PORT>    Start in SSE mode on specified port (default: stdio mode)
  --help, -h       Show this help message
  --version, -v    Show version

ENVIRONMENT:
  REDHAT_TOKEN   Red Hat API offline token (required)
                         Generate at: https://access.redhat.com/management/api

EXAMPLES:
  # stdio mode (for Claude Code, Claude Desktop)
  npx mcp-redhat-kb

  # SSE mode on port 9081
  npx mcp-redhat-kb --port 9081

CLAUDE CODE CONFIGURATION:
  Add to ~/.claude/settings.json:

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
`);
}

// Check if Java is installed
function checkJava() {
  try {
    const result = execSync('java -version 2>&1', { encoding: 'utf8' });
    const match = result.match(/version "(\d+)/);
    if (match && parseInt(match[1]) < 21) {
      console.error('Error: Java 21+ is required. Found:', result.split('\n')[0]);
      process.exit(1);
    }
    return true;
  } catch {
    console.error('Error: Java 21+ is required but not found.');
    console.error('Install Java from: https://adoptium.net/');
    process.exit(1);
  }
}

// Get latest release info from GitHub
async function getLatestRelease() {
  return new Promise((resolve, reject) => {
    const options = {
      hostname: 'api.github.com',
      path: `/repos/${GITHUB_REPO}/releases/latest`,
      headers: { 'User-Agent': 'mcp-redhat-kb' }
    };

    https.get(options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          resolve(JSON.parse(data));
        } catch (e) {
          reject(new Error('Failed to parse release info'));
        }
      });
    }).on('error', reject);
  });
}

// Download file from URL
function downloadFile(url, dest) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(dest);

    const request = (url) => {
      https.get(url, { headers: { 'User-Agent': 'mcp-redhat-kb' } }, (res) => {
        if (res.statusCode === 302 || res.statusCode === 301) {
          request(res.headers.location);
          return;
        }

        if (res.statusCode !== 200) {
          reject(new Error(`Download failed: ${res.statusCode}`));
          return;
        }

        res.pipe(file);
        file.on('finish', () => {
          file.close();
          resolve();
        });
      }).on('error', (err) => {
        fs.unlink(dest, () => {});
        reject(err);
      });
    };

    request(url);
  });
}

// Get or download JAR
async function getJar(verbose = true) {
  const jarPath = path.join(CACHE_DIR, JAR_NAME);
  const versionFile = path.join(CACHE_DIR, 'version');

  // Create cache directory
  if (!fs.existsSync(CACHE_DIR)) {
    fs.mkdirSync(CACHE_DIR, { recursive: true });
  }

  try {
    const release = await getLatestRelease();
    const latestVersion = release.tag_name;

    // Check if we have the latest version
    let currentVersion = null;
    if (fs.existsSync(versionFile)) {
      currentVersion = fs.readFileSync(versionFile, 'utf8').trim();
    }

    if (currentVersion === latestVersion && fs.existsSync(jarPath)) {
      return jarPath;
    }

    // Find JAR asset
    const jarAsset = release.assets.find(a => a.name.endsWith('.jar'));
    if (!jarAsset) {
      throw new Error('No JAR found in release');
    }

    if (verbose) console.error(`Downloading mcp-redhat-kb ${latestVersion}...`);
    await downloadFile(jarAsset.browser_download_url, jarPath);
    fs.writeFileSync(versionFile, latestVersion);
    if (verbose) console.error('Download complete.');

    return jarPath;
  } catch (error) {
    // If download fails but we have a cached JAR, use it
    if (fs.existsSync(jarPath)) {
      if (verbose) console.error('Warning: Could not check for updates, using cached version.');
      return jarPath;
    }
    throw error;
  }
}

// Run the MCP server
async function main() {
  const options = parseArgs();

  if (options.help) {
    showHelp();
    process.exit(0);
  }

  if (options.version) {
    const pkg = require('./package.json');
    console.log(pkg.version);
    process.exit(0);
  }

  // Check environment
  if (!process.env.REDHAT_TOKEN) {
    console.error('Error: REDHAT_TOKEN environment variable is required.');
    console.error('Get your token at: https://access.redhat.com/management/api');
    process.exit(1);
  }

  checkJava();

  try {
    // In stdio mode, suppress download messages to avoid stderr noise
    const verbose = !!options.port;
    const jarPath = await getJar(verbose);

    // Build Java arguments based on mode
    const javaArgs = [];

    if (options.port) {
      // SSE mode
      javaArgs.push(`-Dquarkus.http.port=${options.port}`);
      javaArgs.push('-Dquarkus.http.host=0.0.0.0');
      console.error(`Starting MCP server in SSE mode on port ${options.port}...`);
      console.error(`SSE endpoint: http://localhost:${options.port}/mcp/sse`);
    } else {
      // stdio mode (default) - disable HTTP server and enable stdio transport
      javaArgs.push('-Dquarkus.http.host-enabled=false');
      javaArgs.push('-Dquarkus.mcp.server.stdio.enabled=true');
      javaArgs.push('-Dquarkus.banner.enabled=false');
      javaArgs.push('-Dquarkus.log.level=WARN');
      javaArgs.push('-Dquarkus.mcp.server.traffic-logging.enabled=false');
    }

    javaArgs.push('-jar', jarPath);
    javaArgs.push(...options.extraArgs);

    // Run Java
    const java = spawn('java', javaArgs, {
      stdio: 'inherit',
      env: process.env
    });

    java.on('error', (err) => {
      console.error('Failed to start Java:', err.message);
      process.exit(1);
    });

    java.on('exit', (code) => {
      process.exit(code || 0);
    });

    // Handle termination
    const handleSignal = (signal) => {
      if (java && !java.killed) {
        java.kill(signal);
      }
    };

    process.on('SIGINT', () => handleSignal('SIGINT'));
    process.on('SIGTERM', () => handleSignal('SIGTERM'));

  } catch (error) {
    console.error('Error:', error.message);
    process.exit(1);
  }
}

main();
