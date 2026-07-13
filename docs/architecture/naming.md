# PaperLoom Naming Contract

PaperLoom uses two deliberate identities:

- **Folio** is the user-facing research-paper reading product.
- **PaperLoom** is the repository, backend platform, runtime, and engineering identity.

Canonical technical names:

| Surface | Name |
| --- | --- |
| Repository | `CHZarles/paperloom` |
| Java package | `io.github.chzarles.paperloom` |
| Maven artifact | `io.github.chzarles:paperloom-server` |
| Spring application | `paperloom` |
| Main class | `PaperLoomApplication` |
| MySQL schema | `paperloom` |
| Docker resources | `paperloom-*` |
| Environment prefix | `PAPERLOOM_*` |

The names used by the original upstream project are legacy identifiers. They may appear only in
the provenance notice, archived upstream material, immutable Git history, or an explicitly
temporary migration input supplied by an operator. New code, configuration, tests, and active
documentation must use the canonical names above.
