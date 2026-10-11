---
name: working-with-mcp
description: Use when using the GitHub or PostgreSQL MCP servers, when a tool error names something other than what is broken, or before pushing, committing or rewriting history in this repository.
---

# Working with MCP, and with git

## Config files

`.mcp.json` at the repository root is the **Claude Code** format: `mcpServers`, an `env` block,
`command` as separate keys. OpenCode ignores it — `opencode mcp list` answers *No MCP servers
configured*. OpenCode wants the key `mcp`, `"type": "local" | "remote"` on every entry, `command` as
**one array** of executable plus arguments, and `environment` rather than `env`.

Both are kept deliberately. `opencode.json` in the project root carries Context7, which is tied to
this repository's dependencies; `~/.config/opencode/opencode.jsonc` carries GitHub and PostgreSQL,
which are personal and carry a token.

## PostgreSQL server

Read-only on purpose, and enforced three times over: the `readonly` role has only `SELECT`,
`PGOPTIONS` sets `default_transaction_read_only=on`, and the server runs every statement in
`BEGIN READ ONLY`. A writable agent connection to a development database is how a table disappears,
so the guard belongs in the configuration rather than in the instructions. **An agent cannot change
`currency_pair` through this server** — that needs psql or a migration, see
`changing-swept-currencies`.

`@crystaldba/postgres-mcp` no longer exists on npm; the registry answers 404, and a command that
cannot resolve fails as a server that "exited with code 1" rather than as a missing package. The
replacement is `@contextflo/postgres-mcp`.

### Two settings that look like they work and do not

**The connection string must be a command argument, not `DATABASE_URL`.** The package reads
`DATABASE_URL`, and run by hand it works that way — which is what makes it a trap. Under OpenCode the
same configuration hangs at `pending` and never initialises, while the identical string as the last
argument connects. Pass it in `command`:

```json
"command": ["npx", "-y", "@contextflo/postgres-mcp", "postgresql://readonly:readonly@localhost:5432/financial"]
```

**`statement_timeout` in `PGOPTIONS` is silently ignored.** The server runs every statement in its own
`BEGIN READ ONLY` with its own timeout and overrides the session setting, so the configured 5000 ms
never applies and `show statement_timeout` answers `30s` — the package default. The only thing that
changes it is the flag:

```json
"command": ["npx", "-y", "@contextflo/postgres-mcp", "--statement-timeout", "5000", "postgresql://…"]
```

Verify both rather than trusting the file:

```sql
show statement_timeout;                              -- 5s, not 30s
select current_user, current_setting('default_transaction_read_only');   -- readonly | on
```

### "Request timed out" is OpenCode's own limit, not the server's

A local MCP server has a `timeout` option, and it defaults to **5000 ms**. `npx -y` on a cold npx cache
downloads the package before the server answers, which takes longer than that, so the server fails
with `Request timed out` — and works afterwards, from the cache, which is why it looks intermittent.
Set it explicitly on every server started through `npx`:

```json
"postgres": { "type": "local", "command": ["npx", "-y", "…"], "enabled": true, "timeout": 30000 }
```

`opencode mcp list` prints `○ pending` for a server still initialising and `✓ connected` for one
ready; a `pending` that never becomes `connected` is a hang, not slowness.

## GitHub server

The remote endpoint `https://api.githubcopilot.com/mcp/` answers **403** to a plain `gh` token,
which works fine against `api.github.com`. The local Docker image
`ghcr.io/github/github-mcp-server` takes the same token and is what is configured.

The token comes from `gh auth token` in `config.fish`, so it is never written to a config file or
into the repository. **The server must see it in the environment at startup**, or it falls back to
a device-code flow that waits for a browser and looks like a hang.

`GITHUB_TOOLSETS` is not optional: the server registers more tools than a session can carry, and
`context,repos,issues,pull_requests,actions` is what this project needs.

## Commands

```bash
opencode mcp list                 # ✓ connected / ○ pending / ✗ failed
gh run list --branch master --limit 5
gh run list --branch master --limit 1 --json databaseId --jq '.[0].databaseId'   # run id
gh run view <id> --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
gh run view <id> --log-failed | head -50
gh repo view --json isPrivate,visibility
gh auth token                     # never echo this into a file
```

A GitHub run that fails in seconds with no jobs is a workflow parse error, not a test failure.
`gh run view <id> --json jobs` returns an empty array for one and a populated one for the other.

## Git

**Do not push while another agent is working in this tree.** Two agents committing in one
repository means one commits the other's half-finished files under the wrong message, which has
happened here. Stage paths explicitly — `git add -A` sweeps in whatever appeared since the last
commit, which is the same failure with extra steps.

Check before staging whether the tree holds work you did not write:

```bash
git status --short
git diff --stat
```

Rewriting history is free before the first push and needs `--force-with-lease` after it.

`gh` is authenticated, but its token may lack a scope a push needs. Pushing a **workflow file**
fails with `refusing to allow an OAuth App to create or update workflow ... without workflow
scope`. SSH keys are not subject to that restriction, so `git@github.com:` is the escape when
`gh auth refresh -s workflow` is not available.

A public key on disk is not a key GitHub accepts. `ssh-add` after `ssh-agent -c` is the step that is
easy to skip, and the failure — `Permission denied (publickey)` — reads as if the key were rejected.

## When an MCP error names the wrong thing

The error is about the tool's resolution, not about the code. Two seen here:

- **`Can't find 'action.yml'`** on a local composite action points at the missing
  `actions/checkout` *in front of it*. A local action is a path on the runner, so the repository has
  to be on disk before it resolves.
- **`repository not found`** from the Runner Image Provisioner is an action that does not exist.
  `actions/setup-buildx-action` is not a repository — buildx is published by **Docker**. Check the
  name against the API before assuming the workflow is at fault.

Neither is catchable by running the same Maven command locally. Workflow bugs surface on the first
real push, so the first run has to be watched to its first real step rather than judged by its
status.

## Writing the request that triggers this skill

Weak: "push the changes". The agent cannot tell whether the tree holds another agent's unfinished
work, and `git add -A` will commit it.

Strong: "Commit only the four files under `docs/`, leave `src/` untouched, and push to master.
The tree has another agent's unfinished changes in `src/main/java/.../client/` — do not stage
them."

Checklist:

- Name the files or the paths, not "the changes".
- Say what must **not** be staged when other work is in the tree.
- Say which branch, when it is not the current one.
- For a CI question, ask for `gh run view --json jobs` before asking for logs: the jobs array
  distinguishes a parse error from a failing step, and it costs one call.
- Diagnose an MCP problem from the config outwards, not by re-running the server. `opencode mcp list`
  first, then the two SQL settings that say whether the guard is in force, then the file.

## Keeping the context small

- Check `gh run list` for the conclusion before pulling logs. `--log-failed` on a green run returns
  nothing and on a multi-thousand-line run returns everything.
- Read the JSON view first and request logs only for the failing job: `gh run view <id> --log-failed
  | grep -i error | head`.
- For schema questions query through the MCP server rather than reading migration files; for "what
  does this table mean" read `docs/database.md`, which is shorter than the SQL and says which
  queries the schema exists to serve.