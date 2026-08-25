# Red Hat Knowledge Base MCP Server Evaluations

Evaluations for the Red Hat Knowledge Base MCP server, run with
[mcpchecker](https://github.com/mcpchecker/mcpchecker).

These answer a question unit tests cannot: **does a real agent complete the task using
these tools?** mcpchecker starts the server, gives an LLM agent a goal in plain language,
records which tools it called, and has a second model judge whether it succeeded. That
catches ambiguous tool descriptions and unhelpful output — problems no deterministic test
would notice.

They are non-deterministic and cost API credits, so they run on a schedule rather than on
every commit. The deterministic suite lives in `src/test/java`.

## Structure

```
evals/
├── README.md                                # This file
├── mcp-config.yaml                          # Where the server is reached
├── tasks/
│   └── redhat-kb/
│       └── search-openshift-error.yaml      # One goal + its assertions
└── agent/
    ├── agents.yaml                          # The agent that drives the tasks
    └── eval.yaml                            # Ties agent, server and tasks together
```

## The agent

`agents.yaml` configures mcpchecker's `llm-agent`, which speaks the OpenAI-compatible API.
That is a wire format, not a vendor: `MODEL_BASE_URL` can point at OpenShift AI, vLLM,
Ollama, a corporate gateway, or a hosted provider.

The model is set as `provider:model-id`:

```yaml
builtin:
  type: "llm-agent"
  model: "openai:gpt-4o-mini"
```

Change it locally with `yq`, since mcpchecker does not expand variables inside its YAML:

```bash
yq -i '.builtin.model = "openai:granite-3.3-8b"' evals/agent/agents.yaml
```

In CI, set the **`EVAL_MODEL`** repository variable (Settings → Secrets and variables →
Actions → Variables) and the workflow applies it before running. Leave it unset to use the
file's default.

The default is deliberately a small model: these evaluations check that the agent picks the
right tool and reaches an answer, which does not need a frontier model, and they run
weekly, where cost accumulates.

## Prerequisites

- A Red Hat API token
- The MCP server reachable at `http://localhost:9081/mcp`
- `mcpchecker` installed

## Running locally

```bash
# Start the server (HTTP transport, no authentication for a local run)
REDHAT_TOKEN='your-token' \
  java -Dquarkus.oidc.enabled=false \
       -Dquarkus.http.auth.permission.mcp.policy=permit \
       -jar target/quarkus-app/quarkus-run.jar &

# Credentials for the agent and for the judge
export MODEL_BASE_URL='https://your-endpoint/v1'
export MODEL_KEY='your-api-key'
export JUDGE_BASE_URL='https://your-judge-endpoint/v1'
export JUDGE_API_KEY='your-judge-api-key'
export JUDGE_MODEL_NAME='your-judge-model'

mcpchecker check evals/agent/eval.yaml
```

## In CI

The `mcpchecker.yaml` workflow runs them:

- Weekly, Mondays at 09:00 UTC
- On demand via `workflow_dispatch`
- On a PR comment of `/run-mcpchecker`, from someone with write access

## Adding a task

Create a YAML file under `tasks/redhat-kb/`. The prompt should read like something a person
would actually ask — the point is to test how the agent interprets a real request, not to
script tool calls:

```yaml
kind: Task
metadata:
  name: "search-openshift-error"
prompt: "Search the Red Hat Knowledge Base for solutions to CrashLoopBackOff errors in OpenShift pods"
assertions:
  toolsUsed:
    - server: redhat-kb
      toolPattern: "searchKnowledgeBase"
  minToolCalls: 1
```

Run it locally before committing: a task that never passes is worse than no task.
