# Figure Caption Capture Probe - 2026-07-05

## Question

The structured Reading Model audit found 193 parsed figure/chart objects but 0 FIGURE locations.
The builder skipped them because every `ParsedPaperFigure` had blank `caption` and `figureText`.

This probe checks whether the missing caption problem is caused by absent captions in the PDF, or by
the MinerU mapper not reading the fields used by the current MinerU output.

## Evidence Source

Four real PDFs were reparsed with the local MinerU sidecar at `http://127.0.0.1:8010` and raw result
zips were saved under:

```text
target/figure-caption-probe/raw/
```

The probe sample:

| PDF | Raw figure/chart objects | Prior FIGURE locations | Prior blank figure skips |
| --- | ---: | ---: | ---: |
| `data/2601.09032v1.pdf` | 2 | 0 | 2 |
| `data/2408.15769.pdf` | 1 | 0 | 1 |
| `data/2503.16416.pdf` | 2 | 0 | 2 |
| `data/2412.08972.pdf` | 16 | 0 | 16 |
| Total | 21 | 0 | 21 |

## Root Cause

The captions are present in MinerU output, but under field names the mapper did not read.

Before the fix, `MinerUOutputMapper.caption(...)` read legacy/generic fields:

```text
table_caption
img_caption
caption
```

The current MinerU output in the sampled PDFs uses:

```text
image_caption
chart_caption
```

Observed examples:

```json
{
  "type": "image",
  "image_caption": [
    "Fig. 1: Typology of evaluation on multimodal large language models."
  ]
}
```

```json
{
  "type": "chart",
  "chart_caption": [
    "(a) Recall",
    "Figure 2: Rule-wise metrics of rules in airline domain."
  ]
}
```

So the failure mode was not "the PDF has no captions"; it was "the mapper ignored current MinerU
caption fields." Because this project is still in development and there is no stored user corpus to
preserve, the fix intentionally does not keep legacy aliases.

## Reliable Rule

Use only the current MinerU caption fields observed in real parser output. Do not infer from nearby
paragraph text unless these fields are absent, and do not keep compatibility aliases without fresh
evidence that they are needed.

Recommended capture rule:

1. For image/figure items:
   - read `image_caption`
2. For chart items:
   - read `chart_caption`
   - accept it only when at least one entry is a full figure caption, such as `Figure 2: ...`,
     `Fig. 2: ...`, or `图 2 ...`
   - keep panel labels like `(a) Recall` only when a full figure caption appears in the same
     `chart_caption` array
   - skip panel-only labels such as `(b) Correctness`
3. Use `text`, `image_text`, and `content` only as fallback figure text, not as the primary caption
   source.

The chart guard matters because MinerU often emits chart panels as separate objects. In
`data/2412.08972.pdf`, several `chart_caption` arrays contain only panel labels, not standalone
figure captions. Generating FIGURE locations for those would create noisy coordinates.

## Implementation

`MinerUOutputMapper` now uses field-aware caption extraction:

- `tableCaption(...)` for tables
- `figureCaption(...)` for image/figure/chart objects
- `chartCaption(...)` with full-caption detection to avoid panel-only false positives
- legacy `img_caption` and generic `caption` are ignored unless a future real-output probe proves
  they are needed

Regression coverage was added in:

```text
src/test/java/com/yizhaoqi/smartpai/paper/parser/MinerUOutputMapperTest.java
```

The regression fixture mirrors current MinerU output:

- `image_caption: ["Figure 1: ..."]`
- `chart_caption: ["(a) Recall", "Figure 2: ..."]`
- `chart_caption: ["(b) Correctness"]`
- ignored aliases: `img_caption` and generic `caption`

Expected behavior:

- image caption is captured
- chart caption with a full `Figure/Fig.` entry is captured
- panel-only chart label remains blank, so the builder can skip it
- legacy/generic aliases remain blank, so unsupported fields do not silently become product
  behavior

## Real PDF Effect

After the mapper fix, the 4-PDF real audit was rerun:

```bash
mvn -q \
  -Dtest=PaperReadingModelRealPdfAuditTest \
  -Dpaperloom.reading-model.audit=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  -Dpaperloom.reading-model.audit.pdfs=data/2601.09032v1.pdf,data/2408.15769.pdf,data/2503.16416.pdf,data/2412.08972.pdf \
  -Dpaperloom.reading-model.audit.output=target/figure-caption-probe/reading-model-summary.jsonl \
  test
```

Surefire result:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 119.7 s
```

Summary from `target/figure-caption-probe/reading-model-summary.jsonl`:

| PDF | Figure/chart objects | FIGURE locations after fix | Blank figure skips after fix |
| --- | ---: | ---: | ---: |
| `data/2601.09032v1.pdf` | 2 | 1 | 1 |
| `data/2408.15769.pdf` | 1 | 1 | 0 |
| `data/2503.16416.pdf` | 2 | 2 | 0 |
| `data/2412.08972.pdf` | 16 | 8 | 8 |
| Total | 21 | 12 | 9 |

The fix changed this sample from 0/21 figure objects becoming FIGURE locations to 12/21. The
remaining 9 skipped objects are expected under the reliability rule: they are empty chart objects or
panel-only chart labels, not standalone figure captions.

## Conclusion

The reliable first fix is not geometric caption association. The existing parsing data shows that
MinerU already emits explicit figure captions; the mapper was missing the current field names.

The next fallback, if needed for PDFs without explicit caption arrays, should be a second-stage
same-page nearest-neighbor association from text elements beginning with `Figure`, `Fig.`, or `图`.
That fallback should be evaluated separately because it has a higher false-positive risk than using
MinerU's explicit caption fields.
