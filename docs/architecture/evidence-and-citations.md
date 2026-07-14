# Evidence and Citation Model

PaperLoom separates parser output, readable paper structure, navigation candidates, read evidence,
and persisted answer references. They form one traceable chain, but they are not interchangeable.

## Evidence Chain

```text
MinerU parser artifact
-> PaperLoom Reading Model
-> authorized MySQL corpus projection
-> disclosed paper
-> disclosed reading location
-> exact location read
-> Evidence ID
-> validated final answer
-> persistent product reference
-> reopened paper evidence
```

### Parser Artifact

MinerU's raw structured output, Markdown, images, and archive. It is retained for reproducibility and
debugging. A parser artifact is not a citeable answer source by itself.

### PaperLoom Reading Model

The canonical product representation of a paper. It preserves model version, page number, reading
order, sections, typed elements, navigation locations, parser provenance, relationships, source
spans, and visual assets without making a parser's raw schema the product contract.

### Authorized Corpus Projection

A request-local Python projection built only from the paper IDs Java authorized for the turn. The
current live projection contains paper metadata, current ready-model metadata, Reading Elements,
and visual-asset availability. It is loaded directly from MySQL and searched in memory.

### Candidate And Navigation Preview

`search_paper_candidates`, `find_papers_by_identity`, and `find_reading_locations` reveal which
papers and locations the model may consider next. Their cards and text previews are navigation
material, not paper-content evidence.

### Read Evidence

`read_locations` is the only current content tool that creates citeable evidence. Each returned item
contains an Evidence ID, paper identity, location, section, page when known, element type, exact span
text, parser provenance, and visual-asset availability.

An Evidence ID is deterministic for the current paper, location, element type, and page identity. It
is also entered into the run's Evidence Ledger so final validation can distinguish known evidence
from invented citations.

### Validated Answer

The model finishes through `submit_research_answer`, not by returning arbitrary terminal text. The
submission gate checks:

- the outcome and answer shape;
- citation syntax and Evidence ID existence;
- whether a content answer that read evidence also cites it;
- Candidate, Read, Cited, and substantive-evidence coverage for papers claimed in the answer;
- that final submission is the only tool call in the final step.

After acceptance, the harness renders numeric references from structured Evidence IDs. The model
does not invent the final reference numbers or a free-form Sources section.

### Persistent Product Reference

Java converts cited evidence into product reference mappings and persists them with the durable
conversation. Historical resolution follows the stored mapping rather than a transient model
context or in-memory tool result:

```text
conversation message
-> reference number and evidence identity
-> paper and location metadata
-> page, text, table, figure, chart, or formula evidence
```

Current permission checks still apply when a historical reference is reopened.

## Authorization Rules

- Java authorization precedes all corpus loading.
- The conversation's paper scope is fixed before Python begins research.
- A paper must be disclosed by candidate search or an unambiguous identity lookup before its
  locations can be searched.
- A location must be disclosed before it can be read.
- Candidate previews never authorize a paper-content claim.
- A historical reference cannot bypass current paper access.
- Ambiguous paper identities require clarification rather than arbitrary selection.

## Evaluation Funnel

The evaluation system distinguishes four evidence stages:

| Stage | Question |
| --- | --- |
| Candidate | Did location search expose a required paper location? |
| Read | Did the Agent open that location and create evidence? |
| Cited | Did the final answer cite the resulting Evidence ID? |
| Hard pass | Did outcome, content, evidence, citation, and trace obligations all pass? |

This separation matters because a retrieval improvement can restore Candidate coverage without
improving what the Agent chooses to read or cite.

## Related Reading

- [Reading Model and Agent Tools](reading-model-and-agent-tools.md)
- [Architecture Overview](overview.md)
- [Evaluation System](../evaluation/README.md)
- [ADR 0003: Paper locations](../adr/0003-use-paper-locations-for-reading-structure.md)
- [ADR 0004: Canonical reading elements](../adr/0004-use-reading-elements-as-canonical-typed-content.md)
- [ADR 0009: Ambiguous paper identities](../adr/0009-do-not-authorize-ambiguous-paper-identity-matches.md)
