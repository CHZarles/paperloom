# Paper Reading Model Sample - data/2412.08972.pdf

Date: 2026-07-05

This note records one concrete paper's current Reading Model shape so the modeling choices can be
checked against product expectations.

## Paper

PDF:

```text
data/2412.08972.pdf
```

Title detected from MinerU heading:

```text
RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios
```

Evidence used:

```text
target/figure-caption-probe/reading-model-summary.jsonl
target/figure-caption-probe/raw/2412.08972/result.zip
docs/qa/figure-caption-capture-probe-2026-07-05.md
target/paper-reading-model-element-retention-audit/summary.jsonl
```

The counts below use the rerun after the figure-caption mapper fix, so `image_caption` and guarded
`chart_caption` are active. They also include the element-retention phase, so non-location parser
objects are now persisted as Reading Model Elements instead of being discarded.

## Model Summary

The paper reaches:

```text
modelStatus = READING_MODEL_READY
parserName = MinerU
parserVersion = self-hosted
```

Aggregate counts:

| Metric | Value |
| --- | ---: |
| Metadata pages | 23 |
| Readable pages | 20 |
| PAGE locations | 20 |
| Parsed elements | 209 |
| Parsed tables | 10 |
| Parsed figure/chart objects | 16 |
| Parsed formulas | 7 |
| SECTION rows | 38 |
| SECTION locations | 38 |
| TABLE locations | 10 |
| FIGURE locations | 8 |
| Structured locations, excluding PAGE | 56 |
| All locations | 76 |
| Retained Reading Model Elements | 209 |
| Retained elements with searchable text | 186 |
| Retained TABLE elements | 10 |
| Retained IMAGE/CHART elements | 16 |
| Retained FORMULA elements | 7 |
| Retained panel-only chart elements | 7 |
| Attached panel-only chart elements | 7 |
| Ambiguous panel-only chart elements | 0 |
| Unattached panel-only chart elements | 0 |
| Readable characters | 56,115 |
| Page-text elements omitted for missing page number | 0 |
| Page-text elements omitted for blank text | 30 |
| Table locations not created for no page/id/blank payload | 0 |
| Table locations not created | 0 |
| Figure locations not created | 8 |
| Formula locations deferred | 7 |
| Pages without readable text | 3 |
| Any bbox present | true |

Raw `content_list` inspection is consistent with the 3 unreadable pages: pages 11-13 contain only
blank list items in the current MinerU output. The current audit records only the count
`pagesWithoutText=3`; exact unreadable page numbers are one reason the next element-retention spec
exists.

## Location Model

Current location types for this paper:

```text
PAGE    -> page_ref_...
SECTION -> section_ref_...
TABLE   -> table_ref_...
FIGURE  -> figure_ref_...
```

Each location is stored in `paper_locations` with:

```text
paperId
modelVersion
locationType
pageNumber
pageEndNumber
sourceObjectId for non-PAGE locations
displayOrder
sourceSpanJson
contentKind
```

For this sample:

- PAGE locations are required and match readable pages one-to-one.
- SECTION locations point to `PaperSection.sectionId`.
- TABLE locations point to `ParsedPaperTable.tableId`.
- FIGURE locations point to `ParsedPaperFigure.figureId`.
- Location refs are opaque runtime ids. They are not expected to be stable after a rebuild.

## Section Modeling

Section modeling is present and persisted as `paper_sections`.

Builder rule used:

```text
heading / sectionTitle signals
-> PaperSection(sectionText, page range, reading-order range)
-> SECTION PaperLocation(sourceObjectId = sectionId)
```

Detected section starts from the raw MinerU heading signals:

| Page | Section title |
| ---: | --- |
| 1 | RULEARENA: A Benchmark for Rule-Guided Reasoning with LLMs in Real-World Scenarios |
| 1 | Abstract |
| 1 | 1 Introduction |
| 2 | 2 Related Work |
| 3 | 3 RuleArena |
| 3 | 3.1 Domains and Rule Collection |
| 4 | 3.2 Problem Annotation |
| 4 | 3.3 Difficulty control |
| 4 | 3.4 Evaluation Metrics |
| 5 | 4 Experiments |
| 5 | 4.1 Experiment Settings |
| 5 | 4.2 Main Results |
| 6 | 4.2.1 Problem-wise Analysis |
| 7 | 4.2.2 Rule-wise Analysis |
| 8 | 4.3 In-Depth Analyses |
| 8 | 4.3.1 What Impacts Rule Following? |
| 9 | 4.3.2 Case Studies |
| 9 | 5 Conclusions |
| 10 | Limitations |
| 10 | References |
| 14 | A Terminology Explanation in NBA |
| 14 | B Data Collection and Annotation |
| 14 | B.1 Rule Collection |
| 15 | B.2 NBA Data Annotation |
| 15 | The format of annotated NBA test problems. |
| 15 | C Structured Rule Extraction in Each Scenario |
| 16 | NBA. In NBA domain we let the LLM parser decide whether each of the 54 rules is applied. |
| 16 | Tax. In tax domain we just list each line in Form 1040 and its Schedules 1-3 for parsing. |
| 17 | D More Experiment Results and Analysis |
| 17 | D.1 Rule-Wise Statistics |
| 20 | D.2 What Impacts Rule Following? |
| 20 | D.2.1 Correlation Between Accuracy and Other Metrics |
| 20 | D.2.2 Do In-Context Examples Help? |
| 21 | D.2.3 Does Rule Representation Matter? |
| 21 | D.2.4 Do Distractive Rules Matter? |
| 22 | D.2.5 Can Tool Augmentation Help? |
| 22 | D.2.6 Summary of Factors that Influence Rule-Guided Following |
| 23 | E LLM Prompts |

Human check points:

- The main paper outline looks useful.
- Appendix headings are captured.
- Some long sentence-like headings are accepted because MinerU marks them with `text_level`.
  Examples are the NBA and Tax parser-rule lines on page 16. If those feel too noisy, the next
  section modeling rule should add a heading-quality guard rather than trusting `text_level`
  blindly.

## Table Modeling

All 10 parsed table objects became TABLE locations.

Builder rule used:

```text
valid tableId
valid pageNumber
nonblank tableText OR tableMarkdown OR caption
-> TABLE PaperLocation(sourceObjectId = tableId)
```

Detected tables:

| Page | Caption / parser label | Modeled |
| ---: | --- | --- |
| 3 | Table 1: Statistics of rules in each domain. | yes |
| 4 | Table 2: Number of test problems at different difficulty levels in each domain. | yes |
| 6 | Table 3: Main problem-wise evaluation results on airline, NBA, and tax domains. | yes |
| 7 | Table 4: Statistics of our three rule-wise metrics. | yes |
| 8 | blank caption, nonblank table body | yes |
| 8 | Table 5 and Table 6 combined in one parser object | yes |
| 8 | Table 7: Top-5 rules of the lowest precision in ascent order of precision. | yes |
| 21 | Table 8: 0-shot and 1-shot rule-wise comparison. | yes |
| 21 | Table 9: Results of different LLMs given different rule representations. | yes |
| 22 | Table 10: Results of different LLMs with tool augmentation on airline tasks. | yes |

Human check points:

- This paper has no parsed tables that were retained without a TABLE location.
- A parser object with blank caption but nonblank body still becomes a TABLE location. That seems
  desirable if the table body is readable.
- MinerU merged "Table 5" and "Table 6" into one table object, so the current Reading Model creates
  one TABLE location for that combined parser object. If the product expectation is one location per
  human-visible table number, this needs a later table-splitting rule.

## Reading Element Retention

The Reading Model now persists `paper_reading_elements` rows for parser-derived content, including
objects that do not deserve their own top-level navigation location.

For this sample:

| Retained element type | Count | Location behavior |
| --- | ---: | --- |
| Text/title/heading/list/etc. | 176 | retained as page/section text elements |
| TABLE | 10 | all 10 have TABLE locations |
| IMAGE/CHART | 16 | 8 have FIGURE locations, 8 are retained without own FIGURE location |
| FORMULA | 7 | retained with `FORMULA_LOCATION_DEFERRED` |

Important rule:

```text
not a TABLE/FIGURE location
!=
not retained
```

Searchable examples from retained panel-only chart elements:

```text
(a) Recall
(b) Precision
(a) Airline
(b) NBA
(a) Correctness
```

When a retained child has no own `locationRef` but has `parentLocationRef`, search should route the
match to the parent TABLE/FIGURE location and expose the child text as the matched element.

## Figure Modeling

The paper has 16 parsed image/chart objects. Current rules create 8 FIGURE locations.

Builder rule used:

```text
valid figureId
valid pageNumber
nonblank caption OR figureText
-> FIGURE PaperLocation(sourceObjectId = figureId)
```

Caption mapper rule used:

```text
image items -> image_caption
chart items -> chart_caption only if the array contains a full Figure/Fig./图 caption
panel-only labels stay blank
```

Modeled figures:

| Page | Parser type | Caption source | Modeled as FIGURE location |
| ---: | --- | --- | --- |
| 2 | image | Figure 1: Overview of RULEARENA... | yes |
| 7 | chart | Figure 2: Rule-wise metrics of rules in airline domain. | yes |
| 9 | image | Figure 3: Failure Case Studies. | yes |
| 18 | chart | Figure 4: Rule-wise metrics of rules in airline domain. | yes |
| 19 | chart | Figure 5: Rule-wise metrics of rules in NBA domain. | yes |
| 19 | chart | Figure 6: Rule-wise metrics of rules in tax domain. | yes |
| 20 | chart | Figure 7: Correlation between problem-wise metrics and accuracy. | yes |
| 22 | chart | Figure 8: The effect of distractive rules and context length. | yes |

Retained figure/chart objects without their own FIGURE location:

| Page | Parser type | Searchable retained text | Location reason | Parent association |
| ---: | --- | --- | --- | --- |
| 7 | chart | blank | blank searchable text | retained from raw parser object |
| 18 | chart | `(a) Recall` | panel-only caption | attached to parent FIGURE |
| 18 | chart | `(a) Recall` | panel-only caption | attached to parent FIGURE |
| 19 | chart | `(b) Precision` | panel-only caption | attached to parent FIGURE |
| 20 | chart | `(a) Airline` | panel-only caption | attached to parent FIGURE |
| 20 | chart | `(b) NBA` | panel-only caption | attached to parent FIGURE |
| 22 | chart | `(a) Correctness` | panel-only caption | attached to parent FIGURE |
| 22 | chart | `(b) Recall` | panel-only caption | attached to parent FIGURE |

Human check points:

- The model treats a full figure caption as the location boundary.
- Panel labels are not separate FIGURE locations unless they appear in the same `chart_caption`
  array as a full figure caption.
- This avoids noisy locations like "FIGURE: (a) Recall", but it also means individual panels are not
  independently addressable as top-level locations.
- Panel-only chart labels are now persisted as child Reading Model Elements. For this PDF, all 7
  panel-only labels were deterministically attached to a parent FIGURE.
- If the product needs panel-level navigation later, it should be a separate concept from FIGURE
  location, probably with a panel id or sub-location.

## Formula Modeling

MinerU produced 7 equation/formula objects.

Current behavior:

- Formula text is included in PAGE / SECTION readable text when MinerU provides text.
- No FORMULA `PaperLocation` is created.
- Each parsed formula is persisted as a searchable `PaperReadingElement`.
- Formula-specific reading/citation policy is explicitly deferred.

Human check point:

- If formula navigation is important soon, it needs a separate FORMULA location phase. It should not
  be hidden inside the current TABLE/FIGURE rules.

## What This Sample Does Not Prove

This sample does not prove:

- full product upload / Kafka / MinIO / Elasticsearch behavior
- `read_locations`
- `find_reading_locations`
- Source Quote creation
- answer citation behavior
- table/figure content version alignment
- figure-caption nearest-neighbor recovery

It proves only the current parser-to-Reading-Model structure path for this one PDF.

## Decision Checklist

Use this sample to check the product shape:

1. Are PAGE locations enough as the readiness baseline even when some metadata pages have no text?
2. Should section headings trust MinerU `text_level`, or do we need a heading-quality guard?
3. Is one SECTION location per heading signal the right granularity?
4. Is one TABLE location per MinerU table object acceptable, even when one object contains two human
   table labels?
5. Should blank-caption but nonblank-body tables become TABLE locations? Current answer: yes.
6. Should a full figure caption be the FIGURE location boundary? Current answer: yes.
7. Should panel-only chart labels become separate locations? Current answer: no, but they must be
   persisted as searchable child Reading Model Elements attached to the corresponding figure/table
   when deterministic. For this PDF, all 7 panel-only chart labels attach to parent FIGURE rows.
8. Should formulas remain embedded in page/section text for now? Current answer: yes.
9. Is it acceptable that this phase records structure but still does not create Source Quotes or
   retrieval chunks? Current answer: yes.
