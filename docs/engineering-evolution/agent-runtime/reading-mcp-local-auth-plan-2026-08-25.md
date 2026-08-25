# Reading MCP Local Authentication Plan

Date: 2026-08-25

Status: Proposed. Java refresh-token hardening is required before MCP credential storage.

This plan replaces the immediate OAuth/PKCE introduction plan. PaperLoom's current MCP is local,
loopback-only, and normally used by one operating-system user. The smallest usable design is a local
login command, secure refresh-token storage, automatic refresh, and stdio as the default MCP
transport. OAuth/PKCE remains the required design for any future remote or shared MCP deployment.

The implemented `standalone-reading-mcp-spec-2026-08-23.md` remains authoritative until this plan's
cutover gate passes. The five read-only Tools, Java Reading facade, ACL, Current Model, response
bounds, audit, quota, and zero Source Quote behavior do not change.

## 1. Goal

A local user logs in once and then starts PaperLoom from an MCP client without copying or periodically
replacing JWTs.

```text
One-time setup
  paperloom_mcp auth login
    -> interactive username/password
    -> existing Java /api/v1/users/login
    -> verify USER or ADMIN
    -> store refresh token in OS credential store

Normal use
  MCP client
    -> starts paperloom_mcp over stdio
    -> local token manager loads refresh token
    -> Java /api/v1/auth/refreshToken
    -> access token remains in memory
    -> five Reading Tools call /api/v1/reading/**
```

Success means:

- no manual JWT copy or MCP Authorization Header maintenance;
- username/password are entered interactively once and sent only to Java;
- passwords and access tokens are never persisted;
- refresh tokens are stored only in the OS credential store;
- MCP stdout contains protocol messages only;
- Java still derives User, role, ACL, Current Model, and quota;
- remote and multi-user serving remain unsupported.

## 2. Decisions

### D1. Stdio Is The Default Local Transport

The MCP client launches `paperloom_mcp` as a child process and communicates over stdin/stdout. There
is no listening socket and no second local bearer credential.

```text
MCP client -> child process stdin/stdout -> paperloom_mcp -> Java Reading API
```

All diagnostics go to stderr. Authentication commands run as separate CLI invocations and never
share stdout with an active MCP protocol process.

### D2. Login Is A CLI Command, Not An MCP Tool Or Python HTTP Endpoint

Provide:

```text
python -m paperloom_mcp auth login
python -m paperloom_mcp auth status
python -m paperloom_mcp auth logout
python -m paperloom_mcp
python -m paperloom_mcp serve-stdio
python -m paperloom_mcp serve-http --host 127.0.0.1 --port 8090
```

The bare command is an alias for `serve-stdio`. `auth login` requires an interactive TTY and reads
the password with `getpass`. It MUST NOT accept password flags, environment
variables, stdin pipes, Tool arguments, query parameters, or config-file values.

Python MUST NOT add `/auth/login`, `/token`, or credential-collection MCP Tools. It calls the existing
Java product endpoints directly with redirects disabled and `trust_env=false`.

### D3. Reuse Java Login And Refresh, But Harden Refresh Tokens First

Reuse:

```text
POST /api/v1/users/login
POST /api/v1/auth/refreshToken
GET  /api/v1/users/me
```

The current refresh endpoint generates a new refresh token without invalidating the previous token,
and current logout does not revoke refresh tokens. MCP MUST NOT persist refresh credentials until
Java adds:

- atomic refresh-token rotation;
- one-time use of the previous refresh token;
- replay rejection;
- refresh-token indexing by user;
- current-device refresh revocation;
- logout-all revocation of access and refresh tokens;
- stable safe errors that never contain credentials;
- `401 INVALID_REFRESH_TOKEN` for invalid, expired, revoked, or replayed refresh tokens;
- `503 AUTH_STATE_UNAVAILABLE` for Redis timeout, connection, or atomic-operation failure.

Use the existing Redis token store and one atomic Redis operation or Lua script. Do not add a second
OAuth token database for the local design. Auth-critical Redis reads and writes MUST propagate
availability failures instead of returning `false` or swallowing exceptions. Java MUST NOT return a
new access or refresh token until all corresponding token state is durably stored.

### D4. Store Only The Refresh Token In The OS Credential Store

Use the maintained Python `keyring` package. The production code MUST fail closed when no secure
backend is available; it MUST NOT fall back to plaintext `.env`, JSON, SQLite, home-directory files,
or reversible application-managed encryption.

Initial storage contract:

| Field | Value |
| --- | --- |
| keyring service | `paperloom-mcp` |
| keyring account | `default` |
| secret value | versioned JSON containing generation, state, Java base URL, username, refresh token |
| state | `active` or `pending_revoke` |

Only one local PaperLoom profile is supported initially. Multiple accounts and servers are deferred.
Tests use an injected in-memory fake and never the developer's real keyring.

Only native secure backends are accepted initially: macOS Keychain, Windows Credential Locker, and
Linux Secret Service. Reject fail, null, plaintext, alternate-file, and chained backends unless the
chain's selected backend is explicitly allowlisted and tested. Backend detection is part of the
Phase 1 Gate, not a warning-only check.

All keyring read/rotate/write/delete sequences also use one cross-process file lock. Use the
maintained `filelock` package with a non-secret lock file under a per-user directory created with
owner-only permissions where supported. After acquiring the lock, always re-read keyring before
acting. The lock file never contains usernames or credentials.

### D5. Keep Access Tokens In Memory And Refresh On Authentication Failure

The local token manager:

1. loads the refresh token during stdio startup;
2. exchanges it immediately for a new access token and rotated refresh token;
3. writes the rotated refresh token to keyring before serving Tools;
4. calls `/api/v1/users/me` and requires the stored account still be USER or ADMIN;
5. keeps the access token only in memory;
6. attaches it to Java Reading requests;
7. on Java 401, performs one synchronized refresh, rechecks `/users/me`, and retries the read-only
   call once;
8. never retries 403, 404, quota, model, validation, or retrieval failures.

Use one `asyncio.Lock` for tasks in a process and the cross-process file lock for multiple MCP child
processes. A process acquires the file lock through `asyncio.to_thread`, re-reads the latest keyring
generation, rotates that refresh token, and atomically replaces the stored generation. A caller that
waited for another in-process refresh reuses the new access token instead of refreshing again.

Python treats tokens as opaque and does not decode expiry or claims. A Java or network outage is not
an authentication failure: timeout/5xx returns `AUTH_UNAVAILABLE` and MUST NOT delete keyring
state or trigger a login loop. A confirmed invalid/replayed refresh token clears the stored credential
only when its generation and token still match the failed value; otherwise another process already
replaced it. The terminal error is `LOGIN_REQUIRED`.

If Java rotates the token but the process crashes before keyring is updated, the user logs in again.
Do not weaken one-time rotation to hide this rare recovery case.

### D6. Keep HTTP Mode For Compatibility, Not As The Default UX

The existing loopback Streamable HTTP server remains available for protocol tests and advanced
integrations. Its authentication contract does not change:

- every request carries the caller's current PaperLoom Bearer token;
- Python forwards that token unchanged;
- stored local credentials are never used by HTTP mode;
- non-loopback serving remains rejected.

This avoids creating an unauthenticated loopback HTTP service backed by the user's keyring token.

### D7. OAuth/PKCE Is Deferred To Remote Or Shared Deployment

OAuth 2.1 Authorization Code + PKCE becomes mandatory when any of these are approved:

- MCP listens beyond loopback;
- one MCP process serves multiple users;
- a hosted MCP endpoint is exposed over HTTPS;
- a target MCP client requires standard browser authorization.

That future design uses Java as Authorization Server and MCP Protected Resource. Authorization
Server persistence, consent, RFC 8707 resource binding, DCR/CIMD, introspection, and browser UI are
not part of the local-auth implementation.

## 3. CLI Contract

### `auth login`

1. Validate that the Java base URL is loopback HTTP or verified HTTPS.
2. Prompt for username and password interactively.
3. Call Java login without redirects or environment proxies.
4. Call `/api/v1/users/me` with the returned access token.
5. Accept USER or ADMIN only; reject GUEST and revoke/discard returned credentials.
6. Store the refresh token in keyring.
7. Remove password and access-token references before returning.
8. Print only the username, server, role, and success status.

Login failures use one fixed message and never reveal whether a username exists.

Before prompting, briefly acquire the cross-process lock and inspect keyring. If any `active` or
`pending_revoke` credential exists, refuse login and require `auth logout` to complete first. After
reading credentials, reacquire the lock, recheck that keyring is still empty, and hold the lock
through Java login, role verification, and keyring write. Do not overwrite an existing entry and do
not add a force-replace path in the initial implementation.

### `auth status`

Read keyring metadata and report the configured server and username. It does not print, validate, or
refresh credentials. Exit nonzero when no credential exists or the keyring backend is unavailable.

### `auth logout`

Under the cross-process lock, change the stored state to `pending_revoke` before calling the Java
refresh-token revocation endpoint. `serve-stdio` refuses pending credentials. On successful
revocation, or a confirmed already-invalid token, delete the keyring entry. If Java or Redis is
unavailable, retain the pending credential, exit nonzero, and retry revocation on the next
`auth logout`; never discard the only server-side revocation handle.

### `serve-stdio`

Fail before MCP initialization when login is required, keyring is unavailable, refresh fails, the
stored Java URL is invalid, or the stored account is GUEST. Once initialized, expose exactly the same
five Tools and schemas as the current HTTP implementation.

## 4. Implementation Shape

### Java

1. Add refresh-token IDs to a per-user Redis set.
2. Add one atomic rotate operation: validate old ID, delete old key/set membership, create new token
   state, and return success exactly once.
3. Change `/api/v1/auth/refreshToken` to use the atomic operation.
4. Add current-refresh-token revocation for CLI logout.
5. Make logout-all delete both access-token and refresh-token sets.
6. Recheck that the current database user still exists during refresh. Stdio performs the MCP-specific
   USER/ADMIN check through `/api/v1/users/me`, so ordinary product refresh behavior does not change.
7. Make auth-critical Redis failures propagate to login/refresh/revoke as stable 503 responses with
   no partial token issuance or deletion.
8. Preserve current legacy access-token behavior for product clients.

### Python

1. Add `keyring` and `filelock` to `requirements.in` and regenerate the lock.
2. Add `auth login/status/logout` and explicit `serve-stdio/serve-http` commands.
3. Add one concrete local token manager with keyring read/write/delete, startup refresh, synchronized
   401 refresh, cross-process coordination, generation checks, pending revocation, and single retry.
4. Let `ReadingMcpGateway` receive one async token provider callable. Stdio uses the local token
   manager; HTTP uses the existing per-request bearer context.
5. Reuse the existing five Tool definitions, output validation, size limits, error mapping, and Java
   paths. Do not create a second Tool dispatcher or schema registry.
6. Add MCP SDK stdio transport using the existing low-level `Server`.
7. Enforce a 1 MiB stdio JSON-RPC frame limit before JSON parsing. Oversized input terminates the
   stdio session without echoing input or credentials.

## 5. Delivery Phases

### Phase 0. Java Refresh Safety

Implement and prove atomic refresh rotation and revocation before Python stores any refresh token.

**Gate:** concurrent refresh permits exactly one winner; old-token replay fails; Redis outage returns
503 without consuming token state; login never returns unstored tokens; logout and logout-all revoke
the intended refresh credentials; current product access-token tests remain green.

### Phase 1. Python Credential CLI

Add keyring-backed login, status, and logout. No MCP transport changes yet.

**Gate:** password/access token never persist or enter logs; insecure keyring backend fails closed;
GUEST is rejected; login cannot overwrite an existing credential; pending revocation survives Java
outage; rotated refresh token replaces the previous generation under a cross-process lock.

### Phase 2. Stdio And Automatic Refresh

Add stdio transport and the local token manager. Make stdio the documented default while keeping
HTTP behavior unchanged.

**Gate:** initialize/list/all five Tools work through an MCP stdio client; concurrent 401 responses
cause one in-process refresh; two independent stdio processes safely serialize rotation; a refreshed
call retries once; Java outage does not delete credentials; an oversized stdio frame is rejected
before JSON parsing.

### Phase 3. Regression And Cutover

Run:

- Java full regression;
- Harness full regression;
- standalone MCP HTTP tests;
- standalone MCP stdio tests;
- clean locked install;
- PaperLoom Reading benchmark gate;
- real USER login, process restart, automatic refresh, five-Tool real-PDF smoke;
- unchanged Source Quote count;
- logout/replay and malformed-token checks;
- Redis outage during login, refresh, rotation, and revoke;
- two-process startup/refresh/keyring generation checks;
- pending-revocation recovery;
- 1 MiB stdio frame boundary;
- log scan for username/password/access token/refresh token and Paper content.

Only after the full gate passes may Quick Start switch from HTTP Bearer setup to stdio login setup.

## 6. Configuration And Security

Supported production configuration:

```text
PAPERLOOM_PUBLIC_API_BASE_URL
```

HTTP is allowed only for `127.0.0.1`, `localhost`, or `::1`. Every other Java URL requires HTTPS with
certificate verification. Login and refresh requests do not follow redirects and do not honor proxy
environment variables.

No password, access token, or refresh token configuration variable is supported. CI and tests use
fakes rather than environment credentials.

## 7. Explicitly Deferred

- OAuth/PKCE for local stdio;
- remote or shared MCP serving;
- Java Authorization Server;
- OAuth consent and introspection;
- Dynamic Client Registration and CIMD;
- multiple local accounts or PaperLoom servers;
- plaintext credential fallback;
- automatic refresh-token caching outside the OS credential store;
- frontend, Field Extraction, Harness, Evidence, Source Quote, or Reading Tool changes.

## 8. Commit Sequence

```text
1. fix(auth): rotate and revoke refresh tokens atomically
2. feat(mcp): add keyring-backed local authentication commands
3. feat(mcp): add stdio transport and synchronized token refresh
4. test(mcp): cover local auth, stdio, replay, and regression gates
5. docs(mcp): document one-time login and stdio setup
```

Each commit runs its changed-surface tests. No Python credential-storage commit may merge before the
Java Refresh Safety Gate passes, and no Quick Start cutover may merge before the full Phase 3 gate is
green.
