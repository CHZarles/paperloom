# Reading MCP OAuth/PKCE Introduction Plan

Date: 2026-08-25

Status: Proposed. Phase 0 compatibility evidence is required before production implementation.

This plan adds browser-based OAuth authorization to the standalone Reading MCP. It does not change
the five Reading Tools or authorize Field Extraction. The implemented
`standalone-reading-mcp-spec-2026-08-23.md` remains authoritative until the OAuth cutover gate passes.

## 1. Goal

A user connects an OAuth-capable MCP client, completes PaperLoom login and consent in a browser once,
and then uses the five Reading Tools without copying or refreshing JWTs manually.

```text
MCP client
  -> OAuth discovery + browser authorization code flow with PKCE
  -> PaperLoom Java Authorization Server
  -> opaque access/refresh tokens
  -> standalone paperloom_mcp Protected Resource
  -> Java /api/v1/reading/**
  -> existing User, role, Paper ACL, Current Model, and quota checks
```

Success means:

- no username, password, authorization code, verifier, access token, or refresh token enters Tool
  arguments or logs;
- the client opens the browser and refreshes tokens automatically;
- Python owns MCP transport validation only; Java remains the identity and policy authority;
- MCP remains stateless for Papers, conversations, disclosure, Evidence, and Source Quotes.

## 2. Decisions

### D1. Java Is The Authorization Server

PaperLoom Java owns users, passwords, roles, authorization, token issuance, refresh, revocation, and
introspection. `paperloom_mcp` MUST NOT expose OAuth authorization, token, registration, login, or
consent endpoints.

Use Spring Authorization Server. Do not implement OAuth protocol parsing or PKCE manually.

### D2. Use Opaque Access Tokens And Introspection

OAuth access tokens use Spring's reference-token format. Python implements the MCP SDK `TokenVerifier`
by calling Java's RFC 7662 introspection endpoint.

The Java Reading resource chain validates the same reference token against its in-process
`OAuth2AuthorizationService`; it MUST NOT call its own HTTP introspection endpoint.

This plan explicitly rejects combining JWT/JWKS validation, introspection, and Redis blacklists.
Java owns token state and revocation through one `OAuth2AuthorizationService`.

Initial token policy:

| Item | Policy |
| --- | --- |
| Authorization code | single-use, 2 minutes |
| PKCE | `S256` required |
| Access token | opaque, 10 minutes |
| Refresh token | 30 days |
| Refresh reuse | disabled; rotate on every refresh |
| MCP scope | `paperloom.read` only |

### D3. Split Security Into Three Ordered Chains

```text
@Order(1) Authorization Server
  /.well-known/oauth-authorization-server
  /oauth2/**
  /login and consent
  browser session + CSRF

@Order(2) Reading OAuth Resource Server
  /api/v1/reading/**
  opaque bearer token + paperloom.read
  stateless

@Order(3) Existing Product API
  all remaining routes
  current legacy JWT behavior
  stateless
```

The legacy `JwtAuthenticationFilter` MUST NOT process `/api/v1/reading/**` after cutover. OAuth and
legacy tokens MUST NOT compete on the same Reading request.

### D4. Start With One Static Public Client

Phase 1 supports one named target MCP client with:

- pre-registered `client_id`;
- `token_endpoint_auth_method=none`;
- authorization code and refresh token grants only;
- one exact HTTPS or loopback redirect URI;
- PKCE `S256` required;
- `paperloom.read` as the only permitted scope.

The exact target client, client ID, and redirect URI are Phase 0 outputs. MCP SDK
`OAuthClientProvider` is the conformance client but does not replace a real desktop-client check.

CIMD and Dynamic Client Registration are deferred. They require a separate threat model covering
metadata fetch SSRF, redirect validation, rate limits, registration persistence, expiry, and abuse.

### D5. Use One Canonical Resource Identifier

Local development uses exactly:

```text
http://127.0.0.1:8090/mcp
```

`localhost` and `::1` are not alternate OAuth resource identifiers. A remote deployment uses one
configured external HTTPS URL. The same exact resource value must appear in:

- RFC 9728 Protected Resource Metadata;
- authorization and token requests;
- authorization storage;
- introspection output;
- Python token verification.

Phase 0 must prove that the selected Spring Authorization Server version correctly preserves and
validates the RFC 8707 `resource` parameter. If extension code is required, it is part of the Java
authorization module, not Python.

### D6. Reuse Existing Users, Add OAuth Persistence

Browser login reuses the existing `UserRepository`, `CustomUserDetailsService`, and BCrypt password
hashes. GUEST cannot authorize `paperloom.read`; USER and ADMIN may authorize it, but ADMIN still does
not bypass Paper ACL.

Registered clients, authorization codes, access tokens, refresh tokens, and consent require durable
JDBC persistence. Apply Spring Authorization Server's schema through a versioned SQL migration. Do
not rely on Hibernate `ddl-auto=update` or in-memory authorization storage outside tests.

The migration mechanism and rollback SQL are Phase 0 deliverables because the repository does not
currently have Flyway or Liquibase.

### D7. Keep The Initial Browser UI Server-Side

The first implementation uses Spring-hosted login and consent pages. It does not modify Folio.
Existing `/api/v1/users/login` remains the legacy product JWT endpoint and is not reused as an OAuth
browser session.

Trusted PaperLoom-owned clients may be pre-approved. Any future dynamically registered client must
show consent.

### D8. Cut Over, Do Not Maintain A Permanent Dual Stack

OAuth endpoints may be deployed while the old MCP Bearer flow remains active. The actual cutover is
one coordinated release that switches both:

- `/api/v1/reading/**` to the OAuth resource chain; and
- `paperloom_mcp` to OAuth discovery and introspection.

Rollback redeploys the previous release. Do not keep legacy and OAuth token acceptance on Reading
endpoints indefinitely and do not infer token type from token shape.

Phase 2 and Phase 3 production commits remain on one delivery branch and MUST NOT be deployed
independently. The branch merges only after the Phase 4 cutover gate passes.

## 3. Protocol Flow

```text
Client -> POST /mcp without token
MCP -> 401 WWW-Authenticate: Bearer resource_metadata="<PRM URL>"
Client -> GET /.well-known/oauth-protected-resource/mcp
MCP -> resource + authorization_servers + paperloom.read
Client -> GET Java /.well-known/oauth-authorization-server
Java -> authorize/token/introspection/revocation metadata
Client -> generate state + code_verifier + S256 code_challenge
Client -> open browser /oauth2/authorize?...&resource=<MCP URL>
User -> login + consent
Java -> redirect_uri?code=<single-use code>&state=<same state>
Client -> POST /oauth2/token with code + code_verifier + resource
Java -> opaque access token + rotating refresh token
Client -> POST /mcp with Bearer access token
MCP -> Java /oauth2/introspect using resource-server client credentials
Java -> active + client_id + sub + scope + exp + resource
MCP -> forward the same access token to /api/v1/reading/**
Java -> validate token again, derive User, apply ACL/Current Model/quota
```

## 4. Delivery Phases

### Phase 0. Compatibility And Persistence Spike

No production behavior changes in this phase.

Prove with disposable code or tests:

1. Name the primary real MCP client and its exact callback behavior.
2. Complete discovery and PKCE using MCP SDK `OAuthClientProvider`.
3. Complete the same browser flow with the real target client.
4. Verify Spring Authorization Server support for opaque tokens, introspection, refresh rotation,
   static public clients, loopback redirects, and RFC 8707 resource binding.
5. Verify three ordered Security Filter Chains can coexist without changing non-Reading endpoints.
6. Select the versioned SQL migration mechanism and validate apply/rollback against MySQL and H2.

**Spike Gate:** all six checks pass and the evidence records exact versions, URLs, callback, and any
required Spring extension point. Otherwise stop and revise this plan.

### Phase 1. Authoritative Spec And ADR

After the Spike Gate:

- add an ADR for Java Authorization Server + opaque introspection;
- add an OAuth MCP Spec that supersedes only the authentication sections of the standalone MCP Spec;
- define discovery documents, endpoints, client metadata, resource, scope, token TTLs, persistence,
  error responses, logs, rollout, and rollback;
- record the target client and interoperability evidence.

No production code may merge before this phase is reviewed.

### Phase 2. Java Authorization And Resource Server

1. Add Spring Authorization Server and OAuth resource-server dependencies.
2. Add versioned OAuth schema migrations and JDBC repositories.
3. Add the Authorization Server chain with session-backed login, consent, CSRF, PKCE, token,
   introspection, refresh, and revocation.
4. Add the Reading resource chain using opaque-token validation and `paperloom.read`.
5. Keep the existing product chain and legacy JWT endpoints unchanged.
6. Bind authorization and tokens to the canonical MCP resource.
7. Recheck current USER/ADMIN role during authorization and refresh.
8. Preserve `ReadingQueryAudit`, Paper ACL, Current Model, quota, response bounds, and zero Source
   Quote writes.

**Java Gate:** browser authorization, refresh, revocation, role changes, ACL, and all existing Java
tests pass before Python changes begin.

### Phase 3. Python Protected Resource

1. Add RFC 9728 metadata at:

   ```text
   /.well-known/oauth-protected-resource/mcp
   ```

2. Return `WWW-Authenticate` with `resource_metadata` on missing or invalid bearer tokens.
3. Use the MCP SDK's `BearerAuthBackend`, `AuthContextMiddleware`, and `RequireAuthMiddleware`.
4. Implement one Java-introspection `TokenVerifier` using deployment-provided confidential client
   credentials.
5. Require `paperloom.read` and exact resource match before MCP protocol handling.
6. Use `get_access_token().token` to forward the opaque token unchanged to Java.
7. Remove the custom Bearer parser and `_bearer_token` ContextVar; retain request-size and transport
   security middleware.
8. Do not add token caching in the first implementation.

Protected Resource Metadata remains unauthenticated. `/mcp` remains authenticated and loopback-only
until a separate HTTPS deployment plan is approved.

### Phase 4. Interoperability, Security, And Cutover

Run automated and live checks for:

- Protected Resource and Authorization Server metadata discovery;
- browser login and consent;
- PKCE success and wrong verifier;
- state mismatch and illegal redirect URI;
- resource mismatch and scope mismatch;
- authorization-code expiry and replay;
- access-token expiry;
- refresh rotation and refresh replay;
- revocation and inactive introspection;
- GUEST rejection, USER/ADMIN permission, and ADMIN-no-ACL-bypass;
- malformed tokens, missing tokens, and insufficient scope;
- no token, code, verifier, password, provider payload, or Paper content in logs;
- non-Reading legacy JWT behavior unchanged;
- MCP initialize/list/call after automatic refresh;
- real PDF five-Tool smoke and unchanged Source Quote count.

Only after these checks pass may the coordinated OAuth cutover ship.

## 5. Required Configuration

Names are provisional until the OAuth Spec is approved:

```text
PAPERLOOM_OAUTH_ISSUER
PAPERLOOM_MCP_RESOURCE_URL
PAPERLOOM_MCP_PUBLIC_CLIENT_ID
PAPERLOOM_MCP_REDIRECT_URI
PAPERLOOM_MCP_INTROSPECTION_CLIENT_ID
PAPERLOOM_MCP_INTROSPECTION_CLIENT_SECRET
```

Secrets must be deployment-injected, never committed or returned to the browser. The desktop public
client has no client secret. The introspection credential belongs only to the deployed Python
resource server.

## 6. Explicitly Deferred

- Dynamic Client Registration;
- Client ID Metadata Documents;
- Folio OAuth UI;
- external identity providers or social login;
- JWT/JWKS access-token validation in Python;
- token introspection caching;
- remote MCP exposure;
- new MCP scopes or write Tools;
- frontend, Field Extraction, Harness, Evidence, or Source Quote changes.

## 7. Commit Sequence

```text
1. docs(auth): record OAuth compatibility spike and decisions
2. docs(auth): specify Reading MCP OAuth and PKCE
3. feat(auth): add Java authorization and Reading resource chains
4. feat(mcp): add OAuth protected-resource discovery and introspection
5. test(auth): cover refresh, revocation, client interoperability, and ACL
6. docs(auth): document browser login, rollout, and rollback
```

Each commit runs its changed-surface tests. No cutover commit may merge until the full Java, Harness,
standalone MCP, browser interoperability, and real-PDF smoke gates are green.
