# PaperLoom Session Scope Collections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build durable PaperLoom collection management and backend-owned locked conversation scopes so each chat session has a stable, auditable evidence universe.

**Architecture:** Product papers remain the only product retrieval corpus. Collections are reusable static paper-id sets, while locked conversations retrieve from immutable source-set snapshots resolved at first accepted chat message. The frontend exposes collection management in the paper library and read-only locked scope in chat; changing papers requires a new session.

**Tech Stack:** Spring Boot, JPA/Hibernate, MySQL, Redis, Elasticsearch, MinIO, Vue 3, TypeScript, Pinia, Naive UI, Vite.

---

## Confirmed Product Rules

- New sessions default to `AUTO_LIBRARY`.
- Before the first accepted user message, a session scope can be changed to a collection-derived or custom `SOURCE_SET_SNAPSHOT`.
- The backend locks scope when it accepts the first user message.
- Locked session scope cannot change. Different papers require a new session.
- Collections are product objects with static paper memberships.
- Collections may contain unsearchable papers, but chat snapshots use only searchable and accessible papers.
- Regex and metadata filters are batch-add tools, not dynamic session scopes.
- Reference focus is temporary for one outgoing message and does not mutate session scope.
- Chat source selection searches paper metadata server-side and does not search chunks.
- Existing local product runtime data can be reset. Admin and benchmark/eval corpora are preserved.

## File Structure

Create:

- `scripts/paperloom-reset-product-runtime.sh`: local/dev reset that clears product runtime data while preserving admin and `paperloom_eval`.
- `src/main/java/com/yizhaoqi/smartpai/model/PaperCollection.java`: collection metadata.
- `src/main/java/com/yizhaoqi/smartpai/model/PaperCollectionPaper.java`: collection membership.
- `src/main/java/com/yizhaoqi/smartpai/model/ConversationScopeMode.java`: `AUTO_LIBRARY`, `SOURCE_SET_SNAPSHOT`.
- `src/main/java/com/yizhaoqi/smartpai/model/ConversationScopeStatus.java`: `READY`, `DEGRADED`, `INVALID`.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperCollectionRepository.java`: collection metadata queries.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperCollectionPaperRepository.java`: membership queries.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperSearchabilityService.java`: decides whether a product paper is searchable for chat.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperCollectionService.java`: collection CRUD and membership operations.
- `src/main/java/com/yizhaoqi/smartpai/service/ConversationScopeService.java`: scope update, snapshot resolution, lock validation.
- `src/main/java/com/yizhaoqi/smartpai/controller/PaperCollectionController.java`: REST API for collections.
- `src/main/java/com/yizhaoqi/smartpai/controller/dto/CollectionRequests.java`: request records for collections.
- `src/main/java/com/yizhaoqi/smartpai/controller/dto/ConversationScopeRequests.java`: request records for scope update and source recipes.
- `src/test/java/com/yizhaoqi/smartpai/service/PaperSearchabilityServiceTest.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperCollectionServiceTest.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ConversationScopeServiceTest.java`
- `src/test/java/com/yizhaoqi/smartpai/controller/PaperCollectionControllerTest.java`
- `src/test/java/com/yizhaoqi/smartpai/controller/ConversationSessionScopeControllerTest.java`
- `frontend/src/service/api/paper-collections.ts`
- `frontend/src/views/knowledge-base/modules/collections-panel.vue`
- `frontend/src/views/chat/modules/session-scope-picker.vue`
- `frontend/src/views/chat/modules/session-scope-chip.vue`

Modify:

- `src/main/java/com/yizhaoqi/smartpai/model/ConversationSession.java`: add scope mode, lock, status, source label, recipe JSON, snapshot JSON, paper count.
- `src/main/java/com/yizhaoqi/smartpai/model/Conversation.java`: add effective-scope JSON for each message record.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperRepository.java`: add server-side accessible paper candidate search queries.
- `src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java`: support paper-level `query` and `readiness=searchable` for accessible papers.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperService.java`: expose accessible paper search page.
- `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java`: return session scope DTOs, update unlocked scope, record effective message scope.
- `src/main/java/com/yizhaoqi/smartpai/controller/ConversationSessionController.java`: add `GET/PUT /{conversationId}/scope`.
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`: resolve and lock conversation scope before harness execution.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`: accept effective scope from conversation scope service; treat reference focus separately.
- `frontend/src/typings/api.d.ts`: add collection and conversation-scope types.
- `frontend/src/store/modules/chat/index.ts`: load sessions with scope, update unlocked scope, send reference focus only.
- `frontend/src/views/chat/index.vue`: display session scope and reference panel behavior.
- `frontend/src/views/chat/modules/input-box.vue`: replace mutable source picker with locked session scope UI and reference-focus display.
- `frontend/src/views/chat/modules/chat-message.vue`: change answer source action to create a new session from sources.
- `frontend/src/views/chat/modules/conversation-sidebar.vue`: show scope summary and lock state.
- `frontend/src/views/knowledge-base/index.vue`: add `Papers` and `Collections` view switch.

## Task 1: Dev Runtime Reset Script

**Files:**
- Create: `scripts/paperloom-reset-product-runtime.sh`

- [ ] **Step 1: Write the reset script**

Create `scripts/paperloom-reset-product-runtime.sh` with these behaviors:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

if [[ "${1:-}" != "--yes-i-know-this-deletes-product-runtime" ]]; then
  echo "Refusing to reset without explicit confirmation."
  echo "Usage: scripts/paperloom-reset-product-runtime.sh --yes-i-know-this-deletes-product-runtime"
  exit 2
fi

env_value() {
  awk -F= -v key="$1" '$1 == key { print substr($0, index($0, "=") + 1) }' .env
}

ES_USER="${ELASTICSEARCH_USERNAME:-$(env_value ELASTICSEARCH_USERNAME)}"
ES_PASSWORD="${ELASTICSEARCH_PASSWORD:-$(env_value ELASTICSEARCH_PASSWORD)}"
REDIS_PASSWORD_VALUE="${SPRING_DATA_REDIS_PASSWORD:-$(env_value SPRING_DATA_REDIS_PASSWORD)}"

mysql_db() {
  docker exec -i pai_smart_mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$1"' sh "$1"
}

mysql_query() {
  docker exec pai_smart_mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$@"' sh "$@"
}

echo "Preserving: admin user and paperloom_eval benchmark corpus."
echo "Deleting: product papers, product chunks, product chat/session history, Redis runtime keys, product ES docs, product MinIO objects."

mysql_db paismart <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
DELETE FROM conversations;
DELETE FROM conversation_sessions;
DELETE FROM paper_visual_assets;
DELETE FROM paper_parser_artifacts;
DELETE FROM paper_tables;
DELETE FROM paper_figures;
DELETE FROM paper_formulas;
DELETE FROM paper_text_chunks;
DELETE FROM chunk_info;
DELETE FROM paper_processing_tasks;
DELETE FROM file_upload;
DELETE FROM users WHERE username <> 'admin';
SET FOREIGN_KEY_CHECKS=1;
SQL

curl -sS -u "${ES_USER}:${ES_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -X POST 'http://localhost:9200/paper_chunks/_delete_by_query?conflicts=proceed' \
  -d '{"query":{"match_all":{}}}' >/dev/null || true

curl -sS -u "${ES_USER}:${ES_PASSWORD}" \
  -H 'Content-Type: application/json' \
  -X POST 'http://localhost:9200/paper_search/_delete_by_query?conflicts=proceed' \
  -d '{"query":{"match_all":{}}}' >/dev/null || true

docker exec pai_smart_redis sh -lc 'redis-cli -a "$1" FLUSHDB' sh "$REDIS_PASSWORD_VALUE" >/dev/null || true

docker exec pai_smart_minio sh -lc '
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
  mc rm --recursive --force local/uploads >/dev/null || true
' || true

mysql_query paismart -N -e "
SELECT 'admin_count', COUNT(*) FROM users WHERE username='admin';
SELECT 'product_papers', COUNT(*) FROM file_upload;
SELECT 'product_chunks', COUNT(*) FROM paper_text_chunks;
SELECT 'conversations', COUNT(*) FROM conversations;
"
mysql_query paperloom_eval -N -e "
SELECT 'eval_litsearch_papers', COUNT(*) FROM eval_papers WHERE corpus='litsearch';
SELECT 'eval_qasper_papers', COUNT(*) FROM eval_papers WHERE corpus='qasper';
"
```

- [ ] **Step 2: Make the script executable**

Run:

```bash
chmod +x scripts/paperloom-reset-product-runtime.sh
```

Expected: exit code `0`.

- [ ] **Step 3: Validate dry refusal**

Run:

```bash
scripts/paperloom-reset-product-runtime.sh
```

Expected: exit code `2` and output containing `Refusing to reset`.

- [ ] **Step 4: Commit**

Run:

```bash
git add scripts/paperloom-reset-product-runtime.sh
git commit -m "chore: add product runtime reset script"
```

## Task 2: Scope And Collection Domain Model

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperCollection.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperCollectionPaper.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/ConversationScopeMode.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/ConversationScopeStatus.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/model/ConversationSession.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/model/Conversation.java`

- [ ] **Step 1: Add scope enums**

Create `ConversationScopeMode.java`:

```java
package com.yizhaoqi.smartpai.model;

public enum ConversationScopeMode {
    AUTO_LIBRARY,
    SOURCE_SET_SNAPSHOT
}
```

Create `ConversationScopeStatus.java`:

```java
package com.yizhaoqi.smartpai.model;

public enum ConversationScopeStatus {
    READY,
    DEGRADED,
    INVALID
}
```

- [ ] **Step 2: Add collection entities**

Create `PaperCollection.java`:

```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_collections", indexes = {
        @Index(name = "idx_pc_owner", columnList = "owner_user_id"),
        @Index(name = "idx_pc_org", columnList = "org_tag"),
        @Index(name = "idx_pc_visibility", columnList = "visibility")
})
public class PaperCollection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "org_tag", length = 120)
    private String orgTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility = Visibility.PRIVATE;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum Visibility {
        PRIVATE,
        ORG
    }
}
```

Create `PaperCollectionPaper.java`:

```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "paper_collection_papers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pcp_collection_paper", columnNames = {"collection_id", "paper_id"})
        },
        indexes = {
                @Index(name = "idx_pcp_collection", columnList = "collection_id"),
                @Index(name = "idx_pcp_paper", columnList = "paper_id")
        }
)
public class PaperCollectionPaper {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collection_id", nullable = false)
    private PaperCollection collection;

    @Column(name = "paper_id", nullable = false, length = 160)
    private String paperId;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Extend conversation session**

Add these fields to `ConversationSession.java`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "scope_mode", nullable = false, length = 32)
private ConversationScopeMode scopeMode = ConversationScopeMode.AUTO_LIBRARY;

@Column(name = "scope_locked", nullable = false)
private boolean scopeLocked = false;

@Enumerated(EnumType.STRING)
@Column(name = "scope_status", nullable = false, length = 32)
private ConversationScopeStatus scopeStatus = ConversationScopeStatus.READY;

@Column(name = "source_label", length = 255)
private String sourceLabel;

@Column(name = "source_recipe_json", columnDefinition = "LONGTEXT")
private String sourceRecipeJson;

@Column(name = "source_snapshot_json", columnDefinition = "LONGTEXT")
private String sourceSnapshotJson;

@Column(name = "source_paper_count")
private Integer sourcePaperCount;
```

- [ ] **Step 4: Extend conversation message record**

Add to `Conversation.java`:

```java
@Column(name = "effective_scope_json", columnDefinition = "LONGTEXT")
private String effectiveScopeJson;
```

- [ ] **Step 5: Run model compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: exit code `0`.

- [ ] **Step 6: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/model
git commit -m "feat: add collection and conversation scope models"
```

## Task 3: Paper Searchability And Candidate Search

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperSearchabilityService.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/PaperSearchabilityServiceTest.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/repository/PaperRepository.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/controller/PaperControllerContractTest.java`

- [ ] **Step 1: Write searchability tests**

Add tests proving:

```java
@Test
void completedPaperWithIndexedChunksIsSearchable() {
    Paper paper = paper("paper-a", "COMPLETED", 1);
    when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);
    assertTrue(service.isSearchable(paper));
}

@Test
void completedPaperWithoutChunksIsNotSearchable() {
    Paper paper = paper("paper-a", "COMPLETED", 1);
    when(chunkRepository.countByPaperId("paper-a")).thenReturn(0L);
    assertFalse(service.isSearchable(paper));
}

@Test
void failedPaperIsNotSearchable() {
    Paper paper = paper("paper-a", "FAILED", 0);
    when(chunkRepository.countByPaperId("paper-a")).thenReturn(12L);
    assertFalse(service.isSearchable(paper));
}
```

Use the real repository method name available on `PaperTextChunkRepository`; if it does not have
`countByPaperId`, add it there before completing this task.

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
mvn -q -Dtest=PaperSearchabilityServiceTest test
```

Expected: compile failure because `PaperSearchabilityService` does not exist.

- [ ] **Step 3: Implement searchability**

Create `PaperSearchabilityService` with:

```java
@Service
public class PaperSearchabilityService {
    private final PaperTextChunkRepository chunkRepository;

    public PaperSearchabilityService(PaperTextChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public boolean isSearchable(Paper paper) {
        if (paper == null || paper.getPaperId() == null || paper.getPaperId().isBlank()) {
            return false;
        }
        if (!"COMPLETED".equalsIgnoreCase(String.valueOf(paper.getVectorizationStatus()))
                && paper.getStatus() != 1) {
            return false;
        }
        return chunkRepository.countByPaperId(paper.getPaperId()) > 0;
    }
}
```

- [ ] **Step 4: Add server-side paper candidate query**

Add repository query methods that filter accessible papers and match metadata:

```java
@Query("""
        SELECT f FROM Paper f
        WHERE (f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false))
          AND (
            :query IS NULL OR :query = '' OR
            LOWER(f.paperTitle) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(f.originalFilename) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(f.authors) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(f.venue) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(f.doi) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(f.arxivId) LIKE LOWER(CONCAT('%', :query, '%')) OR
            LOWER(f.paperId) LIKE LOWER(CONCAT('%', :query, '%')) OR
            CAST(f.year AS string) LIKE CONCAT('%', :query, '%')
          )
        """)
Page<Paper> searchAccessiblePaperCandidates(@Param("userId") String userId,
                                             @Param("orgTagList") List<String> orgTagList,
                                             @Param("query") String query,
                                             Pageable pageable);
```

If the entity field is named `publicationYear` instead of `year`, use the actual field name from
`Paper.java`.

- [ ] **Step 5: Add controller support**

Extend `GET /api/v1/papers?scope=accessible` to accept:

```text
query=<paper metadata query>
readiness=searchable
```

For `readiness=searchable`, filter the page content through `PaperSearchabilityService`. If filtering
after paging produces short pages, document the behavior in the controller test and keep it for the
first implementation; the picker can request the next page.

- [ ] **Step 6: Add contract tests**

In `PaperControllerContractTest`, add tests:

- `accessiblePapersSupportsPaperLevelQuery`: create two accessible paper fixtures, query a term
  present only in one title or filename, call the controller with `query`, and assert the response
  contains only that paper.
- `accessiblePapersReadinessSearchableHidesUnsearchablePapers`: create one completed paper with
  chunks and one processing or failed paper, call the controller with `readiness=searchable`, and
  assert only the searchable paper is returned.
- `accessiblePapersResponseContainsNoEvalFields`: call the accessible paper endpoint and assert no
  returned row contains `evalImport`, `structuredImport`, `sourceDataset`, `evalSplit`, or `isEval`.

- [ ] **Step 7: Run tests**

Run:

```bash
mvn -q -Dtest=PaperSearchabilityServiceTest,PaperControllerContractTest test
```

Expected: exit code `0`.

- [ ] **Step 8: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/repository/PaperRepository.java src/main/java/com/yizhaoqi/smartpai/service/PaperSearchabilityService.java src/main/java/com/yizhaoqi/smartpai/service/PaperService.java src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java src/test/java/com/yizhaoqi/smartpai/service/PaperSearchabilityServiceTest.java src/test/java/com/yizhaoqi/smartpai/controller/PaperControllerContractTest.java
git commit -m "feat: search paper source candidates"
```

## Task 4: Collection Backend API

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/PaperCollectionRepository.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/PaperCollectionPaperRepository.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperCollectionService.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/controller/PaperCollectionController.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/controller/dto/CollectionRequests.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/PaperCollectionServiceTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/controller/PaperCollectionControllerTest.java`

- [ ] **Step 1: Write service tests**

Cover:

- `ownerCanCreatePrivateCollection`: create a private collection as user A and assert only user A
  sees it in the private collection list.
- `orgCollectionIsVisibleToSameOrgUser`: create an `ORG` collection for org tag `lab` and assert a
  user with `lab` in `orgTags` sees it.
- `ordinaryOrgUserCannotEditOrgCollection`: attempt to update an org collection as a non-owner
  non-admin same-org user and assert HTTP/service error `403`.
- `adminCanEditOrgCollection`: update an org collection as admin and assert the name changes.
- `addPapersStoresStaticPaperIds`: add three paper ids, change paper metadata after the add, and
  assert membership still contains the original three paper ids.

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
mvn -q -Dtest=PaperCollectionServiceTest test
```

Expected: compile failure because repositories and service do not exist.

- [ ] **Step 3: Implement repositories**

Repository methods required:

```java
List<PaperCollection> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

@Query("SELECT c FROM PaperCollection c WHERE c.visibility='ORG' AND c.orgTag IN :orgTags ORDER BY c.updatedAt DESC")
List<PaperCollection> findOrgVisibleCollections(@Param("orgTags") List<String> orgTags);

Optional<PaperCollection> findByIdAndOwnerId(Long id, Long ownerId);

List<PaperCollectionPaper> findByCollectionIdOrderByCreatedAtAsc(Long collectionId);

void deleteByCollectionIdAndPaperId(Long collectionId, String paperId);

void deleteByCollectionId(Long collectionId);
```

- [ ] **Step 4: Implement service DTO maps**

`PaperCollectionService` should return maps with:

```json
{
  "id": 12,
  "name": "Agent papers",
  "description": "Agent system reading set",
  "visibility": "PRIVATE",
  "orgTag": "default",
  "paperCount": 128,
  "searchablePaperCount": 117,
  "createdAt": "2026-06-28T12:00:00",
  "updatedAt": "2026-06-28T12:00:00"
}
```

- [ ] **Step 5: Implement controller endpoints**

Add:

```text
GET    /api/v1/paper-collections
POST   /api/v1/paper-collections
GET    /api/v1/paper-collections/{id}
PUT    /api/v1/paper-collections/{id}
DELETE /api/v1/paper-collections/{id}
POST   /api/v1/paper-collections/{id}/papers
DELETE /api/v1/paper-collections/{id}/papers/{paperId}
```

Request records:

```java
public record CreateCollectionRequest(String name, String description, String visibility, String orgTag) {}
public record UpdateCollectionRequest(String name, String description, String visibility, String orgTag) {}
public record AddCollectionPapersRequest(List<String> paperIds) {}
```

- [ ] **Step 6: Run tests**

Run:

```bash
mvn -q -Dtest=PaperCollectionServiceTest,PaperCollectionControllerTest test
```

Expected: exit code `0`.

- [ ] **Step 7: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/repository/PaperCollectionRepository.java src/main/java/com/yizhaoqi/smartpai/repository/PaperCollectionPaperRepository.java src/main/java/com/yizhaoqi/smartpai/service/PaperCollectionService.java src/main/java/com/yizhaoqi/smartpai/controller/PaperCollectionController.java src/main/java/com/yizhaoqi/smartpai/controller/dto/CollectionRequests.java src/test/java/com/yizhaoqi/smartpai/service/PaperCollectionServiceTest.java src/test/java/com/yizhaoqi/smartpai/controller/PaperCollectionControllerTest.java
git commit -m "feat: add paper collections api"
```

## Task 5: Conversation Scope API

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ConversationScopeService.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/controller/dto/ConversationScopeRequests.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ConversationScopeServiceTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/controller/ConversationSessionScopeControllerTest.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/controller/ConversationSessionController.java`

- [ ] **Step 1: Write scope service tests**

Cover:

- `newSessionDefaultsToUnlockedAutoLibrary`: create a session and assert `scopeMode=AUTO_LIBRARY`,
  `scopeLocked=false`, and `scopeStatus=READY`.
- `unlockedSessionCanUpdateToSnapshot`: update an unlocked session with two accessible searchable
  paper ids and assert `scopeMode=SOURCE_SET_SNAPSHOT`, `sourcePaperCount=2`, and snapshot JSON
  contains both ids.
- `lockedSessionRejectsScopeUpdate`: mark a session locked, call the scope update method, and assert
  `CustomException` status `409`.
- `collectionScopeResolvesSearchableAccessiblePapersOnly`: create a collection containing one
  searchable paper, one unsearchable paper, and one inaccessible paper; update session scope from
  that collection and assert only the searchable accessible paper is in the snapshot.
- `emptyResolvedCollectionScopeIsRejected`: create a collection with no searchable accessible papers
  and assert the scope update fails with HTTP/service error `400`.

- [ ] **Step 2: Implement request records**

Create:

```java
public record UpdateConversationScopeRequest(
        String scopeMode,
        String sourceLabel,
        List<Long> collectionIds,
        List<String> paperIds,
        Map<String, Object> sourceRecipe
) {}
```

- [ ] **Step 3: Implement scope service contract**

Required public methods:

```java
public Map<String, Object> defaultScope();

public Map<String, Object> updateUnlockedScope(Long userId, String conversationId, UpdateConversationScopeRequest request);

public EffectiveConversationScope resolveForChat(Long userId, String conversationId);

public EffectiveConversationScope lockForFirstMessage(Long userId, String conversationId);

public void assertReferenceFocusWithinScope(EffectiveConversationScope scope, PaperAnswerService.AnswerScope referenceFocus);
```

`EffectiveConversationScope` must expose:

```java
public record EffectiveConversationScope(
        ConversationScopeMode mode,
        ConversationScopeStatus status,
        boolean locked,
        String label,
        List<String> paperIds,
        Map<String, Object> sourceRecipe
) {}
```

- [ ] **Step 4: Add controller endpoints**

Add to `ConversationSessionController`:

```text
GET /api/v1/users/conversations/{conversationId}/scope
PUT /api/v1/users/conversations/{conversationId}/scope
```

The `PUT` endpoint must reject locked sessions with HTTP `409`.

- [ ] **Step 5: Extend session list DTO**

`ConversationService.getConversationSessions` and `createConversationSession` must include:

```json
{
  "scopeMode": "AUTO_LIBRARY",
  "scopeLocked": false,
  "scopeStatus": "READY",
  "sourceLabel": "All searchable papers",
  "sourcePaperCount": null
}
```

- [ ] **Step 6: Run tests**

Run:

```bash
mvn -q -Dtest=ConversationScopeServiceTest,ConversationSessionScopeControllerTest,ConversationServiceTest test
```

Expected: exit code `0`.

- [ ] **Step 7: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ConversationScopeService.java src/main/java/com/yizhaoqi/smartpai/controller/dto/ConversationScopeRequests.java src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java src/main/java/com/yizhaoqi/smartpai/controller/ConversationSessionController.java src/test/java/com/yizhaoqi/smartpai/service/ConversationScopeServiceTest.java src/test/java/com/yizhaoqi/smartpai/controller/ConversationSessionScopeControllerTest.java
git commit -m "feat: persist conversation source scopes"
```

## Task 6: Chat Scope Locking And Effective Scope History

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReferenceEvidenceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperAnswerServiceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ConversationServiceTest.java`

- [ ] **Step 1: Write locking tests**

Add tests proving:

- `firstAcceptedMessageLocksAutoLibraryScope`: process a first chat request for a new session and
  assert the session is saved with `scopeLocked=true` and `scopeMode=AUTO_LIBRARY`.
- `firstAcceptedMessageLocksSnapshotScope`: preconfigure an unlocked snapshot scope, process the
  first chat request, and assert the session locks with the same snapshot paper ids.
- `laterMessageUsesLockedScopeEvenWhenPayloadContainsDifferentPaperIds`: send a later request with
  conflicting payload paper ids and assert the answer service receives the locked snapshot ids.
- `referenceFocusDoesNotChangeLockedScope`: send a reference-focus request and assert the session
  scope JSON is unchanged after completion.
- `messageHistoryIncludesEffectiveScope`: record a conversation with effective scope and assert
  `ConversationService.toMessageHistory` returns `effectiveScope` on the user message.

- [ ] **Step 2: Update chat request handling**

`ChatHandler.processMessage` must:

1. Get or create the conversation.
2. Ensure the conversation session exists.
3. Ask `ConversationScopeService` to lock or resolve the conversation scope.
4. Build an answer scope from locked session paper ids plus optional reference focus.
5. Ignore or reject mutable paper ids from the frontend if they conflict with the locked scope.

- [ ] **Step 3: Separate reference focus from session scope**

The frontend payload may still use the existing `scope` shape during migration, but backend logic
must treat fields with `referenceNumber`, `conversationRecordId`, `chunkId`, or matched evidence as
reference focus. Paper id arrays must not override a locked session.

- [ ] **Step 4: Record effective scope**

Extend `ConversationService.recordConversation` to accept effective scope JSON and persist it to
`Conversation.effectiveScopeJson`.

Message history should include:

```json
{
  "effectiveScope": {
    "scopeMode": "SOURCE_SET_SNAPSHOT",
    "sourceLabel": "Agent papers",
    "paperIds": ["p1", "p2"],
    "paperCount": 2
  }
}
```

- [ ] **Step 5: Run tests**

Run:

```bash
mvn -q -Dtest=ChatHandlerReferenceEvidenceTest,ConversationServiceTest,PaperAnswerServiceTest test
```

Expected: exit code `0`.

- [ ] **Step 6: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReferenceEvidenceTest.java src/test/java/com/yizhaoqi/smartpai/service/PaperAnswerServiceTest.java src/test/java/com/yizhaoqi/smartpai/service/ConversationServiceTest.java
git commit -m "feat: lock chat retrieval scope per session"
```

## Task 7: Frontend Types And API Clients

**Files:**
- Modify: `frontend/src/typings/api.d.ts`
- Create: `frontend/src/service/api/paper-collections.ts`
- Modify: `frontend/src/store/modules/chat/index.ts`

- [ ] **Step 1: Add API types**

Add:

```ts
namespace Api {
  namespace PaperCollection {
    type Visibility = 'PRIVATE' | 'ORG';

    interface Item {
      id: number;
      name: string;
      description?: string | null;
      visibility: Visibility;
      orgTag?: string | null;
      paperCount: number;
      searchablePaperCount: number;
      createdAt?: string;
      updatedAt?: string;
    }

    interface Detail extends Item {
      papers: Paper.UploadTask[];
    }

    interface UpsertPayload {
      name: string;
      description?: string | null;
      visibility: Visibility;
      orgTag?: string | null;
    }
  }

  namespace Chat {
    type ScopeMode = 'AUTO_LIBRARY' | 'SOURCE_SET_SNAPSHOT';
    type ScopeStatus = 'READY' | 'DEGRADED' | 'INVALID';

    interface ConversationScope {
      scopeMode: ScopeMode;
      scopeLocked: boolean;
      scopeStatus: ScopeStatus;
      sourceLabel?: string | null;
      sourcePaperCount?: number | null;
      paperIds?: string[];
      sourceRecipe?: Record<string, any> | null;
    }

    interface UpdateConversationScopePayload {
      scopeMode: ScopeMode;
      sourceLabel?: string;
      collectionIds?: number[];
      paperIds?: string[];
      sourceRecipe?: Record<string, any>;
    }
  }
}
```

- [ ] **Step 2: Add collection API client**

Create functions:

```ts
import { request } from '@/service/request';

export function fetchPaperCollections() {
  return request<Api.PaperCollection.Item[]>({ url: '/paper-collections' });
}

export function createPaperCollection(payload: Api.PaperCollection.UpsertPayload) {
  return request<Api.PaperCollection.Item>({ url: '/paper-collections', method: 'POST', data: payload });
}

export function updatePaperCollection(id: number, payload: Api.PaperCollection.UpsertPayload) {
  return request<Api.PaperCollection.Item>({ url: `/paper-collections/${id}`, method: 'PUT', data: payload });
}

export function deletePaperCollection(id: number) {
  return request({ url: `/paper-collections/${id}`, method: 'DELETE' });
}

export function fetchPaperCollection(id: number) {
  return request<Api.PaperCollection.Detail>({ url: `/paper-collections/${id}` });
}

export function addPapersToCollection(id: number, paperIds: string[]) {
  return request<Api.PaperCollection.Detail>({
    url: `/paper-collections/${id}/papers`,
    method: 'POST',
    data: { paperIds }
  });
}

export function removePaperFromCollection(id: number, paperId: string) {
  return request<Api.PaperCollection.Detail>({
    url: `/paper-collections/${id}/papers/${paperId}`,
    method: 'DELETE'
  });
}
```

- [ ] **Step 3: Extend chat store**

Add state:

```ts
const currentScope = ref<Api.Chat.ConversationScope | null>(null);
const referenceFocus = ref<Api.Chat.Scope | null>(null);
```

Add actions:

```ts
async function loadConversationScope(conversationId: string) {
  const { error, data } = await request<Api.Chat.ConversationScope>({
    url: `users/conversations/${conversationId}/scope`
  });
  if (!error && data) currentScope.value = data;
  return data || null;
}

async function updateConversationScope(conversationId: string, payload: Api.Chat.UpdateConversationScopePayload) {
  const { error, data } = await request<Api.Chat.ConversationScope>({
    url: `users/conversations/${conversationId}/scope`,
    method: 'PUT',
    data: payload
  });
  if (!error && data) currentScope.value = data;
  return !error;
}

async function createSessionFromScope(payload: Api.Chat.UpdateConversationScopePayload) {
  const created = await createNewSession();
  if (!created || !conversationId.value) return false;
  return updateConversationScope(conversationId.value, payload);
}

function setReferenceFocus(scope: Api.Chat.Scope | null) { referenceFocus.value = scope; }
```

- [ ] **Step 4: Typecheck**

Run:

```bash
cd frontend && pnpm typecheck
```

Expected: exit code `0`.

- [ ] **Step 5: Commit**

Run:

```bash
git add frontend/src/typings/api.d.ts frontend/src/service/api/paper-collections.ts frontend/src/store/modules/chat/index.ts
git commit -m "feat: add frontend scope and collection clients"
```

## Task 8: Paper Library Collections UI

**Files:**
- Modify: `frontend/src/views/knowledge-base/index.vue`
- Create: `frontend/src/views/knowledge-base/modules/collections-panel.vue`

- [ ] **Step 1: Add `Papers` / `Collections` segmented view**

Use Naive UI segmented control or tabs at the top of `knowledge-base/index.vue`.

States:

```ts
const libraryView = ref<'papers' | 'collections'>('papers');
```

- [ ] **Step 2: Implement collections panel**

`collections-panel.vue` must support:

- list collections
- create collection
- edit collection metadata
- delete collection
- open collection detail
- show `paperCount` and `searchablePaperCount`
- add papers through server-side paper candidate search
- remove papers

The add-paper search must call:

```ts
request<Api.Paper.List>({
  url: '/papers?scope=accessible',
  params: { page, size: 20, query, readiness: 'searchable' }
});
```

- [ ] **Step 3: Add start-session action**

For each collection, show a start-session action that calls chat store `createSessionFromScope` with:

```ts
{
  scopeMode: 'SOURCE_SET_SNAPSHOT',
  collectionIds: [collection.id],
  sourceLabel: collection.name,
  sourceRecipe: { type: 'collection', collectionIds: [collection.id] }
}
```

- [ ] **Step 4: Typecheck**

Run:

```bash
cd frontend && pnpm typecheck
```

Expected: exit code `0`.

- [ ] **Step 5: Commit**

Run:

```bash
git add frontend/src/views/knowledge-base/index.vue frontend/src/views/knowledge-base/modules/collections-panel.vue
git commit -m "feat: add paper collection management ui"
```

## Task 9: Chat Scope UI And New Session From Sources

**Files:**
- Create: `frontend/src/views/chat/modules/session-scope-picker.vue`
- Create: `frontend/src/views/chat/modules/session-scope-chip.vue`
- Modify: `frontend/src/views/chat/index.vue`
- Modify: `frontend/src/views/chat/modules/input-box.vue`
- Modify: `frontend/src/views/chat/modules/chat-message.vue`
- Modify: `frontend/src/views/chat/modules/conversation-sidebar.vue`

- [ ] **Step 1: Add session scope chip**

`session-scope-chip.vue` displays:

- `All searchable papers` for `AUTO_LIBRARY`
- collection or source label for `SOURCE_SET_SNAPSHOT`
- paper count
- locked state
- degraded/invalid state

Locked sessions show a lock icon and no edit action.

- [ ] **Step 2: Add session scope picker**

`session-scope-picker.vue` is available only when `scopeLocked=false`.

It supports:

- use all searchable papers
- select collection
- custom snapshot from paper metadata search

Paper search must be server-side:

```ts
params: { page, size: 20, query, readiness: 'searchable' }
```

- [ ] **Step 3: Replace mutable input scope**

In `input-box.vue`:

- remove local source picker state that loads the first 50 papers
- show session scope chip above or near the input
- show reference focus as a separate temporary chip
- send WebSocket payload with `referenceFocus` only for citation follow-up
- clear reference focus after send

- [ ] **Step 4: Change answer source action**

In `chat-message.vue`, replace same-session `Use sources` with `New session from sources`.

Payload:

```ts
{
  scopeMode: 'SOURCE_SET_SNAPSHOT',
  paperIds: sources.map(source => source.paperId),
  sourceLabel: `Sources from answer`,
  sourceRecipe: { type: 'answer_sources', conversationRecordId: props.msg.conversationRecordId }
}
```

- [ ] **Step 5: Sidebar scope summary**

`conversation-sidebar.vue` should show scope summary for each session:

- `All searchable papers`
- `128 papers`
- `Invalid scope`

- [ ] **Step 6: Typecheck**

Run:

```bash
cd frontend && pnpm typecheck
```

Expected: exit code `0`.

- [ ] **Step 7: Commit**

Run:

```bash
git add frontend/src/views/chat/index.vue frontend/src/views/chat/modules/input-box.vue frontend/src/views/chat/modules/chat-message.vue frontend/src/views/chat/modules/conversation-sidebar.vue frontend/src/views/chat/modules/session-scope-picker.vue frontend/src/views/chat/modules/session-scope-chip.vue
git commit -m "feat: lock chat source scope in frontend"
```

## Task 10: End-To-End Verification

**Files:**
- Modify only if tests reveal defects in files touched by earlier tasks.

- [ ] **Step 1: Run backend targeted tests**

Run:

```bash
mvn -q -Dtest=PaperSearchabilityServiceTest,PaperCollectionServiceTest,PaperCollectionControllerTest,ConversationScopeServiceTest,ConversationSessionScopeControllerTest,ConversationServiceTest,ChatHandlerReferenceEvidenceTest,PaperAnswerServiceTest,PaperControllerContractTest test
```

Expected: exit code `0`.

- [ ] **Step 2: Run frontend typecheck**

Run:

```bash
cd frontend && pnpm typecheck
```

Expected: exit code `0`.

- [ ] **Step 3: Run compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: exit code `0`.

- [ ] **Step 4: Reset product runtime**

Run:

```bash
scripts/paperloom-reset-product-runtime.sh --yes-i-know-this-deletes-product-runtime
```

Expected output includes:

```text
admin_count 1
product_papers 0
product_chunks 0
conversations 0
eval_litsearch_papers 64183
eval_qasper_papers 71
```

- [ ] **Step 5: Browser smoke**

Use the running frontend at:

```text
http://localhost:9527
```

Verify:

- login as admin works
- Paper Library shows empty product papers after reset
- Collections view loads
- creating a collection works
- empty collection cannot start a scoped session
- Chat new session shows `All searchable papers` and unlocked state
- first accepted message locks `AUTO_LIBRARY`
- locked chat session cannot edit scope
- creating a new session from a collection or answer sources creates a separate unlocked session
- reference focus appears separately and clears after send

- [ ] **Step 6: Product/eval isolation checks**

Run:

```bash
docker exec pai_smart_mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "
SELECT \"product_papers\", COUNT(*) FROM paismart.file_upload;
SELECT \"product_chunks\", COUNT(*) FROM paismart.paper_text_chunks;
SELECT \"conversations\", COUNT(*) FROM paismart.conversations;
SELECT \"admin_count\", COUNT(*) FROM paismart.users WHERE username=\"admin\";
SELECT \"eval_litsearch_papers\", COUNT(*) FROM paperloom_eval.eval_papers WHERE corpus=\"litsearch\";
SELECT \"eval_qasper_papers\", COUNT(*) FROM paperloom_eval.eval_papers WHERE corpus=\"qasper\";
"'
```

Expected:

```text
product_papers 0
product_chunks 0
conversations 0
admin_count 1
eval_litsearch_papers 64183
eval_qasper_papers 71
```

- [ ] **Step 7: Run benchmark guard**

Run:

```bash
scripts/paperloom-rag-gates.sh qasper-dev-200 --retrieval-corpus EVAL_QASPER
```

Expected:

- exit code `0`
- `scopeLeakRate=0.0`
- QASPER pass rate does not drop to `0.0`
- product `conversations` count remains unchanged after the run

- [ ] **Step 8: Commit verification fixes**

If this task changed files, list them first:

```bash
git status --short
```

If the output is empty, do not create an empty commit. If the output lists fixes made during
verification, add only those exact files shown by `git status --short` and commit:

```bash
git commit -m "test: verify scoped collection workflow"
```

## Self-Review

Spec coverage:

- Durable session scope: Tasks 2, 5, 6, 9.
- Scope locking after first accepted message: Tasks 5, 6, 9.
- Collections as product objects: Tasks 2, 4, 8.
- Snapshot retrieval truth: Tasks 5 and 6.
- Large-library source selection: Tasks 3, 8, 9.
- Reference focus separate from session scope: Tasks 6 and 9.
- Product runtime reset with benchmark/admin preservation: Tasks 1 and 10.
- Browser verification: Task 10.

Gap scan:

- The plan avoids open-ended gaps and names exact files, endpoints, fields, commands, and
  expected verification outputs.

Type consistency:

- Backend scope uses `ConversationScopeMode`, `ConversationScopeStatus`, and
  `EffectiveConversationScope`.
- Frontend scope uses `Api.Chat.ConversationScope` and `Api.Chat.UpdateConversationScopePayload`.
- Collection visibility uses `PRIVATE` and `ORG` on backend and frontend.
