# Security Policy

## Reporting a Vulnerability

Please do not disclose a suspected vulnerability in a public issue. Send a private report through
GitHub's security-advisory feature for this repository, including:

- the affected component and revision;
- reproduction steps or a minimal proof of concept;
- expected impact;
- any known mitigation.

Reports involving authorization boundaries, paper visibility, persistent references, uploaded PDFs,
model credentials, or cross-session state are especially important.

## Supported Versions

PaperLoom is currently developed from the `main` branch. Until tagged stable releases exist, security
fixes target the latest revision only.

## Operational Guidance

- Keep `.env` files and provider credentials outside version control.
- Disable administrator bootstrap after creating the initial account.
- Use trusted TLS certificates for all production storage and retrieval services.
- Treat uploaded PDFs and parser artifacts as untrusted input.
- Restrict MinIO, MySQL, Redis, Kafka, Qdrant, and the research harness to trusted networks.
- Rotate any credential immediately if it is exposed in logs, traces, screenshots, or Git history.
