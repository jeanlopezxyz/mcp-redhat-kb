#!/usr/bin/env node

const { spawn, execSync } = require("child_process");
const { createHash } = require("crypto");
const fs = require("fs");
const path = require("path");
const https = require("https");

const GITHUB_REPO = "jeanlopezxyz/mcp-redhat-kb";
const JAR_NAME = "mcp-redhat-kb.jar";
const CHECKSUMS_NAME = "checksums.txt";
const CACHE_DIR = path.join(require("os").homedir(), ".cache", "mcp-redhat-kb");

// Hosts GitHub uses to serve release assets. Redirects outside this set are refused so a
// tampered Location header cannot point the download at an arbitrary server.
const ALLOWED_HOSTS = new Set([
  "api.github.com",
  "github.com",
  "objects.githubusercontent.com",
  "release-assets.githubusercontent.com",
]);

const MAX_REDIRECTS = 5;

// Parse command line arguments
function parseArgs() {
  const args = process.argv.slice(2);
  const options = {
    port: null,
    host: "127.0.0.1",
    help: false,
    version: false,
    extraArgs: [],
  };

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "--port" && args[i + 1]) {
      options.port = args[++i];
    } else if (arg.startsWith("--port=")) {
      options.port = arg.split("=")[1];
    } else if (arg === "--host" && args[i + 1]) {
      options.host = args[++i];
    } else if (arg.startsWith("--host=")) {
      options.host = arg.split("=")[1];
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else if (arg === "--version" || arg === "-v") {
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
  --port <PORT>    Start in HTTP mode on the given port (default: stdio mode)
  --host <HOST>    Interface to bind in HTTP mode (default: 127.0.0.1)
  --help, -h       Show this help message
  --version, -v    Show version

ENVIRONMENT:
  REDHAT_TOKEN   Red Hat API offline token (required)
                          Generate at: https://access.redhat.com/management/api

EXAMPLES:
  # stdio mode (default)
  npx mcp-redhat-kb

  # HTTP mode on port 9081, listening on localhost only
  npx mcp-redhat-kb --port 9081

SECURITY:
  In HTTP mode this server exposes no authentication of its own and holds your Red Hat
  token. It binds to 127.0.0.1 by default; only pass --host 0.0.0.0 when something else
  (a reverse proxy, a NetworkPolicy) restricts who can reach the port.

MCP CLIENT CONFIGURATION:
  Add to your MCP client configuration (VS Code, Cursor, Windsurf, etc.):

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

const MIN_JAVA_VERSION = 25;

// Check if Java is installed
function checkJava() {
  try {
    const result = execSync("java -version 2>&1", { encoding: "utf8" });
    const match = result.match(/version "(\d+)/);
    if (match && parseInt(match[1]) < MIN_JAVA_VERSION) {
      console.error(
        `Error: Java ${MIN_JAVA_VERSION}+ is required. Found:`,
        result.split("\n")[0],
      );
      process.exit(1);
    }
    return true;
  } catch {
    console.error(
      `Error: Java ${MIN_JAVA_VERSION}+ is required but not found.`,
    );
    console.error("Install Java from: https://adoptium.net/");
    process.exit(1);
  }
}

// Validate a URL before requesting it: HTTPS only, and only GitHub release hosts.
function assertAllowedUrl(rawUrl) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new Error(`Invalid download URL: ${rawUrl}`);
  }
  if (url.protocol !== "https:") {
    throw new Error(`Refusing non-HTTPS URL: ${url.protocol}//${url.host}`);
  }
  if (!ALLOWED_HOSTS.has(url.hostname)) {
    throw new Error(`Refusing download from unexpected host: ${url.hostname}`);
  }
  return url;
}

// GET a URL, following redirects within the allowed-host set.
function httpsGet(rawUrl, onResponse, reject, depth = 0) {
  if (depth > MAX_REDIRECTS) {
    reject(new Error("Too many redirects"));
    return;
  }

  let url;
  try {
    url = assertAllowedUrl(rawUrl);
  } catch (err) {
    reject(err);
    return;
  }

  https
    .get(url, { headers: { "User-Agent": "mcp-redhat-kb" } }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode)) {
        res.resume();
        if (!res.headers.location) {
          reject(
            new Error(
              `Redirect without a Location header from ${url.hostname}`,
            ),
          );
          return;
        }
        httpsGet(
          new URL(res.headers.location, url).toString(),
          onResponse,
          reject,
          depth + 1,
        );
        return;
      }
      onResponse(res);
    })
    .on("error", reject);
}

// Fetch a URL as a UTF-8 string
function fetchText(url) {
  return new Promise((resolve, reject) => {
    httpsGet(
      url,
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          reject(new Error(`Request failed: ${res.statusCode}`));
          return;
        }
        let data = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => (data += chunk));
        res.on("end", () => resolve(data));
        res.on("error", reject);
      },
      reject,
    );
  });
}

// Get release info for a specific tag from GitHub
async function getRelease(tag) {
  const body = await fetchText(
    `https://api.github.com/repos/${GITHUB_REPO}/releases/tags/${tag}`,
  );
  try {
    return JSON.parse(body);
  } catch {
    throw new Error("Failed to parse release info");
  }
}

// Download a file to disk
function downloadFile(url, dest) {
  return new Promise((resolve, reject) => {
    httpsGet(
      url,
      (res) => {
        if (res.statusCode !== 200) {
          res.resume();
          reject(new Error(`Download failed: ${res.statusCode}`));
          return;
        }

        const file = fs.createWriteStream(dest, { mode: 0o600 });
        res.pipe(file);
        file.on("error", (err) => {
          fs.unlink(dest, () => {});
          reject(err);
        });
        // Resolve only once the bytes are flushed and the handle is closed, so a truncated
        // download is never mistaken for a complete one.
        file.on("finish", () =>
          file.close((err) => (err ? reject(err) : resolve())),
        );
      },
      reject,
    );
  });
}

// Parse a "<sha256>  <filename>" checksum listing
function parseChecksums(text) {
  const sums = {};
  for (const line of text.split("\n")) {
    const match = line.trim().match(/^([a-fA-F0-9]{64})\s+\*?(.+)$/);
    if (match) {
      sums[path.basename(match[2].trim())] = match[1].toLowerCase();
    }
  }
  return sums;
}

function sha256(file) {
  return createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

// Get or download the JAR for this package version, verifying its checksum.
async function getJar(verbose = true) {
  const pkg = require("./package.json");
  const tag = `v${pkg.version}`;
  const jarPath = path.join(CACHE_DIR, JAR_NAME);
  const versionFile = path.join(CACHE_DIR, "version");

  fs.mkdirSync(CACHE_DIR, { recursive: true, mode: 0o700 });

  let cachedVersion = null;
  if (fs.existsSync(versionFile)) {
    cachedVersion = fs.readFileSync(versionFile, "utf8").trim();
  }
  if (cachedVersion === tag && fs.existsSync(jarPath)) {
    return jarPath;
  }

  // The JAR is pinned to this npm package's version rather than "latest": the version the
  // user installed is the version that runs, and a published release cannot be swapped
  // underneath them.
  const release = await getRelease(tag);

  const jarAsset =
    (release.assets || []).find((a) => a.name === JAR_NAME) ||
    (release.assets || []).find((a) => a.name.endsWith(".jar"));
  if (!jarAsset) {
    throw new Error(`No JAR found in release ${tag}`);
  }

  const sumsAsset = (release.assets || []).find(
    (a) => a.name === CHECKSUMS_NAME,
  );
  if (!sumsAsset) {
    throw new Error(
      `Release ${tag} publishes no ${CHECKSUMS_NAME}; refusing to run an unverified JAR.`,
    );
  }
  const expected = parseChecksums(
    await fetchText(sumsAsset.browser_download_url),
  )[jarAsset.name];
  if (!expected) {
    throw new Error(`${CHECKSUMS_NAME} has no entry for ${jarAsset.name}`);
  }

  if (verbose) console.error(`Downloading mcp-redhat-kb ${tag}...`);

  // Download to a temporary path and only publish it after the checksum matches, so an
  // interrupted or tampered download never lands at the path we execute.
  const tmpPath = `${jarPath}.${process.pid}.part`;
  try {
    await downloadFile(jarAsset.browser_download_url, tmpPath);

    const actual = sha256(tmpPath);
    if (actual !== expected) {
      throw new Error(
        `Checksum mismatch for ${jarAsset.name}.\n  expected: ${expected}\n  actual:   ${actual}`,
      );
    }

    fs.renameSync(tmpPath, jarPath);
    fs.chmodSync(jarPath, 0o600);
    fs.writeFileSync(versionFile, tag, { mode: 0o600 });
  } finally {
    if (fs.existsSync(tmpPath)) fs.unlinkSync(tmpPath);
  }

  if (verbose) console.error("Download complete (checksum verified).");
  return jarPath;
}

// Run the MCP server
async function main() {
  const options = parseArgs();

  if (options.help) {
    showHelp();
    process.exit(0);
  }

  if (options.version) {
    const pkg = require("./package.json");
    console.log(pkg.version);
    process.exit(0);
  }

  // Check environment
  if (!process.env.REDHAT_TOKEN) {
    console.error("Error: REDHAT_TOKEN environment variable is required.");
    console.error(
      "Get your token at: https://access.redhat.com/management/api",
    );
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
      // HTTP mode (Streamable HTTP transport)
      javaArgs.push(`-Dquarkus.http.port=${options.port}`);
      javaArgs.push(`-Dquarkus.http.host=${options.host}`);
      console.error(
        `Starting MCP server in HTTP mode on ${options.host}:${options.port}...`,
      );
      console.error(
        `Streamable HTTP endpoint: http://${options.host}:${options.port}/mcp`,
      );
      if (options.host === "0.0.0.0") {
        console.error(
          "Warning: binding to 0.0.0.0 exposes this server, and your Red Hat token, " +
            "to every host that can reach this port. It has no authentication of its own.",
        );
      }
    } else {
      // stdio mode (default) - disable HTTP server and enable stdio transport
      javaArgs.push("-Dquarkus.http.host-enabled=false");
      javaArgs.push("-Dquarkus.mcp.server.stdio.enabled=true");
      javaArgs.push("-Dquarkus.banner.enabled=false");
      javaArgs.push("-Dquarkus.log.level=WARN");
      javaArgs.push("-Dquarkus.mcp.server.traffic-logging.enabled=false");
    }

    javaArgs.push("-jar", jarPath);
    javaArgs.push(...options.extraArgs);

    // Run Java
    const java = spawn("java", javaArgs, {
      stdio: "inherit",
      env: process.env,
    });

    java.on("error", (err) => {
      console.error("Failed to start Java:", err.message);
      process.exit(1);
    });

    java.on("exit", (code) => {
      process.exit(code || 0);
    });

    // Handle termination
    const handleSignal = (signal) => {
      if (java && !java.killed) {
        java.kill(signal);
      }
    };

    process.on("SIGINT", () => handleSignal("SIGINT"));
    process.on("SIGTERM", () => handleSignal("SIGTERM"));
  } catch (error) {
    console.error("Error:", error.message);
    process.exit(1);
  }
}

main();
