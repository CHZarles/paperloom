# PaperLoom Minimal Search Loop Plan

Date: 2026-07-06

## Goal

Build the smallest useful product loop for questions such as:

```text
推荐 Agentic eval 相关论文
```

The loop should search the default full Product Paper Library scope, return paper-level candidates,
and attach Reading Model locations as evidence where possible.

This is not the final RAG/recommendation system. It is the minimum loop that proves the Current
Reading Model can support library search, paper grouping, and location-aware evidence lookup.

## Recommended Decisions

### 1. Default Scope

Recommended answer:

```text
Default scope is the user's accessible Product Paper Library.
```

Full-library scope means "find candidate papers from the whole accessible library." It does not mean
"return every matching page/section/element from the whole library."

### 2. First Search Layer

Recommended answer:

```text
Use paper-level metadata grep first.
```

The first layer searches paper cards:

```text
Paper.paperTitle
Paper.abstractText
Paper.authors
Paper.venue
Paper.publicationYear
Paper.doi
Paper.arxivId
Paper.originalFilename
```

It returns paper candidates, not locations.

Do not start with vector search, ReadingChunk, or full-library body grep.

### 3. Evidence Layer

Recommended answer:

```text
Use Reading Model grep only inside candidate papers.
```

For the top paper candidates, search only these Reading Model datasets:

```text
PaperReadingElement
PaperLocation
PaperPage
PaperSection
```

Every supporting hit must resolve to:

```text
PaperLocation.locationRef
```

### 4. Result Shape

Recommended answer:

```text
Return paper-grouped candidates with supporting locations.
```

Return paper cards, not raw grep hits:

```text
paper candidate
-> metadata match reason
-> evidence status
-> up to N supporting Reading Model locations
```

Recommended evidence statuses:

```text
SUPPORTED
METADATA_ONLY
NO_CURRENT_READING_MODEL
NO_READING_LOCATION_MATCH
```

`SUPPORTED` candidates rank above metadata-only candidates. Metadata-only candidates may still be
shown, but the response must not pretend they have paper-body evidence.

### 5. What This Is Not

This phase does not add:

```text
ReadingChunk
ReadingIndex / Elasticsearch migration
vector search
LLM-facing tools
Source Quote
Answer Guard
OCR/VLM
complex recommendation ranking
```

## Minimal Architecture

```text
queryText
-> PaperCandidateSearchService
   -> accessible Product Papers
   -> metadata grep
   -> top paper candidates
-> ReadingModelGrepSearchService
   -> current model for each candidate paper
   -> grep readable Reading Model fields
   -> resolve hits to locationRef
-> PaperRecommendationCandidateService
   -> group by paper
   -> attach up to perPaperLocationLimit supporting locations
   -> return candidate cards
```

## Implementation Sequence

Recommended answer:

```text
Implement inside-out, keep each step testable, and only add the endpoint after the three services
are green.
```

The current codebase already has useful seams:

- `PaperService.getAccessiblePapers(userId, orgTags)` centralizes access checks.
- `PaperRepository.searchAccessiblePaperCandidates(...)` exists, but it does single-query metadata
  LIKE and legacy searchable readiness can depend on `paper_text_chunks`.
- Reading Model repositories already expose current model, pages, sections, locations, and retained
  elements.

Recommended implementation path:

1. Add records only.
2. Implement `PaperCandidateSearchService` over `PaperService.getAccessiblePapers(...)`.
3. Implement `ReadingModelGrepSearchService` over Reading Model repositories.
4. Implement `PaperRecommendationCandidateService` as a small orchestrator.
5. Add the controller endpoint only after service tests pass.

This deliberately avoids changing `HybridSearchService`, `VectorizationService`, ES mappings, or
legacy `paper_text_chunks`.

### Step 1: Add DTO Records

Add:

```text
PaperCandidateSearchRequest
PaperCandidate
ReadingModelGrepSearchRequest
ReadingLocationCandidate
PaperRecommendationSearchRequest
PaperRecommendationCandidate
```

Use records first so service contracts are explicit before logic spreads into controllers.

Recommended constants:

```text
DEFAULT_PAPER_LIMIT = 20
MAX_PAPER_LIMIT = 100
DEFAULT_LOCATION_LIMIT = 60
MAX_LOCATION_LIMIT = 200
DEFAULT_PER_PAPER_LOCATION_LIMIT = 3
MAX_PER_PAPER_LOCATION_LIMIT = 10
```

### Step 2: Implement PaperCandidateSearchService

Recommended answer:

```text
Use PaperService.getAccessiblePapers for v1 rather than duplicating permission SQL.
```

Grilled decision:

```text
For this phase, paper-level candidate search is normalized metadata grep.
It is not vector search, not Reading Model body grep, and not final recommendation ranking.
```

Why:

- It keeps access control identical to the existing paper library.
- It avoids the legacy `searchable` readiness path that still checks `paper_text_chunks`.
- The MVP is for product validation, so transparent Java ranking is better than hidden SQL behavior.
- It lets the body/evidence layer stay bounded: first pick candidate papers, then search Current
  Reading Models only inside those papers.

This is an internal MVP implementation choice, not a permanent domain constraint. Future BM25,
vector search, or paper-level indexes can replace the internals behind the same
`PaperCandidateSearchService` contract after the Reading Model loop is proven.

Algorithm:

```text
1. Reject blank/too-short query.
2. Load accessible papers through PaperService.
3. Tokenize normalized query.
4. For each paper, build field matches over title, abstract, authors, venue, year, DOI, arXiv ID,
   and filename.
5. Prefer all-token matches; keep partial matches only as lower-rank fallback if needed.
6. Rank by best matched field.
7. Return top limit candidates.
```

Recommended field ranks:

```text
10 title all-token match
20 abstract all-token match
30 arxiv/doi/year identity-ish match
40 venue/authors match
50 filename match
80 partial metadata match
```

Do not inspect Reading Model here.

### Step 3: Implement ReadingModelGrepSearchService

Recommended answer:

```text
Load one current model at a time and resolve every hit to a location before returning it.
```

Algorithm for each `paperId`:

```text
1. Find current PaperReadingModel; skip if absent.
2. Load elements, pages, sections, locations for modelVersion.
3. Build indexes:
   - locationsByRef
   - pageLocationsByPageNumber
   - sectionLocationsBySectionId
   - elementsByReadingElementId
4. Grep readable fields only.
5. Resolve each hit to PaperLocation.
6. Drop unresolved hits.
7. Dedupe by paperId + modelVersion + locationRef.
8. Apply locationTypes, page range, and limit.
```

Element routing:

```text
element.locationRef
-> parent.locationRef
-> PAGE location by element.pageNumber
```

Section routing:

```text
SECTION location by sourceObjectId = section.sectionId
-> PAGE location by section.pageNumberFrom
```

Page routing:

```text
PAGE location by page.pageNumber
```

Recommended hit ranks:

```text
10 ELEMENT + OWN_LOCATION
20 ELEMENT + PARENT_LOCATION
30 SECTION
40 PAGE
```

Preview:

```text
Return a short window around the first matched token.
Fallback to the beginning of the matched readable field.
Do not include raw JSON.
```

### Step 4: Implement PaperRecommendationCandidateService

Recommended answer:

```text
Keep orchestration boring: metadata candidates first, then evidence lookup inside those papers.
```

Algorithm:

```text
1. Run PaperCandidateSearchService with paperLimit.
2. Take candidate paperIds in rank order.
3. Run ReadingModelGrepSearchService over those paperIds with limit =
   paperLimit * perPaperLocationLimit.
4. Group location candidates by paperId.
5. Keep at most perPaperLocationLimit per paper.
6. Mark evidenceStatus.
7. Return paper candidates grouped with supporting locations.
```

Evidence status rules:

```text
SUPPORTED:
  at least one supporting location exists

NO_CURRENT_READING_MODEL:
  paper has no current Reading Model

NO_READING_LOCATION_MATCH:
  paper has current Reading Model but grep found no resolved locations

METADATA_ONLY:
  use only when Reading Model evidence lookup was intentionally not attempted
```

For this MVP, prefer `NO_CURRENT_READING_MODEL` / `NO_READING_LOCATION_MATCH` over generic
`METADATA_ONLY`, because they tell us which part of the loop needs work.

### Step 5: Add Thin Endpoint

Recommended answer:

```text
Add it to PaperController under the existing /api/v1/papers base path.
```

Thin means:

```text
Controller-only wrapper for product QA/manual inspection.
```

Here `QA` means quality assurance and product validation, not question answering. The endpoint is a
manual inspection window over the three services; it must not become the place where search,
ranking, Source Quote creation, or recommendation logic lives.

Actual endpoint:

```text
POST /api/v1/papers/recommendation-candidates
```

Controller behavior:

```text
1. Read userId and orgTags from request attributes.
2. Validate queryText.
3. Clamp paperLimit and perPaperLocationLimit.
4. Call PaperRecommendationCandidateService.
5. Return plain structured JSON.
```

Do not:

```text
call LLM
create Source Quotes
emit final recommendations
return raw grep hits outside paper groups
```

## Implementation Tasks

### Task 1: Paper-Level Metadata Grep

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/PaperCandidateSearchService.java
src/main/java/com/yizhaoqi/smartpai/service/PaperCandidateSearchRequest.java
src/main/java/com/yizhaoqi/smartpai/service/PaperCandidate.java
src/test/java/com/yizhaoqi/smartpai/service/PaperCandidateSearchServiceTest.java
```

Input:

```java
record PaperCandidateSearchRequest(
    String queryText,
    String userId,
    String orgTags,
    int limit
)
```

Output:

```java
record PaperCandidate(
    String paperId,
    String title,
    String authors,
    Integer publicationYear,
    String venue,
    String abstractPreview,
    List<String> matchedFields,
    String matchReason,
    int rank
)
```

Rules:

- Search only accessible papers.
- Normalize whitespace and case.
- Tokenize query text.
- Prefer AND token matches.
- Rank title/abstract/tag-like fields above filename/authors.
- Default limit: 20.
- Max limit: 100.

No dependencies on Reading Model, MinerU, `paper_text_chunks`, ES, or vector search.

### Task 2: Reading Model Grep

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/ReadingModelGrepSearchService.java
src/main/java/com/yizhaoqi/smartpai/service/ReadingModelGrepSearchRequest.java
src/main/java/com/yizhaoqi/smartpai/service/ReadingLocationCandidate.java
src/test/java/com/yizhaoqi/smartpai/service/ReadingModelGrepSearchServiceTest.java
```

Input:

```java
record ReadingModelGrepSearchRequest(
    List<String> paperIds,
    String queryText,
    List<PaperLocationType> locationTypes,
    Integer pageFrom,
    Integer pageTo,
    int limit
)
```

Output:

```java
record ReadingLocationCandidate(
    String paperId,
    String modelVersion,
    String locationRef,
    PaperLocationType locationType,
    Integer pageNumber,
    Integer pageEndNumber,
    String sectionTitle,
    String readingElementId,
    String preview,
    String matchSource,
    String routingSource,
    List<String> matchedFields,
    List<String> matchedReadingElementIds
)
```

Search fields:

```text
PaperReadingElement.searchableText
PaperReadingElement.captionText
PaperReadingElement.bodyText
PaperSection.sectionTitle
PaperSection.sectionText
PaperPage.pageText
```

Do not search:

```text
rawAttributesJson
sourceSpanJson
structuredPayloadJson
bboxJson
parserImagePath
parserElementId
sourceObjectId
readingElementId
locationRef
modelVersion
```

Routing:

```text
Element hit:
  element.locationRef
  -> parent.locationRef
  -> PAGE location by element.pageNumber

Section hit:
  SECTION location by sourceObjectId = section.sectionId
  -> PAGE location by section.pageNumberFrom

Page hit:
  PAGE location by page.pageNumber
```

Unresolved hits must not leave the service.

Deduplicate by:

```text
paperId + modelVersion + locationRef
```

### Task 3: Paper Recommendation Candidate Orchestrator

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/PaperRecommendationCandidateService.java
src/main/java/com/yizhaoqi/smartpai/service/PaperRecommendationSearchRequest.java
src/main/java/com/yizhaoqi/smartpai/service/PaperRecommendationCandidate.java
src/test/java/com/yizhaoqi/smartpai/service/PaperRecommendationCandidateServiceTest.java
```

Input:

```java
record PaperRecommendationSearchRequest(
    String queryText,
    String userId,
    String orgTags,
    int paperLimit,
    int perPaperLocationLimit
)
```

Output:

```java
record PaperRecommendationCandidate(
    String paperId,
    String title,
    String authors,
    Integer publicationYear,
    String venue,
    String abstractPreview,
    String matchReason,
    String evidenceStatus,
    List<ReadingLocationCandidate> supportingLocations
)
```

Rules:

- Run `PaperCandidateSearchService` over the accessible library.
- Run `ReadingModelGrepSearchService` only for top paper candidates.
- Keep at most `perPaperLocationLimit` locations per paper.
- Rank `SUPPORTED` before `METADATA_ONLY`.
- Do not generate final prose recommendations here.
- Do not call an LLM.

### Task 4: Thin Inspection Endpoint

Add a small API endpoint only after the services pass focused tests.

Recommended endpoint:

```text
POST /api/v1/papers/recommendation-candidates
```

Request:

```json
{
  "queryText": "Agentic eval",
  "paperLimit": 20,
  "perPaperLocationLimit": 3
}
```

Response:

```json
{
  "code": 200,
  "message": "获取论文候选成功",
  "data": {
    "queryText": "Agentic eval",
    "scope": "PRODUCT_LIBRARY",
    "candidates": [
      {
        "paperId": "...",
        "title": "...",
        "evidenceStatus": "SUPPORTED",
        "matchReason": "title/abstract matched",
        "supportingLocations": [
          {
            "locationRef": "section_ref_...",
            "locationType": "SECTION",
            "pageNumber": 2,
            "preview": "..."
          }
        ]
      }
    ]
  }
}
```

This endpoint is for product QA and UI integration. It is not an LLM-facing tool.

## Focused Tests

### Paper Candidate Search

- Title match ranks above filename match.
- Abstract match returns a candidate.
- Permission filtering excludes inaccessible papers.
- Empty or too-short query returns no candidates.

### Reading Model Grep

- Table body hit resolves to TABLE location.
- Figure caption hit resolves to FIGURE location.
- Panel-only label hit resolves to parent FIGURE location.
- Formula hit resolves to PAGE location.
- Section text hit resolves to SECTION location.
- Page text hit resolves to PAGE location.
- Parser/debug fields do not match.

### Orchestrator

- Query returns paper-grouped candidates.
- Each supported candidate has at most `perPaperLocationLimit` locations.
- Candidate with no current Reading Model is marked `NO_CURRENT_READING_MODEL`.
- Candidate with a current Reading Model but no resolved grep evidence is marked
  `NO_READING_LOCATION_MATCH`.
- Raw location hits are not returned outside paper groups.

## Verification Commands

```bash
mvn -q \
  -Dtest=PaperCandidateSearchServiceTest,ReadingModelGrepSearchServiceTest,PaperRecommendationCandidateServiceTest \
  test
```

After adding the endpoint:

```bash
mvn -q -Dtest=PaperControllerContractTest test
```

Compile gate:

```bash
mvn -q -DskipTests test
```

## Done Criteria

The minimal loop is done when:

```text
full accessible library query
-> paper candidates
-> top candidate papers searched in Current Reading Model
-> supporting hits resolved to locationRef
-> candidates returned grouped by paper
```

and all focused tests pass.

No result may leave the retrieval layer unless it is either:

```text
paper-level metadata candidate
```

or:

```text
Reading Model hit resolved to PaperLocation.locationRef
```
