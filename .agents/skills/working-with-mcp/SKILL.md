---
name: working-with-mcp
description: Use when using the GitHub or PostgreSQL MCP servers, when a tool error names something other than what is broken, or before pushing, committing or rewriting history in this repository.
---

# Working with MCP, and with git

## Two harnesses, two config files, neither reads the other's

`.mcp.json` at the repository root is the **Claude Code** format: `mcpServers`, an `env` block,
`command` as separate keys. OpenCode ignores it — `opencode mcp list` answers *No MCP servers
configured*. OpenCode wants the key `mcp`, a `"type": "local" | "remote"` on every entry,
`command` as **one array** combining executable and arguments, and `environment` rather than `env`.

Both files are kept deliberately: `opencode.json` in the project root carries Context7, being the
server tied to this repository's dependencies, and `~/.config/opencode/opencode.jsonc` carries
GitHub and PostgreSQL, being personal and carrying a token.

## The PostgreSQL server is read-only on purpose

`default_transaction_read_only=on` and `statement_timeout=5000`. A writable agent connection to a
development database is how a table disappears, so the guard belongs in the config rather than in
the instructions.

The direct consequence, and the one that gets forgotten: **an agent cannot change `currency_pair`
through this server.** Changing which currencies are swept needs psql or a migration. See
`changing-swept-currencies`.

The `readonly` role has to exist. `docker-compose.yml` seeds only the application role.

`@crystaldba/postgres-mcp` no longer exists on npm and the registry answers 404; a command that
cannot resolve fails as a server that "exited with code 1" rather than as a missing package. The
replacement is `@contextflo/postgres-mcp`.

## The GitHub server

The remote endpoint `https://api.githubcopilot.com/mcp/` answers **403** to a plain `gh` token,
which works fine against `api.github.com`. The local Docker image
(`ghcr.io/github/github-mcp-server`) takes the same token and is what is configured.

The token comes from `gh auth token` in `config.fish`, so it is never written to a config file or
to the repository. **The server must see it in the environment at startup**, or it silently falls
back to a device-code flow that waits for a browser and looks like a hang.

`GITHUB_TOOLSETS` is not optional. The server registers far more tools than a session can carry;
`context,repos,issues,pull_requests,actions` is what this project needs.

## Git

**Do not push while another agent is working in this tree.** Two agents committing in the same
repository means one of them commits the other's half-finished files under the wrong message, which
has happened here. Stage the paths explicitly — `git add -A` will sweep in whatever appeared since
the last commit, which is the same failure with extra steps.

Rewriting history is free before the first push and needs `--force-with-lease` after it.

`gh` is authenticated, but its token may lack a scope a push needs. The common one: **pushing a
workflow file fails** with `refusing to allow an OAuth App to create or update workflow ... without
workflow scope`. SSH keys are not subject to that restriction, so switching the remote to
`git@github.com:` is the escape when `gh auth refresh -s workflow` is not an option.

A public key on disk is not a key GitHub accepts. `ssh-add` after `ssh-agent -c` is the step that
is easy to skip, and the failure — `Permission denied (publickey)` — reads as if the key were
rejected.

## When an MCP error names the wrong thing

The error is about the tool's resolution, not about your code. Two seen here:

- **`Can't find 'action.yml'`** on a local composite action points at the missing
  `actions/checkout` *in front of it*. A local action is a path on the runner, so the repository
  has to be on disk before it resolves.
- **`repository not found`** from the Runner Image Provisioner is an action that does not exist.
  `actions/setup-buildx-action` is not a repository — buildx is published by **Docker**. Check the
  name against the API before assuming the workflow is at fault.

Neither is catchable by running the same Maven command locally. Workflow bugs surface on the first
real push, which is why the first run has to be watched to its first real step rather than judged
by its status.