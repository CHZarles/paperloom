import assert from 'node:assert/strict';
import {
  buildPdfEvidenceRects,
  evidencePageNumber,
  parsePdfEvidenceBboxJson,
  resolvePdfEvidenceRegions
} from '../src/components/custom/pdf-evidence-regions';

const fallbackBox = JSON.stringify({
  pageNumber: 4,
  left: 100,
  top: 200,
  right: 300,
  bottom: 500,
  unit: 'mineru_1000',
  coordinateSystem: 'top_left_1000'
});

assert.deepEqual(parsePdfEvidenceBboxJson(fallbackBox), {
  pageNumber: 4,
  left: 100,
  top: 200,
  right: 300,
  bottom: 500,
  unit: 'mineru_1000',
  coordinateSystem: 'top_left_1000',
  targetKind: null,
  confidence: null
});

assert.equal(
  evidencePageNumber({
    bboxJson: fallbackBox,
    visualRegions: [
      {
        page_number: '6',
        left: 10,
        top: 10,
        right: 100,
        bottom: 100,
        coordinate_system: 'top-left-1000',
        target_kind: 'QUOTE',
        confidence: 'EXACT'
      }
    ]
  }),
  6
);

const resolvedRegions = resolvePdfEvidenceRegions({
  currentPage: 4,
  bboxJson: fallbackBox,
  visualRegions: [
    {
      pageNumber: 4,
      left: 95,
      top: 190,
      right: 810,
      bottom: 620,
      unit: 'mineru_1000',
      coordinateSystem: 'top_left_1000',
      targetKind: 'ELEMENT',
      confidence: 'APPROXIMATE'
    },
    {
      pageNumber: 4,
      left: 120,
      top: 250,
      right: 420,
      bottom: 290,
      unit: 'mineru_1000',
      coordinateSystem: 'top_left_1000',
      targetKind: 'QUOTE',
      confidence: 'EXACT'
    },
    {
      pageNumber: 4,
      left: 120,
      top: 304,
      right: 360,
      bottom: 340,
      unit: 'mineru_1000',
      coordinateSystem: 'top_left_1000',
      targetKind: 'QUOTE',
      confidence: 'EXACT'
    },
    {
      pageNumber: 5,
      left: 10,
      top: 10,
      right: 100,
      bottom: 100,
      unit: 'mineru_1000',
      coordinateSystem: 'top_left_1000',
      targetKind: 'QUOTE',
      confidence: 'EXACT'
    }
  ]
});

assert.equal(resolvedRegions.length, 2, 'exact quote regions should beat broad element/fallback boxes');
assert.deepEqual(
  buildPdfEvidenceRects({
    regions: resolvedRegions,
    displayWidth: 600,
    displayHeight: 800
  }).map(rect => ({
    left: Number(rect.left.toFixed(3)),
    top: Number(rect.top.toFixed(3)),
    width: Number(rect.width.toFixed(3)),
    height: Number(rect.height.toFixed(3))
  })),
  [
    {
      left: 72,
      top: 200,
      width: 180,
      height: 32
    },
    {
      left: 72,
      top: 243.2,
      width: 144,
      height: 28.8
    }
  ]
);

assert.deepEqual(
  resolvePdfEvidenceRegions({
    currentPage: 7,
    bboxJson: fallbackBox,
    visualRegions: []
  }),
  [],
  'region coordinates must not leak onto another PDF page'
);
