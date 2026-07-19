# Paper Access And Retrieval Lifecycle Simplification Proposal

Date: 2026-07-16

Status: Implemented for the current development stage.

## Executive Summary

PaperLoom should optimize the current development phase for a small, explicit state space rather than
for online migration or zero-downtime index replacement.

The proposed design makes five simplifying choices:

1. A PDF content hash, `paper_id`, identifies one canonical paper and one canonical Reading Model.
2. Every ordinary upload is private and adds the paper to the uploader's personal library.
3. Only an administrator can upload a paper into an administrator library and publish that paper into
   the global library.
4. A paper is searchable only after Parser, Current Reading Model, and the Qdrant candidate index have
   all completed successfully.
5. Each paper has one Qdrant point set. Rebuild deletes that set first, blocks retrieval for the paper,
   writes and verifies the replacement, and then marks the paper searchable again.

This proposal deliberately rejects organization-scoped paper sharing, user-controlled public uploads,
READY-state user reparsing, generation-based online index switching, parallel full rebuilds, and
retrieval fallback when Qdrant is unavailable.

The important domain separation is:

```text
Canonical paper content       keyed globally by paper_id
User library membership       keyed by user_id + paper_id
Global publication            keyed by paper_id, administrator-controlled
Searchability                 derived from Current Reading Model + Qdrant index state
```

The existing repository already shares parser artifacts, Reading Models, MinIO objects, and Qdrant
points by content-hash `paper_id`. It also already permits multiple `file_upload` rows for the same
paper, one per user. This proposal preserves that useful physical sharing while making access and
index lifecycle rules explicit.

## Relationship To The Existing Qdrant Proposal

This proposal keeps the major boundaries established by
[Qdrant Retrieval Plane And Elasticsearch Retirement](qdrant-retrieval-plane-and-elasticsearch-retirement-proposal-2026-07-15.md):

- MySQL Current Reading Model remains canonical truth;
- Qdrant remains a rebuildable Candidate Index, not an Evidence source;
- Java owns authorization, embedding, index lifecycle, candidate validation, and exact reads;
- Python receives a locked paper scope and does not manage Qdrant;
- Qdrant failures do not silently fall back to a different retrieval method.

It supersedes only these lifecycle and access choices from that proposal:

- active `index_generation` switching is replaced by one point set per paper;
- an old index does not continue serving while a replacement is built;
- upload-supplied `org_tag` and `is_public` no longer determine paper access;
- ordinary paper owners cannot invoke a READY-state reparse or maintenance rebuild;
- full rebuild becomes a globally serialized maintenance operation.

## 1. Decision Record

| Question | Decision | Immediate consequence |
| --- | --- | --- |
| Can an incompletely uploaded or processed paper be searched? | No | Searchability is fail-closed until Parser, Reading Model, and Qdrant are all ready |
| Can an ordinary user reparse a READY paper? | No | The user path contains only first processing and failed-initial-processing retry |
| Can Qdrant candidates be rebuilt? | Yes, by administrators only | Rebuild is a maintenance command separate from parsing |
| Can a paper be searched during its Qdrant rebuild? | No | The paper is non-searchable from rebuild claim until verified completion |
| Are multiple index generations retained? | No | Rebuild deletes the paper's current points before writing replacement points |
| Can the same paper rebuild concurrently? | No | A MySQL compare-and-set claim serializes work by `paper_id` across replicas |
| How does full rebuild run? | One global job, papers processed sequentially | The full job reuses the single-paper build implementation |
| What happens after rebuild failure? | The paper remains unavailable | Error state is durable and retry starts from canonical MySQL data |
| Does retrieval fall back when Qdrant is unavailable? | No | The caller receives a typed availability error, never silent empty results |
| Who can make a paper globally visible? | Administrators only | Ordinary uploads are always private |
| What can an ordinary user see? | Global papers plus papers in their personal library | Organization tags are not part of the paper access contract |

## 2. Goals

- Make paper visibility understandable from two facts: personal membership or global publication.
- Keep one canonical parser and index lifecycle per content-hash `paper_id`.
- Prevent a user from exposing content globally through an upload request parameter.
- Prevent a user from mutating a READY canonical Reading Model shared with other users.
- Make the searchability predicate authoritative, durable, and reusable by every retrieval entry point.
- Make Qdrant rebuild behavior deterministic and easy to recover after partial failure.
- Serialize per-paper and full-corpus maintenance without holding a database transaction during
  embedding or Qdrant I/O.
- Preserve exact MySQL reads and current Evidence identity.
- Provide an implementation sequence that may invalidate and rebuild development data instead of
  maintaining old and new index formats simultaneously.

## 3. Non-Goals

- Organization, team, or tenant paper spaces.
- User-to-user paper sharing.
- Moderated user publication or a publication request workflow.
- User-triggered reparse of a READY Reading Model.
- Multiple active Reading Models for one paper.
- Qdrant collection aliases, blue/green collections, or per-paper index generations.
- Serving an old Qdrant index while a new one is built.
- Online schema migration without a retrieval interruption.
- Parallel full rebuild in the first implementation.
- MySQL keyword search, in-memory BM25, or another vector database as a production fallback.
- Moving all canonical paper metadata out of `file_upload` in the first implementation.

## 4. Current System And Gaps

### 4.1 Useful Existing Shape

`file_upload` has a unique key on `(file_md5, user_id)`, while canonical artifacts are keyed only by
`paper_id`. Multiple users can therefore hold references to the same PDF while the system stores one
merged object, one parser projection, one Current Reading Model, and one Qdrant corpus projection.

Deletion already removes only the requester's `file_upload` row and deletes physical resources after
the final row disappears. This is close to the intended personal-library membership model.

The current searchable-paper check also already requires:

- a Current `READING_MODEL_READY` row;
- `retrieval_index_status = READY`;
- a non-empty embedding contract;
- a positive indexed-location count.

These behaviors should be retained and made the only product readiness authority.

### 4.2 Access Is Coupled To Upload Metadata

[`PaperUploadController`](../../../src/main/java/io/github/chzarles/paperloom/controller/PaperUploadController.java)
currently accepts `orgTag` and `isPublic` directly from the upload request. These values are copied
through parser artifacts, Reading Elements, locations, and Qdrant payloads.

[`PaperRepository`](../../../src/main/java/io/github/chzarles/paperloom/repository/PaperRepository.java)
currently defines access as owner, public, or matching organization tag. This is broader than the
new rule and makes organization membership part of every paper query.

The target system must instead authorize a paper from authoritative MySQL membership/publication
records before constructing the Qdrant paper filter. Qdrant access payloads are not authoritative and
should eventually be removed.

### 4.3 Reindex Currently Means Reparse

[`PaperController.reindexPaper`](../../../src/main/java/io/github/chzarles/paperloom/controller/PaperController.java)
currently permits either the owner or an administrator to invoke reindexing.

[`PaperService.reindexPaper`](../../../src/main/java/io/github/chzarles/paperloom/service/PaperService.java)
then deletes parser artifacts and visual assets, reruns Parser, replaces the Reading Model, and finally
rebuilds Qdrant. This conflates three commands:

```text
retry failed initial processing
reparse canonical paper content
rebuild Qdrant candidate projection
```

The target design separates them. V1 exposes the first and third commands only. READY-state reparse
has no ordinary-user route and no administrator route until a separate requirement is accepted.

### 4.4 Qdrant Currently Uses Generations

[`ReadingModelQdrantIndexService`](../../../src/main/java/io/github/chzarles/paperloom/service/ReadingModelQdrantIndexService.java)
currently writes a new `index_generation`, verifies it, atomically activates it in MySQL, and then
deletes the previous generation. This preserves old-index availability but requires generation-aware
point IDs, filters, compare-and-set activation, and cleanup behavior.

The target lifecycle does not need that machinery. A rebuilding paper is unavailable, so the module
can delete all points for the paper before writing deterministic replacement points.

### 4.5 Full Rebuild Is Sequential But Not Globally Claimed

[`QdrantReadingModelReindexService`](../../../src/main/java/io/github/chzarles/paperloom/service/QdrantReadingModelReindexService.java)
already iterates current READY models in paper order, but two callers can start two full jobs at the
same time. Per-paper generation activation prevents some corruption, but it does not prevent duplicate
embedding work or provide one durable maintenance-job state.

## 5. Domain Model

### 5.1 Canonical Paper

A canonical paper is identified by the content hash `paper_id`. It owns the shared physical and
derived data:

```text
merged PDF object
parser artifacts
Current Reading Model
pages, sections, Reading Elements, locations, and visual assets
Qdrant candidate points
retrieval index state
```

Canonical paper data is not owned exclusively by one `file_upload` row. A user-space entry grants
access to the shared canonical content; it does not create a separate parser or Qdrant copy.

### 5.2 User Library Entry

The existing `file_upload` row becomes the V1 user-library entry as well as the upload record.

Its access meaning is limited to:

```text
file_upload(user_id, file_md5) exists
=> user_id may access canonical paper file_md5
```

`file_upload.org_tag` and `file_upload.is_public` are compatibility columns during migration. New
writes force them to `NULL` and `FALSE`; no authorization query may depend on them after cutover.

The per-row upload and vectorization fields may continue driving UI progress during V1, but they do
not determine canonical searchability.

### 5.3 Global Publication

Global visibility is a separate administrator-controlled record:

```sql
CREATE TABLE paper_publications (
    paper_id VARCHAR(32) NOT NULL,
    published_by VARCHAR(64) NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (paper_id),
    INDEX idx_paper_publications_published_by (published_by)
);
```

A publication does not copy or rebuild paper data. It makes the canonical `paper_id` visible to all
authenticated users.

Publication invariants:

- only an administrator may insert or delete the row;
- the publishing administrator must have their own `file_upload` entry for the paper;
- the canonical paper must already have a Current READY Reading Model and READY retrieval index;
- at least one `file_upload` row must exist for the paper;
- the last `file_upload` row cannot be deleted while a publication exists;
- publishing and unpublishing do not invoke Parser or Qdrant;
- publishing is idempotent;
- unpublishing leaves personal-library memberships untouched.

An administrator upload follows the same private ingestion path as any other upload. The admin then
publishes the paper explicitly after it is searchable. Upload and publication therefore remain
separate commands even if the frontend presents them as one operator workflow.

### 5.4 Access Rule

For an authenticated user:

```text
accessible(paper_id, requester_user_id) =
    exists file_upload where file_md5 = paper_id and user_id = requester_user_id
    OR exists paper_publications where paper_id = paper_id
```

Administrators use the same rule for normal product browsing. Administrator maintenance commands may
target any canonical paper, but that operational authority does not automatically publish a user's
private paper.

### 5.5 Visible Versus Searchable

Visibility and searchability are different:

- a personal-library entry may be visible while initial processing is running or failed;
- a global publication is created only after the paper is searchable;
- a published paper remains listed during administrator maintenance but is marked temporarily
  unavailable;
- retrieval tools accept a paper only when it is both accessible and searchable.

## 6. Authoritative Searchability Contract

Introduce one deep module, represented by the existing `PaperSearchabilityService`, whose interface
answers whether a paper may participate in any product retrieval operation.

The authoritative predicate is:

```text
searchable(paper_id) =
    current Reading Model exists
    AND model_status = READING_MODEL_READY
    AND retrieval_index_status = READY
    AND retrieval_embedding_contract = active configured contract
    AND retrieval_indexed_location_count > 0
```

The following facts are explicitly insufficient:

- upload merge completed;
- Parser artifact exists;
- `file_upload.vectorization_status = COMPLETED` by itself;
- Qdrant contains one or more points;
- Reading Model is READY while the index is BUILDING, REBUILDING, or FAILED.

Every product path must use the same predicate:

- paper-library selectable/searchable flags;
- paper candidate search;
- identity resolution;
- reading-location search;
- exact location reads;
- conversation scope creation and reopening;
- global publication precondition.

V1 does not permit exact MySQL reads from a non-searchable paper even if the caller already knows a
`location_ref`. This deliberately keeps one paper-level availability rule.

## 7. Retrieval Index State Machine

Replace the two-value `READY`/`UNAVAILABLE` enum with:

```text
PENDING       Current Reading Model is not yet claimed for first index build
BUILDING      First Qdrant index build is running
READY         Verified index is available for retrieval
REBUILDING    Administrator maintenance rebuild is running
FAILED        The latest build or rebuild failed; retrieval remains disabled
```

Allowed transitions:

```text
PENDING -> BUILDING -> READY
PENDING -> BUILDING -> FAILED
FAILED  -> BUILDING -> READY       failed initial index retry
READY   -> REBUILDING -> READY
READY   -> REBUILDING -> FAILED
FAILED  -> REBUILDING -> READY     administrator maintenance retry
```

Parser or Reading Model failure is recorded in the existing initial-processing state. An index state
exists on the Current Reading Model once that model has been created.

Add these retrieval fields to `paper_reading_models`:

```text
retrieval_index_status
retrieval_index_job_id
retrieval_embedding_contract
retrieval_indexed_location_count
retrieval_index_started_at
retrieval_indexed_at
retrieval_index_error_type
retrieval_index_error_message
```

Remove after cutover:

```text
retrieval_index_generation
```

The Reading Model's `failure_reason` remains about Reading Model construction. Qdrant failures use the
retrieval-specific error fields and must not change `model_status` or `is_current`.

## 8. Module Interfaces

### 8.1 Paper Access Policy

Create one module that owns all product paper access decisions. Its interface should remain small:

```java
Set<String> accessiblePaperIds(String userId, Collection<String> requestedPaperIds);
boolean canAccess(String userId, String paperId);
```

Its implementation reads `file_upload` and `paper_publications`. Callers do not reconstruct owner,
public, or organization logic independently.

The existing `PaperRepository` access queries and paper-specific logic in
`OrgTagAuthorizationFilter` should be replaced by this module. Organization tags may remain for other
product domains, but they leave the paper access interface.

### 8.2 Paper Publication

The publication module has two administrator-only commands:

```java
PublicationResult publish(String paperId, String administratorId);
void unpublish(String paperId, String administratorId);
```

It checks administrator role, an administrator-owned library entry, paper existence, authoritative
searchability, and idempotency. It does not call Parser, Embedding, or Qdrant.

### 8.3 Retrieval Index Lifecycle

The retrieval lifecycle module owns all index state transitions and Qdrant mutations:

```java
IndexResult buildInitial(String paperId, String requesterId);
IndexResult retryFailedInitialIndex(String paperId, String requesterId);
IndexResult rebuildOne(String paperId, String administratorId);
FullRebuildResult rebuildAll(String administratorId);
void deleteCanonicalIndex(String paperId);
```

The implementation may share one private `buildClaimedPaper` function. Callers cannot directly set
index state, create point IDs, delete points, or mark a paper READY.

The interface deliberately contains no parser option, generation option, online-switch option,
parallelism option, or fallback option.

## 9. Workflows

### 9.1 Ordinary User Upload

```text
1. Authenticate user.
2. Ignore or reject client-supplied orgTag/isPublic fields.
3. Create or reuse file_upload(user_id, paper_id) with is_public=false and org_tag=null.
4. If canonical paper is already searchable:
     add membership only; do not parse or index again.
5. If canonical processing is already running:
     add membership only; expose the shared processing state.
6. If canonical paper is absent or failed before Reading Model completion:
     enqueue initial processing keyed by paper_id.
7. Parser builds and activates the Current Reading Model.
8. Retrieval lifecycle claims PENDING -> BUILDING.
9. Build, verify, and mark READY.
```

The paper may appear in the user's library immediately, but retrieval and conversation scope selection
remain disabled until step 9.

### 9.2 Failed Initial Processing Retry

An ordinary user may retry only when all conditions hold:

- the user has a `file_upload` entry for the paper;
- the canonical paper is not searchable;
- the latest initial-processing state is FAILED;
- no initial-processing or index job already owns the paper.

Retry behavior is stage-aware:

- if no Current READY Reading Model exists, rerun Parser and Reading Model construction;
- if a Current READY Reading Model exists but the index is FAILED, retry only Qdrant indexing;
- if the paper is already searchable, return `PAPER_ALREADY_READY` and do not mutate it.

This is not a general reparse command.

### 9.3 Administrator Publication

```text
1. Administrator uploads the paper into their own administrator library.
2. Initial processing reaches authoritative searchable state.
3. POST the publication command.
4. Insert paper_publications(paper_id, published_by) if absent.
5. The paper becomes visible through the global library query.
```

Publishing a non-ready paper returns `PAPER_NOT_READY`. There is no pending-publication state in V1.

### 9.4 Single-Paper Qdrant Rebuild

```text
1. Require administrator role.
2. Load Current READING_MODEL_READY row.
3. Generate job_id.
4. Atomically claim READY or FAILED -> REBUILDING with job_id.
5. Delete every Qdrant point with paper_id.
6. Build indexed locations from the unchanged Current Reading Model.
7. Generate embeddings using the active embedding contract.
8. Upsert deterministic replacement points in bounded batches.
9. Count points by paper_id + model_version and require the exact expected count.
10. Recheck that the same Reading Model is still current.
11. Atomically finalize REBUILDING -> READY where retrieval_index_job_id = job_id.
12. Clear retrieval error fields and record contract, count, and indexed_at.
```

If any step after claim fails:

```text
- best-effort delete partial points for paper_id;
- atomically finalize REBUILDING -> FAILED for the same job_id;
- preserve Current Reading Model and MySQL source rows;
- record error type and bounded error message;
- return an explicit failure to the administrator.
```

Retry always deletes by `paper_id` before writing, so it is idempotent even if partial Qdrant points
survived a process crash.

### 9.5 Full Qdrant Rebuild

Add one singleton MySQL control row:

```sql
CREATE TABLE paper_retrieval_control (
    control_name VARCHAR(64) NOT NULL,
    full_rebuild_status VARCHAR(32) NOT NULL,
    job_id VARCHAR(64) DEFAULT NULL,
    requested_by VARCHAR(64) DEFAULT NULL,
    target_embedding_contract VARCHAR(255) DEFAULT NULL,
    snapshot_paper_count INT NOT NULL DEFAULT 0,
    completed_paper_count INT NOT NULL DEFAULT 0,
    failed_paper_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    last_error VARCHAR(1000) DEFAULT NULL,
    PRIMARY KEY (control_name)
);

INSERT INTO paper_retrieval_control (control_name, full_rebuild_status)
VALUES ('QDRANT_FULL_REBUILD', 'IDLE');
```

The fixed row `control_name = 'QDRANT_FULL_REBUILD'` is claimed by compare-and-set. A second caller
receives `FULL_REBUILD_ALREADY_RUNNING`. Its statuses are `IDLE`, `RUNNING`, `SUCCEEDED`, and `FAILED`;
only `RUNNING` prevents a new claim.

Full rebuild algorithm:

```text
1. Administrator atomically claims the singleton control row.
2. Snapshot current READING_MODEL_READY paper IDs in ascending paper_id order.
3. Mark every target index REBUILDING with the full job_id before changing the collection.
4. Recreate or clear the Qdrant collection once.
5. Process snapshot papers sequentially through the same buildClaimedPaper implementation.
6. Update completed/failed counters after each paper.
7. Finish SUCCEEDED when every paper is READY; otherwise finish FAILED with per-paper failures retained.
```

Because this is the development-stage design, a full rebuild may make the entire corpus unavailable.
This is intentional and removes collection aliasing, mixed embedding contracts, and old/new schema
compatibility.

While the full job is RUNNING, new index mutations return `RETRIEVAL_MAINTENANCE_RUNNING`. Upload and
Parser work may finish, but their first index build remains PENDING until maintenance completes.

### 9.6 User Removal And Canonical Deletion

Ordinary deletion removes only the requester's `file_upload` entry.

After deletion:

```text
if another file_upload row exists:
    keep canonical paper and Qdrant points
else if paper_publications row exists:
    reject deletion of the last backing row, or require unpublish first
else:
    delete canonical Qdrant points, Reading Model data, parser artifacts, MinIO objects, and chunks
```

This preserves the current last-reference cleanup behavior while adding publication as an explicit
canonical reference.

## 10. Qdrant Data Contract

### 10.1 Point Identity

Remove `index_generation` from point identity. A point ID is deterministic for canonical location
identity:

```text
UUID derived from sha256(paper_id + "\n" + model_version + "\n" + location_ref)
```

Rebuild deletes all points for the paper before upsert, so point IDs do not need to distinguish build
attempts.

### 10.2 Payload

Required payload fields remain:

```text
paper_id
model_version
location_ref
location_type
page_number
page_end_number
section_path
element_types
text_hash
parser_name
parser_version
```

Remove after access cutover:

```text
owner_user_id
org_tag
is_public
index_generation
```

Authorization is already resolved into an allowed `paper_id` set before Qdrant search. Returned hits
are still validated against the Current READY Reading Model and exact MySQL locations.

### 10.3 Query Filter

Replace active generation mapping with active model mapping:

```text
paper_id -> current model_version
```

The Qdrant filter contains one or more allowed `(paper_id, model_version)` pairs. A paper without a
READY retrieval index is not included in the filter.

For an explicitly requested multi-paper search, V1 fails the request if any requested paper is not
searchable. It does not silently search a smaller scope. This keeps coverage semantics explicit.

### 10.4 Embedding Or Collection Contract Change

A change to embedding model, vector dimension, named vectors, distance function, sparse representation,
or required payload indexes is a full rebuild.

The operation:

- claims the global full-rebuild row;
- marks all snapshot papers unavailable;
- recreates the collection with the target contract;
- rebuilds sequentially;
- makes each paper READY only after exact verification.

There is no mixed-contract serving mode.

## 11. Concurrency And Atomicity

### 11.1 Per-Paper Claim

Do not hold a database row lock while generating embeddings or calling Qdrant. Claim work with a
short compare-and-set update:

```sql
UPDATE paper_reading_models
SET retrieval_index_status = :running_status,
    retrieval_index_job_id = :job_id,
    retrieval_index_started_at = CURRENT_TIMESTAMP,
    retrieval_index_error_type = NULL,
    retrieval_index_error_message = NULL
WHERE paper_id = :paper_id
  AND model_version = :model_version
  AND is_current = TRUE
  AND model_status = 'READING_MODEL_READY'
  AND retrieval_index_status IN (:allowed_source_states);
```

Exactly one replica receives update count `1`. Other callers receive `PAPER_INDEX_OPERATION_RUNNING`
or `PAPER_INDEX_STATE_CONFLICT` without performing embedding work.

Finalization also checks `job_id`, current model, running state, and target contract. The job ID is a
fencing token: a stale worker cannot mark a later attempt READY.

### 11.2 Stale Running State

If a process dies after claim, the paper remains non-searchable. An administrator retry may reclaim a
BUILDING or REBUILDING state only after a configured stale threshold and only through an explicit
maintenance action. V1 does not add an automatic watchdog.

### 11.3 Full Job Claim

The singleton control-row update is the global atomic claim. A full job owns all index mutation while
RUNNING. Single-paper rebuild and failed-index retry reject rather than wait.

### 11.4 Kafka Delivery

Kafka may redeliver an initial-processing task. Redelivery is harmless because the database claim is
authoritative. Kafka partitioning by `paper_id` is useful ordering, but it is not treated as the global
lock.

## 12. HTTP And Tool Contracts

### 12.1 Upload

Keep the existing chunk and merge workflow, but remove `orgTag` and `isPublic` from the product upload
interface. During a compatibility window, the backend ignores those fields and always writes:

```text
org_tag = NULL
is_public = FALSE
```

### 12.2 User Retry

```text
POST /api/v1/papers/{paperId}/processing/retry
```

Authorization: user has a personal-library entry, or administrator.

Allowed only for failed initial processing of a non-searchable paper.

### 12.3 Administrator Publication

```text
POST   /api/v1/admin/papers/{paperId}/publication
DELETE /api/v1/admin/papers/{paperId}/publication
```

### 12.4 Administrator Retrieval Maintenance

```text
POST /api/v1/admin/retrieval/papers/{paperId}/rebuild
POST /api/v1/admin/retrieval/rebuild-all
GET  /api/v1/admin/retrieval/rebuild-all/status
```

Remove or repurpose the current owner-accessible endpoint:

```text
POST /api/v1/papers/{paperId}/reindex
```

It must not remain as an alias for reparse.

### 12.5 Error Contract

Do not translate unavailable retrieval into an empty candidate list. Use stable error codes:

| Code | Meaning |
| --- | --- |
| `PAPER_NOT_ACCESSIBLE` | User has neither personal membership nor global access |
| `PAPER_NOT_READY` | Initial processing has not produced a searchable paper |
| `PAPER_ALREADY_READY` | User attempted failed-initial retry on a searchable paper |
| `PAPER_INDEX_REBUILDING` | Administrator maintenance currently owns the paper |
| `PAPER_INDEX_FAILED` | Latest index attempt failed |
| `PAPER_INDEX_OPERATION_RUNNING` | Another worker already claimed this paper |
| `FULL_REBUILD_ALREADY_RUNNING` | Another full job owns the singleton control row |
| `RETRIEVAL_MAINTENANCE_RUNNING` | Full rebuild blocks another index mutation |
| `RETRIEVAL_BACKEND_UNAVAILABLE` | Qdrant request or collection verification failed |
| `RETRIEVAL_CONTRACT_MISMATCH` | Active embedding or collection contract differs from the paper state |

The Java Corpus interface passes these errors to Python as structured tool failures. Python does not
switch adapters or fabricate an empty successful result.

## 13. Implementation Plan

### Phase 1: Freeze Access And Lifecycle Contracts

- Add tests expressing personal membership plus global publication access.
- Add tests proving ordinary users cannot set global visibility.
- Add tests proving only authoritative searchable papers enter retrieval scope.
- Rename task concepts in tests and documentation: initial processing, failed retry, and Qdrant rebuild.
- Record the old owner-accessible reindex endpoint as deprecated before removing it.

Exit condition: tests fail against the current broad access and reparse behavior for the intended
reasons.

### Phase 2: Add Global Publication And Central Access Policy

- Add `paper_publications` entity, repository, and administrator controller.
- Add the paper access-policy module.
- Change accessible-paper queries to personal membership or publication.
- Backfill `paper_publications` only from existing searchable `is_public=true` rows whose uploader is
  currently an administrator; report every skipped row.
- Set all `file_upload.is_public` values to `false` after backfill. Existing organization tags may
  remain stored temporarily but no longer grant paper access.
- Force ordinary and administrator uploads to private state.
- Remove paper-path dependence on organization tags.
- Update library responses to expose `PRIVATE` or `GLOBAL` source plus canonical searchability.
- Update deletion to treat publication as a canonical reference.

Exit condition: no paper becomes globally visible from upload parameters; publication is the only
global transition.

### Phase 3: Separate Initial Retry From Maintenance Rebuild

- Replace ambiguous `TASK_TYPE_REINDEX` with explicit task types.
- Make the user retry route reject READY papers.
- Resume failed Qdrant-only initial work without rerunning Parser when a Current READY model exists.
- Move single-paper rebuild under `/api/v1/admin/retrieval/**`.
- Remove parser deletion and `parseAndSave` from the Qdrant rebuild implementation.

Exit condition: ordinary users can recover failed first processing but cannot mutate READY canonical
content or invoke maintenance rebuild.

### Phase 4: Introduce Durable Index States And Claims

- Expand `PaperRetrievalIndexStatus`.
- Add job, timestamp, contract, count, and error columns.
- Replace direct entity state writes with repository compare-and-set methods.
- Add fencing checks to successful and failed finalization.
- Make `PaperSearchabilityService` enforce the new contract.
- Make every Corpus read/search path use authoritative access and searchability.

Exit condition: duplicate tasks cannot perform duplicate embedding work for one paper, and a stale
worker cannot finalize another job.

### Phase 5: Cut Over To One Qdrant Point Set

- Remove generation from point IDs and payload.
- Replace generation filters with `(paper_id, model_version)` filters.
- Change rebuild ordering to claim, delete, build, upsert, count, finalize.
- Delete partial points on failure where possible.
- Add the singleton full-rebuild control row and status endpoint.
- Stop the application retrieval path, recreate the development Qdrant collection, and run one full
  sequential rebuild.
- Remove generation activation and cleanup code after the rebuilt collection verifies successfully.

Exit condition: the collection contains no `index_generation` payload, each paper has exactly its
expected Current Model locations, and retrieval succeeds only for READY rows.

### Phase 6: Remove Compatibility Fields From The Paper Access Path

- Stop copying `user_id`, `org_tag`, and `is_public` into new canonical Reading Model rows where they
  are not needed for provenance.
- Remove the corresponding Qdrant payload indexes.
- Remove organization-aware paper repository queries and filter branches.
- Keep database columns temporarily only if another migration still reads them.
- Update architecture documentation and deployment/reset scripts.

Exit condition: paper authorization has one implementation and no runtime dependency on organization
tags or Qdrant access payloads.

## 14. Expected Code Changes

| Area | Main changes |
| --- | --- |
| `PaperUploadController`, `UploadService` | Remove client-controlled visibility and organization scope; reuse canonical processing by `paper_id` |
| `PaperRepository`, `PaperService` | Query personal membership plus publication; reference-aware deletion |
| New publication model/repository/module | Administrator publish and unpublish commands |
| `OrgTagAuthorizationFilter` | Remove paper-specific organization/public authorization branches |
| `PaperSearchabilityService` | Own the complete Current Model and retrieval-index predicate |
| `PaperProcessingTask`, `PaperProcessingConsumer` | Separate failed initial retry from Qdrant rebuild |
| `PaperRetrievalIndexStatus`, `PaperReadingModel` | Add explicit lifecycle, job, timing, contract, count, and error state |
| `PaperReadingModelRepository` | Add claim/finalize compare-and-set updates; remove generation activation |
| `ReadingModelQdrantIndexService` | Delete-before-build lifecycle and deterministic generation-free point IDs |
| `QdrantClient` | Remove generation filters/index; filter by paper and model version |
| `QdrantReadingModelReindexService` | Add global claim, snapshot, sequential core flow, durable progress |
| `PaperController`, `QdrantAdminController` | Remove owner reindex; add explicit admin maintenance routes |
| `CorpusRetrievalService` | Central access policy, authoritative searchability, typed no-fallback errors |
| `docs/databases/ddl.sql` | Publication, retrieval lifecycle, and full-rebuild control schema |

## 15. Verification Plan

### 15.1 Access And Publication

- A non-admin upload with `isPublic=true` remains private.
- A non-admin upload with an organization tag grants no other user access.
- A user sees their private paper.
- A second user does not see that private paper.
- Both users see an administrator-published paper.
- A non-admin cannot create or delete a publication.
- An administrator cannot publish a paper that is absent from their administrator library.
- Publishing a non-searchable paper fails.
- Unpublishing removes global visibility without removing personal memberships.
- The last upload row cannot disappear while the paper is published.
- Access migration backfills searchable administrator-owned public rows and does not preserve
  ordinary-user or organization-derived global access.

### 15.2 Canonical Deduplication

- Two users uploading the same content create two memberships but one canonical Reading Model.
- A second membership for an already READY paper does not invoke Parser or Embedding.
- Deleting one membership preserves canonical data for the other membership.
- Deleting the final membership of an unpublished paper removes Qdrant and canonical artifacts.

### 15.3 Searchability

- Parser running is not searchable.
- Reading Model READY with PENDING or BUILDING index is not searchable.
- READY index with missing or mismatched embedding contract is not searchable.
- READY with a zero point count is not searchable.
- Only the complete READY predicate enters conversation scope and Qdrant filters.
- Exact location reads reject a non-searchable paper.

### 15.4 User Retry And Reparse Prohibition

- A user can retry their failed initial parse.
- A user can retry a failed initial Qdrant build without rerunning Parser.
- A user cannot retry a READY paper.
- A user cannot call the administrator rebuild routes.
- No ordinary route deletes parser artifacts for a READY paper.

### 15.5 Single-Paper Rebuild

- Claim immediately makes the paper non-searchable.
- Other READY papers remain searchable.
- Rebuild deletes old points before first upsert.
- Point IDs are stable without a generation.
- Exact written count is required before READY.
- A Qdrant failure leaves FAILED state and a durable error.
- Retry cleans partial points and can return the paper to READY.
- Two simultaneous rebuild calls produce one claimant and one conflict without duplicate embeddings.
- A stale job ID cannot finalize a later job.

### 15.6 Full Rebuild

- Two simultaneous full rebuild requests produce one running job.
- The paper snapshot is stable and sorted.
- Papers are processed sequentially.
- The core single-paper build implementation is reused.
- Full collection recreation makes all snapshot papers non-searchable before deletion.
- Per-paper failures remain FAILED while later papers continue.
- Final counters equal snapshot success and failure totals.
- New index mutations are rejected while the full job is running.

### 15.7 No Fallback

- Missing Qdrant collection returns `RETRIEVAL_BACKEND_UNAVAILABLE`.
- Qdrant timeout returns an explicit error rather than an empty candidate list.
- Embedding contract mismatch returns `RETRIEVAL_CONTRACT_MISMATCH`.
- Python preserves the structured error and does not switch to the fixture/in-memory adapter.
- No Elasticsearch, MySQL keyword, or in-memory BM25 production path is invoked.

### 15.8 Real Qdrant Smoke

Run one authenticated smoke against a disposable collection:

```text
create collection
initial-build one paper
verify exact point count
search and reopen one location
rebuild same paper
verify old points were deleted before replacement
force one failed rebuild
verify paper is unavailable and error is durable
retry successfully
run full rebuild over at least two papers
delete collection
```

## 16. Acceptance Criteria

The proposal is implemented only when all statements below are true:

1. Ordinary upload requests cannot create global or organization-visible papers.
2. An authenticated user can access exactly global publications plus their personal-library entries.
3. Two users may reference one canonical `paper_id` without duplicate canonical indexing.
4. No paper is searchable until Parser, Current Reading Model, and Qdrant have all completed.
5. Ordinary users have no READY-state reparse or Qdrant maintenance command.
6. Failed initial processing remains retryable by an authorized personal-library user.
7. Qdrant rebuild consumes the existing Current READY Reading Model without invoking Parser.
8. A rebuilding or failed paper is not searchable.
9. Qdrant contains one point set per paper and no active generation protocol.
10. One database claim serializes index mutation for a `paper_id` across application replicas.
11. Only one full rebuild runs at a time, and it processes a stable paper snapshot sequentially.
12. Build failure preserves canonical MySQL data, records a durable error, and supports idempotent retry.
13. Qdrant outage or contract mismatch returns an explicit typed failure with no retrieval fallback.
14. User deletion removes membership first and deletes canonical data only after the final unpublished
    reference disappears.
15. Publication is administrator-only, requires an administrator-owned library entry, and remains
    independent from index construction.

## 17. Reconsideration Triggers

Do not add extension hooks before one of these conditions is observed:

| Current decision | Reconsider only when |
| --- | --- |
| No partial retrieval during first processing | A concrete product requirement needs incomplete-paper preview or retrieval |
| No ordinary READY reparse | Support evidence shows users need self-service repair of READY canonical content |
| Admin-only publication | A moderation and abuse-handling workflow exists for user publication requests |
| No organization paper scope | A real organization-sharing workflow is prioritized and its membership semantics are defined |
| Admin-only Qdrant rebuild | A non-administrator operational role is introduced with an explicit permission model |
| Paper unavailable during rebuild | Measured maintenance downtime violates a future availability objective |
| One point set, no generations | Per-paper zero-downtime rebuild becomes a required product property |
| One rebuild per paper | One paper is intentionally sharded into independently buildable partitions |
| Sequential full rebuild | Measured full rebuild duration exceeds the accepted maintenance window |
| Failed rebuild stays unavailable | The business requires serving a verified old index after rebuild failure |
| No Qdrant fallback | One fallback contract has matching scope, ranking, evidence identity, and reproducibility tests |
| Keep rebuild maintenance feature | The administrator entry has no calls during an agreed observation window and no planned migration use |

## 18. Risks And Controls

### `file_upload` Still Has Two Responsibilities

V1 uses it as both upload record and personal-library membership. This minimizes migration work but
leaves processing fields duplicated across users who reference the same canonical paper.

Control: searchability is read only from the Current Reading Model and retrieval state. A later schema
cleanup may introduce a canonical `papers` table without changing the access contract.

### Existing Canonical Rows Contain Access Metadata

Reading Model descendants and Qdrant points currently contain owner, organization, and public fields.

Control: stop treating them as authorization inputs first. Remove physical columns and payload fields
only after the central access policy is proven.

### Development Collection Rebuild Invalidates Existing Search

Removing generations and changing point IDs requires rebuilding the collection.

Control: this is accepted for the development stage. Mark every paper non-searchable before collection
recreation and verify every READY paper afterward.

### A Process May Die In BUILDING Or REBUILDING

The paper remains unavailable until an administrator retries.

Control: job ID and start time make the state diagnosable and safely reclaimable. Automatic recovery
is deferred until failures demonstrate a need.

### Published Paper Has No Remaining Upload Row

Without a canonical `papers` table, current display metadata still comes from `file_upload`.

Control: prevent deletion of the last upload row while publication exists. Unpublish first, then apply
normal final-reference cleanup.

## 19. Final Target State

```text
Ordinary upload
-> private file_upload membership
-> canonical first processing when needed
-> Parser
-> Current READING_MODEL_READY
-> Qdrant BUILDING
-> verified READY
-> searchable in uploader's personal library

Administrator publication
-> require canonical searchable paper
-> paper_publications row
-> searchable in every user's global library

Administrator single-paper rebuild
-> READY -> REBUILDING
-> delete paper points
-> rebuild from unchanged Current Reading Model
-> verify
-> READY or FAILED

Administrator full rebuild
-> claim singleton job
-> snapshot papers
-> mark unavailable
-> recreate collection
-> rebuild papers sequentially
-> READY or FAILED per paper

Product retrieval
-> authorize from personal membership or publication
-> require authoritative searchable state
-> Qdrant candidates
-> Current Reading Model validation
-> exact MySQL read
-> Evidence
```

This target favors explicit policy and recoverable state over availability machinery. It is the
appropriate development-stage baseline: one canonical paper, one access rule, one searchable state,
one Qdrant point set, and one maintenance path.
