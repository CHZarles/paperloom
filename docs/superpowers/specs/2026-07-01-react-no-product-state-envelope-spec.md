# ReAct No-Product-State Envelope Fix Spec

Status: proposed
Date: 2026-07-01

## Problem

PaperLoom chat can show the user-facing error:

```text
AI服务暂时不可用，请稍后重试
```

for requests that do not require paper retrieval or product-state lookup, for example:

```text
hi
```

Runtime trace shows the actual backend failure is:

```text
Answer envelope is not grounded in required product tools.
```

This is not an OCR, MinerU, database, or external LLM availability problem. The LLM provider returned
successfully. The failure happens inside `ProductReActHarness` final `AnswerEnvelope` validation.

Relevant observed trace:

```text
conversationId=2fd87db2-f880-414f-9984-ed88e43df0cc
generationId=0cfbf39a-a733-4892-9139-8adb5bf8cf7c
userMessage=hi
tool=answer_without_product_state
toolEffect=NO_PRODUCT_STATE
modelFinalAnswerType=PRODUCT_STATE
resultStatus=FAILED
stopReason=CITATION_VALIDATION_FAILED
```

## Root Cause

The ReAct contract is internally inconsistent.

`answer_without_product_state` correctly returns:

```text
ProductToolEffect.NO_PRODUCT_STATE
```

That effect means:

```text
the answer does not depend on product state, paper identity, references, pages, citations, or paper evidence
```

The validator correctly does not allow this effect to ground:

```text
AnswerType.PRODUCT_STATE
```

because `PRODUCT_STATE` must be grounded by real product-state tools such as:

```text
get_system_state
get_session_scope
list_papers
get_paper_metadata
```

However, the Harness prompt and correction messages over-emphasize the `PRODUCT_STATE` JSON example
and do not explicitly bind:

```text
NO_PRODUCT_STATE -> NON_EVIDENCE
```

As a result, the model can repeatedly output `PRODUCT_STATE` after `answer_without_product_state`.
The validator rejects it, and `ChatHandler` wraps the internal Harness failure as a generic AI
service error.

## Required Behavior

### Tool Effect To Answer Type Mapping

The Harness must enforce and explain this mapping:

| Tool effect | Allowed final answer type | Notes |
| --- | --- | --- |
| `NO_PRODUCT_STATE` | `NON_EVIDENCE` | For smalltalk, UI help, unsupported non-paper requests, or clarification that does not depend on product state. |
| `PRODUCT_STATE` | `PRODUCT_STATE` | For library state, processing state, retrieval state, session state, collection state. |
| `SESSION_SCOPE` | `PRODUCT_STATE` | Scope is product state, not paper evidence. |
| `PAPER_LIST` | `PRODUCT_STATE` | Paper listing is product state and metadata, not citeable paper content. |
| `PAPER_METADATA` | `PRODUCT_STATE` | Bibliographic/product metadata, not citeable paper content. |
| `PAPER_RESOLUTION` | `PRODUCT_STATE` or next evidence tool | If the user asked only to identify a paper, `PRODUCT_STATE`; if they asked content, continue to evidence. |
| `EVIDENCE` | `EVIDENCE_ANSWER` or `INSUFFICIENT_EVIDENCE` | Must use valid tool-returned evidence refs for `EVIDENCE_ANSWER`. |
| `REFERENCE` | `EVIDENCE_ANSWER` or `INSUFFICIENT_EVIDENCE` | Must use valid reference/evidence data returned by the tool. |
| `PAGE` | `EVIDENCE_ANSWER` or `INSUFFICIENT_EVIDENCE` | Must use valid page/evidence data returned by the tool. |

### `answer_without_product_state`

After this tool succeeds:

```text
answerType must be NON_EVIDENCE
evidenceBasedClaims must be []
stateClaims must be []
final answer must not include paper counts, paper titles, filenames, processing status, pages, citations, or evidence claims
```

Valid example:

```json
{
  "answerType": "NON_EVIDENCE",
  "answer": "Hi. I can help you read, compare, and inspect papers in your PaperLoom library.",
  "evidenceBasedClaims": [],
  "stateClaims": [],
  "limitations": [],
  "nonEvidenceNotes": ["Smalltalk response; no product state or paper evidence was used."],
  "missingFields": [],
  "reason": "Handled with answer_without_product_state."
}
```

Invalid example:

```json
{
  "answerType": "PRODUCT_STATE",
  "answer": "Hi. I can help you with your paper library.",
  "evidenceBasedClaims": [],
  "stateClaims": [
    {
      "claim": "Smalltalk; no product state was retrieved.",
      "sourceTool": "answer_without_product_state"
    }
  ],
  "limitations": [],
  "nonEvidenceNotes": [],
  "missingFields": [],
  "reason": ""
}
```

Reason invalid:

```text
answer_without_product_state is not a product-state grounding tool.
```

### Product State Questions

Questions such as:

```text
有多少论文可以检索
当前 session scope 是什么
有哪些论文
```

must still call product-state tools and return:

```text
AnswerType.PRODUCT_STATE
```

They must not be answered through `answer_without_product_state`.

### Paper Content Questions

Questions such as:

```text
LoRA 的核心方法是什么
这篇论文的实验结果是什么
解释 citation [1]
```

must still use evidence/reference/page tools and return:

```text
AnswerType.EVIDENCE_ANSWER
```

or:

```text
AnswerType.INSUFFICIENT_EVIDENCE
```

They must not be answered through `answer_without_product_state`.

## Non-Goals

Do not:

```text
hardcode user phrases such as "hi"
add fallback final answers
skip ReAct tools
relax citation validation
allow NO_PRODUCT_STATE to ground PRODUCT_STATE
change OCR/MinerU behavior
change paper ingestion behavior
change frontend citation rendering
```

## Code Change Scope

Primary file:

```text
src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java
```

Required changes:

1. Update `systemPrompt(...)` to document the effect-to-answer-type mapping.
2. Replace the single `PRODUCT_STATE` schema example with either a neutral schema or multiple explicit examples.
3. Update `toolResultPolicyMessage(...)` so it emits effect-specific instructions:
   - `NO_PRODUCT_STATE` -> require `NON_EVIDENCE`
   - product-state effects -> require `PRODUCT_STATE`
   - evidence effects -> require `EVIDENCE_ANSWER` or `INSUFFICIENT_EVIDENCE`
4. Update `answerEnvelopeCorrectionMessage(...)` so correction text can reference the successful tool effects already present in the turn.
5. Preserve `validToolGrounding(...)` strictness.

Test file:

```text
src/test/java/com/yizhaoqi/smartpai/service/ProductReActHarnessTest.java
```

Required tests:

1. `NO_PRODUCT_STATE` correction test:
   - model calls `answer_without_product_state`
   - tool returns `NO_PRODUCT_STATE`
   - model incorrectly outputs `PRODUCT_STATE`
   - Harness correction message contains `NO_PRODUCT_STATE` and `NON_EVIDENCE`
   - model then outputs `NON_EVIDENCE`
   - result completes

2. `NO_PRODUCT_STATE` direct success test:
   - model calls `answer_without_product_state`
   - model outputs `NON_EVIDENCE`
   - result completes

3. Product-state regression test:
   - model calls `get_system_state` or `list_papers`
   - model outputs `PRODUCT_STATE`
   - result completes

4. Product-state misuse regression test:
   - model calls `answer_without_product_state`
   - model outputs `PRODUCT_STATE`
   - if model never corrects to `NON_EVIDENCE`, result remains failed

## Acceptance Criteria

The fix is complete only when all are true:

1. Smalltalk no longer fails as a generic AI service error.
2. `answer_without_product_state` cannot produce a valid `PRODUCT_STATE` answer.
3. Product-state questions still require product-state tools.
4. Evidence answers still require valid evidence refs returned by tools.
5. The validator remains strict; no fallback answer path is introduced.
6. The runtime trace shows the tool call process normally, for example:

```text
calling answer_without_product_state
```

7. Browser-visible response for `hi` is a normal assistant response, not:

```text
AI服务暂时不可用，请稍后重试
```

8. Browser-visible response for `有多少论文可以检索` still goes through product-state tools.

## Verification Commands

Run focused backend tests:

```bash
mvn -q -Dtest=ProductReActHarnessTest test
```

Compile backend:

```bash
mvn -q -DskipTests compile
```

Runtime verification:

```text
Open http://localhost:9527/#/chat
Send: hi
Expected: normal non-evidence assistant response.
Expected network/generation state: no failed generation with "Answer envelope is not grounded in required product tools."

Send: 有多少论文可以检索
Expected: product-state answer using product-state tool path.
Expected: no evidence citations unless a paper evidence tool was actually used.
```

## Expected User Experience Impact

Positive:

```text
Smalltalk, UI help, and unsupported non-paper requests stop surfacing as fake AI service outages.
```

No intended change:

```text
paper retrieval quality
citation strictness
reference registry behavior
paper upload/OCR/indexing
product-state grounding requirements
```

## Risk

Main risk:

```text
Prompt changes could over-bias the model toward NON_EVIDENCE.
```

Mitigation:

```text
Product-state and evidence instructions must remain explicit and tests must verify those paths still work.
```

## Decision Needed

Approve this spec before implementation.

