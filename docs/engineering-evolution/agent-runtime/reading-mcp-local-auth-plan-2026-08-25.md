# Reading MCP Local Authentication Plan

Date: 2026-08-27

Status: Proposed. This plan targets one desktop account and one local MCP client. There are no
production users or external MCP consumers to migrate.

This plan replaces manual JWT handling with one local login command, native OS credential storage,
automatic refresh, and stdio as the default MCP transport. It does not add OAuth, frontend work,
remote serving, multi-user support, or plaintext credential fallback.

The existing five Reading Tools, Java Reading facade, ACL, Current Model, quota, response bounds,
audit, and zero Source Quote behavior remain unchanged.

## 1. Goal

```text
One-time setup
  python -m paperloom_mcp auth login
    -> interactive username/password
    -> Java /api/v1/users/login
    -> OS credential store keeps refresh token

Normal use
  MCP client
    -> starts python -m paperloom_mcp over stdio
    -> Python refreshes access token automatically
    -> five Reading Tools call Java /api/v1/reading/**
```

The user never copies a JWT or edits an Authorization Header.

## 2. Scope

### In Scope

- atomic Java refresh-token rotation;
- refresh-token revoke and logout-all cleanup;
- Redis outage mapped to stable 503;
- native macOS, Windows, or Linux credential storage through `keyring`;
- `auth login`, `auth status`, and `auth logout`;
- MCP stdio transport;
- access token in memory only;
- one synchronized refresh and one retry after Java 401;
- existing loopback HTTP mode retained for tests.

### Out Of Scope

- migration of existing refresh tokens;
- headless Linux without an active Secret Service credential store;
- multiple local accounts or PaperLoom servers;
- multiple concurrent MCP stdio processes sharing one local credential;
- OAuth/PKCE, DCR, CIMD, consent, or introspection;
- remote or shared MCP serving;
- frontend, Field Extraction, Harness, Evidence, Source Quote, or Tool changes.

## 3. Decisions

### D1. Invalidate Existing Refresh Tokens

There are no production users to preserve. The new implementation uses:

```text
jwt:refresh:v2:<refreshTokenId>
jwt:user:<userId>:refresh_tokens:v2
```

Legacy `jwt:refresh:<refreshTokenId>` entries are ignored and expire naturally. After deployment,
existing sessions perform one new login. Do not add legacy-token migration, dual reads, SCAN jobs,
or rollback restoration.

### D2. Make Refresh Rotation Atomic

`POST /api/v1/auth/refreshToken` keeps its current request and success response shape, but its state
transition changes:

1. validate refresh JWT signature, type, expiry, and current database user;
2. generate the next refresh-token ID;
3. run one Redis Lua operation that verifies the old v2 key, deletes it, removes it from the user set,
   creates the new v2 key, adds it to the user set, and applies TTL;
4. return the new access and refresh tokens only after required Redis writes succeed.

Exactly one concurrent refresh may succeed. Reusing the previous token fails.

Stable failures:

| Condition | HTTP | Error |
| --- | --- | --- |
| invalid, expired, legacy, revoked, or replayed refresh token | 401 | `INVALID_REFRESH_TOKEN` |
| Redis timeout, connection failure, or atomic-operation failure | 503 | `AUTH_STATE_UNAVAILABLE` |

Auth-critical Redis methods must propagate availability failures instead of returning `false` or
swallowing exceptions. No response or log may contain a token.

### D3. Add Refresh Revocation

Add:

```text
POST /api/v1/auth/revokeRefreshToken
body: { "refreshToken": "..." }
```

The refresh token authorizes its own revocation.

| Condition | HTTP | Error |
| --- | --- | --- |
| revoked or already invalid | 200 | none |
| malformed request | 400 | `INVALID_REFRESH_REQUEST` |
| Redis unavailable | 503 | `AUTH_STATE_UNAVAILABLE` |

`logout-all` deletes both access-token and v2 refresh-token sets. Ordinary product access-token
behavior otherwise remains unchanged.

### D4. Use The Native OS Credential Store

Use the maintained Python `keyring` package and require the platform-native secure backend: macOS
Keychain, Windows Credential Manager, or Linux Secret Service. Reject missing, fail, null, chained,
plaintext, or alternate-file backends. Linux requires an active Secret Service session. Do not fall
back to `.env`, JSON, SQLite, home-directory files, or application-managed encryption.

Store one versioned secret:

| Field | Value |
| --- | --- |
| keyring service | `paperloom-mcp` |
| keyring account | `default` |
| value | Java base URL, username, refresh token |

Only the refresh token persists. Passwords and access tokens remain in process memory and are never
written to disk, stdout, traces, or logs.

### D5. Login Is A CLI Command

Provide:

```text
python -m paperloom_mcp auth login
python -m paperloom_mcp auth status
python -m paperloom_mcp auth logout
```

`auth login`:

1. requires an interactive TTY;
2. reads password with `getpass`;
3. accepts no password flag, environment value, pipe, Tool argument, or config value;
4. allows loopback HTTP or verified HTTPS Java URLs only;
5. disables redirects and environment proxies;
6. calls Java login and `/api/v1/users/me`;
7. accepts USER or ADMIN; for GUEST, revoke the returned refresh token before rejecting login;
8. refuses to overwrite an existing stored credential; the user runs logout first;
9. stores only the refresh token and prints only server, username, role, and success.

Login failures use one fixed message and do not reveal whether the username exists.

`auth status` reports the locally configured server and username without refreshing or printing
credentials.

`auth logout` calls refresh-token revocation before deleting credential-store state. If Java or Redis
is unavailable, it keeps the credential, exits nonzero, and asks the user to retry. A confirmed
successful or already-invalid revocation deletes the credential.

Python MUST NOT add a login HTTP endpoint or credential-collection MCP Tool.

### D6. Stdio Is The Default MCP Transport

```text
MCP client -> child process stdin/stdout -> paperloom_mcp -> Java Reading API
```

Commands:

```text
python -m paperloom_mcp              # serve-stdio
python -m paperloom_mcp serve-stdio
python -m paperloom_mcp serve-http --host 127.0.0.1 --port 8090
```

MCP stdout contains protocol messages only; diagnostics use stderr. Stdio rejects JSON-RPC frames
larger than 1 MiB before JSON parsing.

HTTP mode keeps the current per-request Bearer contract and never reads stored credentials. It
remains loopback-only and is retained for tests and advanced debugging, not as the default setup.

Because there are no external consumers, the current top-level `--host/--port` form is removed rather
than maintained through a deprecation period.

### D7. Refresh Access Tokens In Memory

During stdio startup:

1. read the stored refresh token;
2. exchange it for a new access token and rotated refresh token;
3. replace the stored refresh token;
4. call `/api/v1/users/me` and require USER or ADMIN;
5. keep the access token only in memory;
6. begin MCP initialization.

Within one process, an `asyncio.Lock` serializes refresh. On Java 401, the first caller refreshes and
retries the read-only request once; waiting callers reuse the new access token. Never retry 403, 404,
quota, Current Model, validation, or retrieval failures.

The stored credential belongs to one local MCP client configuration and MUST NOT be shared by
concurrent MCP processes. The Java server only enforces atomic rotation and replay rejection; it does
not coordinate clients. Supporting another simultaneous MCP client requires an independent local
profile and refresh-token session, which is deferred. Do not weaken rotation or add server-side
client coordination.

Timeout or 5xx returns `AUTH_UNAVAILABLE` without deleting credential-store state. A confirmed invalid or
replayed refresh token deletes the credential and returns `LOGIN_REQUIRED`.

If Java rotates the token and the process crashes before the credential-store update, the user logs
in again. Do not weaken one-time rotation for this recovery case.

## 4. Implementation

### Java

1. Add v2 refresh-token Redis keys and per-user sets.
2. Add one Lua-backed atomic rotate operation.
3. Change refresh to use rotation and stable 401/503 errors.
4. Add refresh-token revocation.
5. Make logout-all remove access and v2 refresh tokens.
6. Propagate auth-state Redis failures and prevent partial token issuance.
7. Preserve non-auth product behavior.

### Python

1. Add `keyring` and regenerate the lock.
2. Add `auth login/status/logout` and `serve-stdio/serve-http` commands.
3. Add one `keyring` credential helper that accepts only the supported native secure backends.
4. Add one in-memory local token manager with startup refresh, `asyncio.Lock`, and single retry.
5. Let `ReadingMcpGateway` accept one async token-provider callable; stdio uses the local token
   manager and HTTP uses the current request bearer.
6. Add MCP SDK stdio transport using the existing low-level `Server` and five Tool definitions.
7. Add a 1 MiB pre-parse stdio frame bound.

Do not create another Tool dispatcher, schema registry, credential server, or generic auth framework.

## 5. Delivery

### Phase 0. Java Refresh Safety

Implement Java rotation and revocation first.

**Gate:** exactly one concurrent refresh succeeds; replay fails; legacy refresh tokens fail; Redis
outage returns 503 without consuming state; logout and logout-all revoke v2 credentials; existing
product and Reading tests pass.

### Phase 1. Local Login CLI

Add native credential-store login, status, and logout.

**Gate:** password/access token never persist or enter logs; unsupported or insecure credential
backends fail closed; macOS Keychain, Windows Credential Manager, and Linux Secret Service contract
tests pass; GUEST is rejected; login cannot overwrite existing credentials; failed server revocation
keeps the stored token.

### Phase 2. Stdio And Automatic Refresh

Add stdio and the in-memory token manager.

**Gate:** initialize/list/all five Tools pass through an MCP stdio client; concurrent 401 calls cause
one refresh; retry occurs once; Java outage keeps credentials; oversized frames fail before parsing;
HTTP tests remain unchanged.

### Phase 3. Full Gate And Cutover

Run:

- Java full regression;
- Harness full regression;
- standalone MCP HTTP and stdio tests;
- clean locked install;
- PaperLoom Reading benchmark gate;
- real USER login, restart, refresh, and five-Tool real-PDF smoke;
- unchanged Source Quote count;
- refresh replay, revoke, logout-all, Redis outage, malformed token, and 1 MiB stdio tests;
- log scan for password, access token, refresh token, provider payload, and Paper content.

Only after all gates pass may Quick Start switch to stdio login setup.

## 6. Configuration

The only supported runtime setting is:

```text
PAPERLOOM_PUBLIC_API_BASE_URL
```

HTTP is allowed only for loopback hosts; all other Java URLs require verified HTTPS. Login and refresh
do not follow redirects or use proxy environment variables. No password or token environment variable
is supported.

## 7. Deferred

- headless Linux without Secret Service;
- multiple local profiles or concurrent stdio processes;
- remote/shared MCP serving;
- OAuth/PKCE, Authorization Server, DCR, CIMD, consent, and introspection;
- plaintext credential fallback;
- frontend, Field Extraction, Harness, Evidence, Source Quote, or Reading Tool changes.

## 8. Commit Sequence

```text
1. fix(auth): rotate and revoke refresh tokens atomically
2. feat(mcp): add native credential-store login commands
3. feat(mcp): add stdio transport and automatic refresh
4. test(mcp): cover local auth, stdio, replay, and regression gates
5. docs(mcp): document one-time login and stdio setup
```

Each commit runs changed-surface tests. No Python credential work starts before the Java gate, and no
Quick Start cutover occurs before the full Phase 3 gate passes.
