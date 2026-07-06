# PaperLoom Reading Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the product-owned Reading Model path from `ParsedPaper` into persisted `PaperReadingModel`, `PaperPage`, PAGE `PaperLocation`, and `SourceSpan` records.

**Architecture:** Keep Reading Model as a narrow persistence and builder layer under the existing parser flow. The builder converts `ParsedPaper.elements` into page text and PAGE locations; the service persists a versioned model and flips the current model only after success. Retrieval chunks, tools, Source Quotes, and Answer Guard stay out of this plan.

**Tech Stack:** Java 17, Spring Boot 3.4.2, Spring Data JPA, Hibernate, MySQL/H2, JUnit 5, Mockito, Jackson.

## Global Constraints

- Scope file: `docs/superpowers/specs/2026-07-04-paperloom-reading-model-implementation-spec.md`.
- Target flow: `PDF -> MinerU -> ParsedPaper -> PaperReadingModel -> PaperPage -> PAGE PaperLocation -> SourceSpan -> READING_MODEL_READY`.
- Do not implement `ReadingChunk`, Elasticsearch indexing, `find_reading_locations`, `read_locations`, `trace_source_quotes`, `PaperSourceQuote`, or Answer Guard in this plan.
- `READING_MODEL_READY` means readable pages and PAGE locations exist; it is not LLM retrieval readiness.
- PAGE location is required; SECTION, TABLE, FIGURE, bbox, screenshot, summary, and PageIndex must not block `READING_MODEL_READY`.
- `paper.vectorizationStatus` must not be used as Reading Model readiness.
- Use `data/*.pdf` only for the real MinerU smoke after unit and integration tests pass.

---

## File Structure

Create:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModel.java`: JPA row for one Reading Model build.
- `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModelStatus.java`: model build status enum.
- `src/main/java/com/yizhaoqi/smartpai/model/PaperPage.java`: persisted readable page text.
- `src/main/java/com/yizhaoqi/smartpai/model/PaperLocation.java`: persisted current-model location refs.
- `src/main/java/com/yizhaoqi/smartpai/model/PaperLocationType.java`: location type enum, only PAGE used now.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepository.java`: model queries and current-model flip.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperPageRepository.java`: page queries by paper/model.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperLocationRepository.java`: location queries by paper/model/ref.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuildResult.java`: in-memory build output.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelValidationException.java`: validation failure with diagnostics.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`: pure ParsedPaper-to-page/location builder.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelService.java`: transactional persistence and current-model replacement.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelNotReadyException.java`: stops downstream parse work after validation failure.
- `src/test/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepositoryTest.java`: JPA mapping and repository behavior.
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilderTest.java`: page assembly and diagnostics.
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelServiceTest.java`: current replacement and failed rebuild behavior.
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelDataPdfSmokeTest.java`: disabled-by-default real MinerU smoke over `data/*.pdf`.

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`: call `PaperReadingModelService` after parser artifact persistence and before table/figure/formula/chunk work.
- `src/test/java/com/yizhaoqi/smartpai/service/ParseServiceStructuredParserTest.java`: inject the new service mock and verify call order.

---

### Task 1: Persist Reading Model Tables

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModelStatus.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperLocationType.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModel.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperPage.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperLocation.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepository.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/PaperPageRepository.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/PaperLocationRepository.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepositoryTest.java`

**Interfaces:**
- Produces: `PaperReadingModelStatus.READING_MODEL_BUILDING`, `READING_MODEL_READY`, `READING_MODEL_FAILED`.
- Produces: `PaperLocationType.PAGE`.
- Produces repository methods used by `PaperReadingModelService`:
  - `Optional<PaperReadingModel> findFirstByPaperIdAndIsCurrentTrue(String paperId)`
  - `List<PaperReadingModel> findByPaperIdOrderByCreatedAtDesc(String paperId)`
  - `int clearCurrentModels(String paperId, String exceptModelVersion)`
  - `List<PaperPage> findByPaperIdAndModelVersionOrderByPageNumberAsc(String paperId, String modelVersion)`
  - `List<PaperLocation> findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(String paperId, String modelVersion)`

- [ ] **Step 1: Write the failing JPA repository test**

Create `src/test/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepositoryTest.java`:

```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class PaperReadingModelRepositoryTest {

    @Autowired
    private PaperReadingModelRepository modelRepository;

    @Autowired
    private PaperPageRepository pageRepository;

    @Autowired
    private PaperLocationRepository locationRepository;

    @Test
    void savesVersionedCurrentModelPagesAndPageLocations() {
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId("paper-a");
        model.setModelVersion("rm_test_1");
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        model.setParserName("MinerU");
        model.setParserVersion("1.3.0");
        model.setPageCount(1);
        model.setReadablePageCount(1);
        model.setReadableCharCount(12);
        modelRepository.save(model);

        PaperPage page = new PaperPage();
        page.setPaperId("paper-a");
        page.setModelVersion("rm_test_1");
        page.setPageNumber(1);
        page.setPageText("Hello paper.");
        page.setTextHash("hash-a");
        page.setCharCount(12);
        page.setSourceSpanJson("{\"pageNumber\":1}");
        page.setParserName("MinerU");
        page.setParserVersion("1.3.0");
        page.setUserId("user-a");
        page.setOrgTag("lab");
        page.setPublic(true);
        pageRepository.save(page);

        PaperLocation location = new PaperLocation();
        location.setLocationRef("page_ref_test_1");
        location.setPaperId("paper-a");
        location.setModelVersion("rm_test_1");
        location.setLocationType(PaperLocationType.PAGE);
        location.setPageNumber(1);
        location.setSourceSpanJson("{\"pageNumber\":1}");
        location.setContentKind("PAGE_TEXT");
        location.setUserId("user-a");
        location.setOrgTag("lab");
        location.setPublic(true);
        locationRepository.save(location);

        assertTrue(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());
        assertEquals(1, pageRepository.findByPaperIdAndModelVersionOrderByPageNumberAsc("paper-a", "rm_test_1").size());
        assertEquals(1, locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("paper-a", "rm_test_1").size());

        int cleared = modelRepository.clearCurrentModels("paper-a", "rm_next");

        assertEquals(1, cleared);
        assertFalse(modelRepository.findFirstByPaperIdAndIsCurrentTrue("paper-a").isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=PaperReadingModelRepositoryTest test
```

Expected: compile failure because `PaperReadingModel`, `PaperPage`, `PaperLocation`, and repositories do not exist.

- [ ] **Step 3: Add model enums**

Create `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModelStatus.java`:

```java
package com.yizhaoqi.smartpai.model;

public enum PaperReadingModelStatus {
    READING_MODEL_BUILDING,
    READING_MODEL_READY,
    READING_MODEL_FAILED
}
```

Create `src/main/java/com/yizhaoqi/smartpai/model/PaperLocationType.java`:

```java
package com.yizhaoqi.smartpai.model;

public enum PaperLocationType {
    PAGE,
    SECTION,
    TABLE,
    FIGURE
}
```

- [ ] **Step 4: Add JPA entities**

Create `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModel.java`:

```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_reading_models",
        indexes = {
                @Index(name = "idx_paper_reading_models_paper_current", columnList = "paper_id,is_current"),
                @Index(name = "idx_paper_reading_models_paper_version", columnList = "paper_id,model_version")
        })
public class PaperReadingModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_status", nullable = false, length = 64)
    private PaperReadingModelStatus modelStatus;

    @Column(name = "is_current", nullable = false)
    private boolean isCurrent;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "readable_page_count")
    private Integer readablePageCount;

    @Column(name = "readable_char_count")
    private Integer readableCharCount;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Lob
    @Column(name = "diagnostics_json", columnDefinition = "TEXT")
    private String diagnosticsJson;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

Create `src/main/java/com/yizhaoqi/smartpai/model/PaperPage.java`:

```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_pages",
        indexes = {
                @Index(name = "idx_paper_pages_paper_model_page", columnList = "paper_id,model_version,page_number")
        })
public class PaperPage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Lob
    @Column(name = "page_text", nullable = false, columnDefinition = "TEXT")
    private String pageText;

    @Column(name = "text_hash", nullable = false, length = 64)
    private String textHash;

    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    @Lob
    @Column(name = "source_span_json", nullable = false, columnDefinition = "TEXT")
    private String sourceSpanJson;

    @Column(name = "parser_name", length = 64)
    private String parserName;

    @Column(name = "parser_version", length = 64)
    private String parserVersion;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

Create `src/main/java/com/yizhaoqi/smartpai/model/PaperLocation.java`:

```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "paper_locations",
        indexes = {
                @Index(name = "idx_paper_locations_ref", columnList = "location_ref"),
                @Index(name = "idx_paper_locations_paper_model", columnList = "paper_id,model_version"),
                @Index(name = "idx_paper_locations_paper_model_page", columnList = "paper_id,model_version,page_number")
        })
public class PaperLocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location_ref", nullable = false, unique = true, length = 96)
    private String locationRef;

    @Column(name = "paper_id", nullable = false, length = 32)
    private String paperId;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 32)
    private PaperLocationType locationType;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    @Lob
    @Column(name = "source_span_json", nullable = false, columnDefinition = "TEXT")
    private String sourceSpanJson;

    @Column(name = "content_kind", nullable = false, length = 64)
    private String contentKind;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
```

- [ ] **Step 5: Add repositories**

Create `src/main/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepository.java`:

```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperReadingModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PaperReadingModelRepository extends JpaRepository<PaperReadingModel, Long> {
    Optional<PaperReadingModel> findFirstByPaperIdAndIsCurrentTrue(String paperId);

    List<PaperReadingModel> findByPaperIdOrderByCreatedAtDesc(String paperId);

    @Transactional
    @Modifying
    @Query("""
            UPDATE PaperReadingModel model
            SET model.isCurrent = false
            WHERE model.paperId = :paperId
              AND model.isCurrent = true
              AND model.modelVersion <> :exceptModelVersion
            """)
    int clearCurrentModels(@Param("paperId") String paperId,
                           @Param("exceptModelVersion") String exceptModelVersion);
}
```

Create `src/main/java/com/yizhaoqi/smartpai/repository/PaperPageRepository.java`:

```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaperPageRepository extends JpaRepository<PaperPage, Long> {
    List<PaperPage> findByPaperIdAndModelVersionOrderByPageNumberAsc(String paperId, String modelVersion);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);
}
```

Create `src/main/java/com/yizhaoqi/smartpai/repository/PaperLocationRepository.java`:

```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.PaperLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperLocationRepository extends JpaRepository<PaperLocation, Long> {
    Optional<PaperLocation> findFirstByLocationRef(String locationRef);

    List<PaperLocation> findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(String paperId, String modelVersion);

    long countByPaperIdAndModelVersion(String paperId, String modelVersion);
}
```

- [ ] **Step 6: Run repository test**

Run:

```bash
mvn -q -Dtest=PaperReadingModelRepositoryTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModelStatus.java \
  src/main/java/com/yizhaoqi/smartpai/model/PaperLocationType.java \
  src/main/java/com/yizhaoqi/smartpai/model/PaperReadingModel.java \
  src/main/java/com/yizhaoqi/smartpai/model/PaperPage.java \
  src/main/java/com/yizhaoqi/smartpai/model/PaperLocation.java \
  src/main/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepository.java \
  src/main/java/com/yizhaoqi/smartpai/repository/PaperPageRepository.java \
  src/main/java/com/yizhaoqi/smartpai/repository/PaperLocationRepository.java \
  src/test/java/com/yizhaoqi/smartpai/repository/PaperReadingModelRepositoryTest.java
git commit -m "feat: add reading model persistence"
```

### Task 2: Build PAGE Reading Model From ParsedPaper

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuildResult.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelValidationException.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilderTest.java`

**Interfaces:**
- Consumes: `ParsedPaper`, `ParsedPaperElement`, `PaperPage`, `PaperLocation`, `PaperLocationType`.
- Produces:
  - `PaperReadingModelBuildResult(List<PaperPage> pages, List<PaperLocation> locations, String diagnosticsJson)`
  - `PaperReadingModelBuilder.build(String paperId, String modelVersion, ParsedPaper parsedPaper, String userId, String orgTag, boolean isPublic)`
  - `PaperReadingModelValidationException.failureReason()`
  - `PaperReadingModelValidationException.diagnosticsJson()`

- [ ] **Step 1: Write failing builder tests**

Create `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilderTest.java`:

```java
package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperReadingModelBuilderTest {

    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void buildsPagesAndPageLocationsInReadingOrder() {
        ParsedPaper paper = parsedPaper(List.of(
                element("p2", 2, 3, ParsedPaperElementType.PARAGRAPH, "Second page text."),
                element("h1", 1, 1, ParsedPaperElementType.HEADING, "Intro"),
                element("p1", 1, 2, ParsedPaperElementType.PARAGRAPH, "First page text."),
                element("blank", 1, 4, ParsedPaperElementType.PARAGRAPH, "   "),
                element("no-page", null, 5, ParsedPaperElementType.PARAGRAPH, "Skipped text.")
        ));

        PaperReadingModelBuildResult result = builder.build(
                "paper-a",
                "rm_test_1",
                paper,
                "user-a",
                "lab",
                true
        );

        assertEquals(2, result.pages().size());
        PaperPage page1 = result.pages().get(0);
        assertEquals(1, page1.getPageNumber());
        assertEquals("Intro\n\nFirst page text.", page1.getPageText());
        assertEquals("paper-a", page1.getPaperId());
        assertEquals("rm_test_1", page1.getModelVersion());
        assertEquals("MinerU", page1.getParserName());
        assertTrue(page1.getSourceSpanJson().contains("\"elementIds\":[\"h1\",\"p1\"]"));
        assertTrue(page1.getSourceSpanJson().contains("\"readingOrderFrom\":1"));
        assertTrue(page1.getSourceSpanJson().contains("\"readingOrderTo\":2"));

        PaperLocation location = result.locations().get(0);
        assertEquals(PaperLocationType.PAGE, location.getLocationType());
        assertEquals(1, location.getPageNumber());
        assertTrue(location.getLocationRef().startsWith("page_ref_"));
        assertEquals("PAGE_TEXT", location.getContentKind());
        assertEquals(page1.getSourceSpanJson(), location.getSourceSpanJson());

        assertTrue(result.diagnosticsJson().contains("\"elementsSkippedNoPage\":1"));
        assertTrue(result.diagnosticsJson().contains("\"elementsSkippedBlankText\":1"));
    }

    @Test
    void failsWhenNoReadableNumberedTextExists() {
        ParsedPaper paper = parsedPaper(List.of(
                element("blank", 1, 1, ParsedPaperElementType.PARAGRAPH, " "),
                element("no-page", null, 2, ParsedPaperElementType.PARAGRAPH, "Has text.")
        ));

        PaperReadingModelValidationException failure = assertThrows(
                PaperReadingModelValidationException.class,
                () -> builder.build("paper-a", "rm_test_1", paper, "user-a", "lab", false)
        );

        assertEquals("NO_READABLE_NUMBERED_TEXT", failure.failureReason());
        assertTrue(failure.diagnosticsJson().contains("\"elementsSkippedNoPage\":1"));
        assertTrue(failure.diagnosticsJson().contains("\"elementsSkippedBlankText\":1"));
    }

    private ParsedPaper parsedPaper(List<ParsedPaperElement> elements) {
        return new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 2, null, null),
                elements,
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ParsedPaperElement element(String id,
                                       Integer pageNumber,
                                       Integer order,
                                       ParsedPaperElementType type,
                                       String text) {
        return new ParsedPaperElement(
                id,
                pageNumber,
                order,
                type,
                text,
                null,
                null,
                pageNumber == null ? null : new BoundingBox(pageNumber, 1.0, 2.0, 3.0, 4.0, "pdf_points", "bottom_left"),
                Map.of()
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=PaperReadingModelBuilderTest test
```

Expected: compile failure because builder classes do not exist.

- [ ] **Step 3: Add build result and validation exception**

Create `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuildResult.java`:

```java
package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperPage;

import java.util.List;

public record PaperReadingModelBuildResult(
        List<PaperPage> pages,
        List<PaperLocation> locations,
        String diagnosticsJson
) {
}
```

Create `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelValidationException.java`:

```java
package com.yizhaoqi.smartpai.service;

public class PaperReadingModelValidationException extends RuntimeException {
    private final String failureReason;
    private final String diagnosticsJson;

    public PaperReadingModelValidationException(String failureReason, String diagnosticsJson) {
        super(failureReason);
        this.failureReason = failureReason;
        this.diagnosticsJson = diagnosticsJson;
    }

    public String failureReason() {
        return failureReason;
    }

    public String diagnosticsJson() {
        return diagnosticsJson;
    }
}
```

- [ ] **Step 4: Add `PaperReadingModelBuilder`**

Create `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java` with this behavior:

```java
package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperLocationType;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.paper.parser.BoundingBox;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PaperReadingModelBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaperReadingModelBuildResult build(String paperId,
                                              String modelVersion,
                                              ParsedPaper parsedPaper,
                                              String userId,
                                              String orgTag,
                                              boolean isPublic) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("paperId", paperId);
        diagnostics.put("modelVersion", modelVersion);
        diagnostics.put("parserName", parsedPaper == null ? null : parsedPaper.parserName());
        diagnostics.put("parserVersion", parsedPaper == null ? null : parsedPaper.parserVersion());

        if (parsedPaper == null) {
            throw failure("PARSED_PAPER_MISSING", diagnostics);
        }
        if (parsedPaper.elements() == null || parsedPaper.elements().isEmpty()) {
            diagnostics.put("elementCount", 0);
            throw failure("PARSED_ELEMENTS_EMPTY", diagnostics);
        }

        List<ParsedPaperElement> elements = parsedPaper.elements();
        int elementsSkippedNoPage = 0;
        int elementsSkippedBlankText = 0;
        List<ReadableElement> readable = new ArrayList<>();
        for (ParsedPaperElement element : elements) {
            String text = normalizeText(element == null ? null : element.text());
            if (text.isBlank()) {
                elementsSkippedBlankText++;
                continue;
            }
            Integer pageNumber = element.pageNumber();
            if (pageNumber == null || pageNumber <= 0) {
                elementsSkippedNoPage++;
                continue;
            }
            readable.add(new ReadableElement(element, text));
        }

        diagnostics.put("elementCount", elements.size());
        diagnostics.put("elementsWithPageNumber", elements.size() - elementsSkippedNoPage);
        diagnostics.put("elementsWithText", elements.size() - elementsSkippedBlankText);
        diagnostics.put("elementsSkippedNoPage", elementsSkippedNoPage);
        diagnostics.put("elementsSkippedBlankText", elementsSkippedBlankText);

        if (readable.isEmpty()) {
            throw failure("NO_READABLE_NUMBERED_TEXT", diagnostics);
        }

        Map<Integer, List<ReadableElement>> byPage = readable.stream()
                .sorted(Comparator
                        .comparing((ReadableElement item) -> item.element().pageNumber())
                        .thenComparing(item -> item.element().readingOrder(), Comparator.nullsLast(Integer::compareTo)))
                .collect(Collectors.groupingBy(
                        item -> item.element().pageNumber(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<PaperPage> pages = new ArrayList<>();
        List<PaperLocation> locations = new ArrayList<>();
        int readableCharCount = 0;
        for (Map.Entry<Integer, List<ReadableElement>> entry : byPage.entrySet()) {
            int pageNumber = entry.getKey();
            List<ReadableElement> pageElements = entry.getValue();
            String pageText = pageElements.stream()
                    .map(ReadableElement::text)
                    .collect(Collectors.joining("\n\n"));
            String sourceSpanJson = sourceSpanJson(parsedPaper, pageNumber, pageElements);

            PaperPage page = new PaperPage();
            page.setPaperId(paperId);
            page.setModelVersion(modelVersion);
            page.setPageNumber(pageNumber);
            page.setPageText(pageText);
            page.setTextHash(sha256(pageText));
            page.setCharCount(pageText.length());
            page.setSourceSpanJson(sourceSpanJson);
            page.setParserName(parsedPaper.parserName());
            page.setParserVersion(parsedPaper.parserVersion());
            page.setUserId(userId);
            page.setOrgTag(orgTag);
            page.setPublic(isPublic);
            pages.add(page);
            readableCharCount += pageText.length();

            PaperLocation location = new PaperLocation();
            location.setLocationRef("page_ref_" + UUID.randomUUID().toString().replace("-", ""));
            location.setPaperId(paperId);
            location.setModelVersion(modelVersion);
            location.setLocationType(PaperLocationType.PAGE);
            location.setPageNumber(pageNumber);
            location.setSourceSpanJson(sourceSpanJson);
            location.setContentKind("PAGE_TEXT");
            location.setUserId(userId);
            location.setOrgTag(orgTag);
            location.setPublic(isPublic);
            locations.add(location);
        }

        diagnostics.put("readablePageCount", pages.size());
        diagnostics.put("readableCharCount", readableCharCount);
        diagnostics.put("locationCount", locations.size());
        diagnostics.put("hasAnyBbox", readable.stream().anyMatch(item -> item.element().boundingBox() != null));
        return new PaperReadingModelBuildResult(pages, locations, writeJson(diagnostics));
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u0000", "")
                .trim();
    }

    private String sourceSpanJson(ParsedPaper paper, int pageNumber, List<ReadableElement> pageElements) {
        List<String> elementIds = pageElements.stream()
                .map(item -> item.element().elementId())
                .filter(Objects::nonNull)
                .toList();
        List<Integer> orders = pageElements.stream()
                .map(item -> item.element().readingOrder())
                .filter(Objects::nonNull)
                .toList();
        LinkedHashSet<String> sourceKinds = pageElements.stream()
                .map(item -> item.element().elementType() == null ? "UNKNOWN" : item.element().elementType().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<BoundingBox> bboxes = pageElements.stream()
                .map(item -> item.element().boundingBox())
                .filter(Objects::nonNull)
                .toList();

        Map<String, Object> sourceSpan = new LinkedHashMap<>();
        sourceSpan.put("parserName", paper.parserName());
        sourceSpan.put("parserVersion", paper.parserVersion());
        sourceSpan.put("pageNumber", pageNumber);
        sourceSpan.put("elementIds", elementIds);
        sourceSpan.put("readingOrderFrom", orders.stream().min(Integer::compareTo).orElse(null));
        sourceSpan.put("readingOrderTo", orders.stream().max(Integer::compareTo).orElse(null));
        sourceSpan.put("bbox", bboxes.isEmpty() ? null : bboxes);
        sourceSpan.put("sourceKinds", List.copyOf(sourceKinds));
        sourceSpan.put("rawArtifactRef", null);
        return writeJson(sourceSpan);
    }

    private PaperReadingModelValidationException failure(String reason, Map<String, Object> diagnostics) {
        diagnostics.put("failureReason", reason);
        return new PaperReadingModelValidationException(reason, writeJson(diagnostics));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize reading model JSON", e);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record ReadableElement(ParsedPaperElement element, String text) {
    }
}
```

- [ ] **Step 5: Run builder tests**

Run:

```bash
mvn -q -Dtest=PaperReadingModelBuilderTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuildResult.java \
  src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelValidationException.java \
  src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java \
  src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilderTest.java
git commit -m "feat: build page reading model"
```

### Task 3: Persist And Replace Current Reading Model

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelService.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelServiceTest.java`

**Interfaces:**
- Consumes: `PaperReadingModelBuilder.build(...)`.
- Produces: `PaperReadingModelService.replaceFromParsedPaper(String paperId, ParsedPaper parsedPaper, String userId, String orgTag, boolean isPublic)`.

- [ ] **Step 1: Write failing service tests**

Create `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelServiceTest.java`:

```java
package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperLocation;
import com.yizhaoqi.smartpai.model.PaperPage;
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElement;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperElementType;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaperMetadata;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperReadingModelServiceTest {

    @Mock
    private PaperReadingModelRepository modelRepository;

    @Mock
    private PaperPageRepository pageRepository;

    @Mock
    private PaperLocationRepository locationRepository;

    @Test
    void successfulBuildCreatesCurrentReadyModel() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(locationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                locationRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper("Readable text."),
                "user-a",
                "lab",
                true
        );

        assertEquals(PaperReadingModelStatus.READING_MODEL_READY, model.getModelStatus());
        assertTrue(model.isCurrent());
        assertEquals(1, model.getReadablePageCount());
        assertEquals("MinerU", model.getParserName());
        verify(pageRepository).saveAll(any());
        verify(locationRepository).saveAll(any());
        verify(modelRepository).clearCurrentModels(eq("paper-a"), eq(model.getModelVersion()));
    }

    @Test
    void failedBuildDoesNotReplaceCurrentModel() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                locationRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel model = service.replaceFromParsedPaper(
                "paper-a",
                parsedPaper(" "),
                "user-a",
                "lab",
                false
        );

        assertEquals(PaperReadingModelStatus.READING_MODEL_FAILED, model.getModelStatus());
        assertEquals("NO_READABLE_NUMBERED_TEXT", model.getFailureReason());
        assertFalse(model.isCurrent());
        verify(pageRepository, never()).saveAll(any());
        verify(locationRepository, never()).saveAll(any());
        verify(modelRepository, never()).clearCurrentModels(any(), any());
    }

    @Test
    void pagesAndLocationsUseSameModelVersion() {
        when(modelRepository.save(any(PaperReadingModel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pageRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(locationRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PaperReadingModelService service = new PaperReadingModelService(
                modelRepository,
                pageRepository,
                locationRepository,
                new PaperReadingModelBuilder()
        );

        PaperReadingModel model = service.replaceFromParsedPaper("paper-a", parsedPaper("Readable text."), "user-a", "lab", true);

        ArgumentCaptor<Iterable<PaperPage>> pagesCaptor = ArgumentCaptor.forClass(Iterable.class);
        ArgumentCaptor<Iterable<PaperLocation>> locationsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(pageRepository).saveAll(pagesCaptor.capture());
        verify(locationRepository).saveAll(locationsCaptor.capture());
        PaperPage page = pagesCaptor.getValue().iterator().next();
        PaperLocation location = locationsCaptor.getValue().iterator().next();

        assertEquals(model.getModelVersion(), page.getModelVersion());
        assertEquals(model.getModelVersion(), location.getModelVersion());
    }

    private ParsedPaper parsedPaper(String text) {
        return new ParsedPaper(
                "MinerU",
                "1.3.0",
                new ParsedPaperMetadata("paper.pdf", "Paper", "Ada", 1, null, null),
                List.of(new ParsedPaperElement(
                        "p1",
                        1,
                        1,
                        ParsedPaperElementType.PARAGRAPH,
                        text,
                        null,
                        null,
                        null,
                        Map.of()
                )),
                Map.of(),
                "{}",
                List.of(),
                List.of(),
                List.of()
        );
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=PaperReadingModelServiceTest test
```

Expected: compile failure because `PaperReadingModelService` does not exist.

- [ ] **Step 3: Add service**

Create `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelService.java`:

```java
package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import com.yizhaoqi.smartpai.repository.PaperLocationRepository;
import com.yizhaoqi.smartpai.repository.PaperPageRepository;
import com.yizhaoqi.smartpai.repository.PaperReadingModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaperReadingModelService {

    private final PaperReadingModelRepository modelRepository;
    private final PaperPageRepository pageRepository;
    private final PaperLocationRepository locationRepository;
    private final PaperReadingModelBuilder builder;

    public PaperReadingModelService(PaperReadingModelRepository modelRepository,
                                    PaperPageRepository pageRepository,
                                    PaperLocationRepository locationRepository,
                                    PaperReadingModelBuilder builder) {
        this.modelRepository = modelRepository;
        this.pageRepository = pageRepository;
        this.locationRepository = locationRepository;
        this.builder = builder;
    }

    @Transactional
    public PaperReadingModel replaceFromParsedPaper(String paperId,
                                                    ParsedPaper parsedPaper,
                                                    String userId,
                                                    String orgTag,
                                                    boolean isPublic) {
        String modelVersion = newModelVersion();
        PaperReadingModel model = new PaperReadingModel();
        model.setPaperId(paperId);
        model.setModelVersion(modelVersion);
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_BUILDING);
        model.setCurrent(false);
        model.setParserName(parsedPaper == null ? null : parsedPaper.parserName());
        model.setParserVersion(parsedPaper == null ? null : parsedPaper.parserVersion());
        model = modelRepository.save(model);

        PaperReadingModelBuildResult result;
        try {
            result = builder.build(paperId, modelVersion, parsedPaper, userId, orgTag, isPublic);
        } catch (PaperReadingModelValidationException exception) {
            model.setModelStatus(PaperReadingModelStatus.READING_MODEL_FAILED);
            model.setFailureReason(exception.failureReason());
            model.setDiagnosticsJson(exception.diagnosticsJson());
            model.setCurrent(false);
            return modelRepository.save(model);
        }

        pageRepository.saveAll(result.pages());
        locationRepository.saveAll(result.locations());
        modelRepository.clearCurrentModels(paperId, modelVersion);

        int readableCharCount = result.pages().stream()
                .mapToInt(page -> page.getCharCount() == null ? 0 : page.getCharCount())
                .sum();
        model.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
        model.setCurrent(true);
        model.setPageCount(result.pages().size());
        model.setReadablePageCount(result.pages().size());
        model.setReadableCharCount(readableCharCount);
        model.setDiagnosticsJson(result.diagnosticsJson());
        model.setFailureReason(null);
        return modelRepository.save(model);
    }

    private String newModelVersion() {
        return "rm_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
```

- [ ] **Step 4: Run service tests**

Run:

```bash
mvn -q -Dtest=PaperReadingModelServiceTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelService.java \
  src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelServiceTest.java
git commit -m "feat: persist current reading model"
```

### Task 4: Wire Reading Model Into ParseService

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelNotReadyException.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ParseServiceStructuredParserTest.java`

**Interfaces:**
- Consumes: `PaperReadingModelService.replaceFromParsedPaper(...)`.
- Produces: parse flow calls Reading Model build after parser artifact save and before table/figure/formula/visual/chunk work.

- [ ] **Step 1: Update ParseService test first**

Modify `ParseServiceStructuredParserTest`:

```java
@Mock
private PaperReadingModelService paperReadingModelService;
```

In each `ParseService` setup block, add:

```java
ReflectionTestUtils.setField(parseService, "paperReadingModelService", paperReadingModelService);
```

In `parseAndSavePersistsStructuredPaperChunkProvenance`, before `parseService.parseAndSave(...)`, add:

```java
PaperReadingModel readyModel = new PaperReadingModel();
readyModel.setPaperId("paper123");
readyModel.setModelVersion("rm_test_1");
readyModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_READY);
readyModel.setCurrent(true);
when(paperReadingModelService.replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true))
        .thenReturn(readyModel);
```

After existing verifies, add order verification:

```java
InOrder order = inOrder(paperParserArtifactService, paperReadingModelService, paperTableService);
order.verify(paperParserArtifactService).saveParserArtifact("paper123", parsedPaper, "7", "lab", true);
order.verify(paperReadingModelService).replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true);
order.verify(paperTableService).replaceTables("paper123", parsedPaper, "7", "lab", true);
```

Add imports:

```java
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
```

Add a failure test:

```java
@Test
void parseAndSaveStopsWhenReadingModelIsNotReady() throws Exception {
    ParseService parseService = new ParseService();
    ReflectionTestUtils.setField(parseService, "paperTextChunkRepository", paperTextChunkRepository);
    ReflectionTestUtils.setField(parseService, "paperRepository", paperRepository);
    ReflectionTestUtils.setField(parseService, "usageQuotaService", usageQuotaService);
    ReflectionTestUtils.setField(parseService, "paperPdfParser", paperPdfParser);
    ReflectionTestUtils.setField(parseService, "paperChunkBuilder", new PaperChunkBuilder());
    ReflectionTestUtils.setField(parseService, "paperParserArtifactService", paperParserArtifactService);
    ReflectionTestUtils.setField(parseService, "paperTableService", paperTableService);
    ReflectionTestUtils.setField(parseService, "paperVisualAssetService", paperVisualAssetService);
    ReflectionTestUtils.setField(parseService, "paperFigureService", paperFigureService);
    ReflectionTestUtils.setField(parseService, "paperFormulaService", paperFormulaService);
    ReflectionTestUtils.setField(parseService, "paperReadingModelService", paperReadingModelService);
    ReflectionTestUtils.setField(parseService, "chunkSize", 512);
    ReflectionTestUtils.setField(parseService, "maxMemoryThreshold", 0.8);

    Paper paper = new Paper();
    paper.setPaperId("paper123");
    paper.setOriginalFilename("uploaded.pdf");
    when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("paper123")).thenReturn(Optional.of(paper));
    ParsedPaper parsedPaper = parsedPaper();
    when(paperPdfParser.parse(any(), eq("uploaded.pdf"))).thenReturn(parsedPaper);
    PaperReadingModel failedModel = new PaperReadingModel();
    failedModel.setPaperId("paper123");
    failedModel.setModelVersion("rm_failed");
    failedModel.setModelStatus(PaperReadingModelStatus.READING_MODEL_FAILED);
    failedModel.setFailureReason("NO_READABLE_NUMBERED_TEXT");
    when(paperReadingModelService.replaceFromParsedPaper("paper123", parsedPaper, "7", "lab", true))
            .thenReturn(failedModel);

    assertThrows(
            PaperReadingModelNotReadyException.class,
            () -> parseService.parseAndSave(
                    "paper123",
                    new ByteArrayInputStream("%PDF-test".getBytes(StandardCharsets.UTF_8)),
                    "uploaded.pdf",
                    "7",
                    "lab",
                    true
            )
    );

    verify(paperTableService, never()).replaceTables(any(), any(), any(), any(), eq(true));
    verify(paperTextChunkRepository, never()).save(any());
}
```

Add import:

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=ParseServiceStructuredParserTest test
```

Expected: compile failure because `PaperReadingModelNotReadyException` does not exist and `ParseService` has no `paperReadingModelService` field.

- [ ] **Step 3: Add not-ready exception**

Create `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelNotReadyException.java`:

```java
package com.yizhaoqi.smartpai.service;

public class PaperReadingModelNotReadyException extends RuntimeException {
    public PaperReadingModelNotReadyException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Modify ParseService**

Add imports:

```java
import com.yizhaoqi.smartpai.model.PaperReadingModel;
import com.yizhaoqi.smartpai.model.PaperReadingModelStatus;
```

Add field:

```java
@Autowired
private PaperReadingModelService paperReadingModelService;
```

In `parseAndSave(...)`, replace the block immediately after `updatePaperMetadata(paperId, parsedPaper);`
with this exact sequence:

```java
paperParserArtifactService.saveParserArtifact(paperId, parsedPaper, userId, orgTag, isPublic);
updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MINERU_ARTIFACT_SAVED);
PaperReadingModel readingModel = paperReadingModelService.replaceFromParsedPaper(
        paperId,
        parsedPaper,
        userId,
        orgTag,
        isPublic
);
if (readingModel == null || readingModel.getModelStatus() != PaperReadingModelStatus.READING_MODEL_READY) {
    String reason = readingModel == null ? "READING_MODEL_MISSING" : readingModel.getFailureReason();
    throw new PaperReadingModelNotReadyException("Reading Model is not ready for paperId="
            + paperId + ", reason=" + reason);
}
updatePipelineStatus(paperId, Paper.VECTORIZATION_STATUS_MAPPING_STRUCTURED_CONTENT);
```

Do not call `paperTableService.replaceTables(...)`, `paperFigureService.replaceFigures(...)`,
`paperFormulaService.replaceFormulas(...)`, `paperVisualAssetService.replaceVisualAssets(...)`, or
`paperChunkBuilder.buildChunks(...)` before the Reading Model status check.

- [ ] **Step 5: Run parse integration test**

Run:

```bash
mvn -q -Dtest=ParseServiceStructuredParserTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelNotReadyException.java \
  src/main/java/com/yizhaoqi/smartpai/service/ParseService.java \
  src/test/java/com/yizhaoqi/smartpai/service/ParseServiceStructuredParserTest.java
git commit -m "feat: build reading model during parsing"
```

### Task 5: Add Real PDF Smoke For MinerU Output

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelDataPdfSmokeTest.java`

**Interfaces:**
- Consumes: Spring `PaperPdfParser` bean, `PaperReadingModelBuilder`.
- Produces: disabled-by-default smoke that parses real PDFs in `data/` and verifies Reading Model build diagnostics.

- [ ] **Step 1: Write disabled real-data smoke test**

Create `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelDataPdfSmokeTest.java`:

```java
package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.SmartPaiApplication;
import com.yizhaoqi.smartpai.paper.parser.PaperPdfParser;
import com.yizhaoqi.smartpai.paper.parser.ParsedPaper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "paperloom.reading-model.real-pdf", matches = "true")
@SpringBootTest(
        classes = SmartPaiApplication.class,
        properties = {
                "elasticsearch.init.enabled=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "admin.bootstrap.enabled=false",
                "paper.bootstrap.enabled=false"
        }
)
class PaperReadingModelDataPdfSmokeTest {

    @Autowired
    private PaperPdfParser paperPdfParser;

    private final PaperReadingModelBuilder builder = new PaperReadingModelBuilder();

    @Test
    void dataPdfsProduceReadablePagesAndPageLocations() throws Exception {
        List<Path> pdfs = List.of(
                Path.of("data/2308.03688.pdf"),
                Path.of("data/2401.13178.pdf"),
                Path.of("data/2503.05244.pdf")
        );

        for (Path pdf : pdfs) {
            assertTrue(Files.exists(pdf), "missing smoke PDF: " + pdf);
            ParsedPaper parsedPaper;
            try (InputStream inputStream = Files.newInputStream(pdf)) {
                parsedPaper = paperPdfParser.parse(inputStream, pdf.getFileName().toString());
            }

            PaperReadingModelBuildResult result = builder.build(
                    "smoke-" + pdf.getFileName(),
                    "rm_smoke",
                    parsedPaper,
                    "smoke-user",
                    "smoke-org",
                    false
            );

            assertFalse(result.pages().isEmpty(), "no pages for " + pdf);
            assertEquals(result.pages().size(), result.locations().size(), "page/location mismatch for " + pdf);
            assertTrue(result.diagnosticsJson().contains("\"readablePageCount\""), result.diagnosticsJson());
            assertTrue(result.diagnosticsJson().contains("\"locationCount\""), result.diagnosticsJson());
        }
    }
}
```

- [ ] **Step 2: Run smoke test disabled path**

Run:

```bash
mvn -q -Dtest=PaperReadingModelDataPdfSmokeTest test
```

Expected: PASS with the test skipped because `paperloom.reading-model.real-pdf` is not set.

- [ ] **Step 3: Run smoke test with local MinerU sidecar**

Start local MinerU sidecar first. Then run:

```bash
mvn -q -Dtest=PaperReadingModelDataPdfSmokeTest -Dpaperloom.reading-model.real-pdf=true test
```

Expected: PASS when MinerU is available and the selected `data/*.pdf` files parse into readable numbered text.

If this fails because MinerU is unavailable, record the exact sidecar error and continue with unit/integration tests. If it fails because parsed elements lack page numbers or text, update `diagnosticsJson` and revise `docs/superpowers/specs/2026-07-04-paperloom-reading-model-implementation-spec.md` from the observed output.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelDataPdfSmokeTest.java
git commit -m "test: add reading model real pdf smoke"
```

### Task 6: Final Verification

**Files:**
- Verify all files from Tasks 1-5.
- Modify docs only if real MinerU smoke reveals a stable schema correction.

**Interfaces:**
- Consumes: full Reading Model implementation.
- Produces: verified implementation of `docs/superpowers/specs/2026-07-04-paperloom-reading-model-implementation-spec.md`.

- [ ] **Step 1: Run focused tests**

Run:

```bash
mvn -q -Dtest=PaperReadingModelRepositoryTest,PaperReadingModelBuilderTest,PaperReadingModelServiceTest,ParseServiceStructuredParserTest test
```

Expected: PASS.

- [ ] **Step 2: Compile backend**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: PASS.

- [ ] **Step 3: Check whitespace**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Confirm scope stayed narrow**

Run:

```bash
git diff --stat
git diff --name-only
```

Expected changed files are limited to Reading Model entities, repositories, builder/service/tests,
`ParseService`, and any spec update caused by real MinerU observations. There should be no changes
to ReAct tools, Elasticsearch search, Source Quote, Answer Guard, frontend, or eval corpus storage.

- [ ] **Step 5: Commit final spec correction only if needed**

If real MinerU smoke changed the model contract, commit the spec update:

```bash
git add docs/superpowers/specs/2026-07-04-paperloom-reading-model-implementation-spec.md
git commit -m "docs: record reading model mineru observations"
```

If no spec correction was needed, do not create a docs-only commit.
