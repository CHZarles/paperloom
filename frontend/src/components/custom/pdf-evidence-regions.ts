export interface PdfEvidenceRegionLike {
  pageNumber?: number | string | null;
  page_number?: number | string | null;
  left?: number | string | null;
  top?: number | string | null;
  right?: number | string | null;
  bottom?: number | string | null;
  unit?: string | null;
  coordinateSystem?: string | null;
  coordinate_system?: string | null;
  targetKind?: string | null;
  target_kind?: string | null;
  confidence?: string | null;
}

export interface PdfEvidenceRegion {
  pageNumber?: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  unit?: string | null;
  coordinateSystem: 'top_left_1000';
  targetKind?: string | null;
  confidence?: string | null;
}

export interface PdfEvidenceRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

export function parsePdfEvidenceBboxJson(raw?: string | null) {
  if (!raw) return null;

  try {
    return normalizePdfEvidenceRegion(JSON.parse(raw));
  } catch {
    return null;
  }
}

export function normalizePdfEvidenceRegion(raw: unknown): PdfEvidenceRegion | null {
  if (!raw || typeof raw !== 'object') return null;

  const region = raw as PdfEvidenceRegionLike;
  const left = finiteNumber(region.left);
  const top = finiteNumber(region.top);
  const right = finiteNumber(region.right);
  const bottom = finiteNumber(region.bottom);

  if (left === null || top === null || right === null || bottom === null) {
    return null;
  }

  if (normalizeCoordinateSystem(region.coordinateSystem ?? region.coordinate_system) !== 'top_left_1000') {
    return null;
  }

  const normalizedLeft = Math.min(left, right);
  const normalizedRight = Math.max(left, right);
  const normalizedTop = Math.min(top, bottom);
  const normalizedBottom = Math.max(top, bottom);

  if (normalizedRight <= normalizedLeft || normalizedBottom <= normalizedTop) {
    return null;
  }

  const pageNumber = finiteNumber(region.pageNumber ?? region.page_number);
  const targetKind = region.targetKind ?? region.target_kind;

  return {
    pageNumber: pageNumber && pageNumber > 0 ? Math.round(pageNumber) : undefined,
    left: normalizedLeft,
    top: normalizedTop,
    right: normalizedRight,
    bottom: normalizedBottom,
    unit: typeof region.unit === 'string' ? region.unit : null,
    coordinateSystem: 'top_left_1000',
    targetKind: typeof targetKind === 'string' ? targetKind : null,
    confidence: typeof region.confidence === 'string' ? region.confidence : null
  };
}

export function resolvePdfEvidenceRegions(options: {
  bboxJson?: string | null;
  visualRegions?: PdfEvidenceRegionLike[] | null;
  currentPage?: number;
}) {
  const visualRegions = (options.visualRegions || [])
    .map(region => normalizePdfEvidenceRegion(region))
    .filter((region): region is PdfEvidenceRegion => Boolean(region))
    .filter(region => matchesPage(region, options.currentPage));

  if (visualRegions.length) {
    const bestPriority = Math.min(...visualRegions.map(regionPriority));
    return visualRegions.filter(region => regionPriority(region) === bestPriority);
  }

  const fallbackRegion = parsePdfEvidenceBboxJson(options.bboxJson);
  if (!fallbackRegion || !matchesPage(fallbackRegion, options.currentPage)) {
    return [];
  }

  return [fallbackRegion];
}

export function evidencePageNumber(options: {
  bboxJson?: string | null;
  visualRegions?: PdfEvidenceRegionLike[] | null;
}) {
  const visualPage = (options.visualRegions || [])
    .map(region => normalizePdfEvidenceRegion(region))
    .filter((region): region is PdfEvidenceRegion => Boolean(region))
    .sort((left, right) => regionPriority(left) - regionPriority(right))
    .map(region => region.pageNumber)
    .find(pageNumber => Boolean(pageNumber));

  if (visualPage) {
    return visualPage;
  }

  return parsePdfEvidenceBboxJson(options.bboxJson)?.pageNumber;
}

export function buildPdfEvidenceRects(options: {
  regions: PdfEvidenceRegion[];
  displayWidth: number;
  displayHeight: number;
}) {
  const { displayWidth, displayHeight } = options;
  if (displayWidth <= 0 || displayHeight <= 0) {
    return [];
  }

  return options.regions
    .map(region => {
      const left = clamp(region.left, 0, 1000);
      const top = clamp(region.top, 0, 1000);
      const right = clamp(region.right, left + 0.1, 1000);
      const bottom = clamp(region.bottom, top + 0.1, 1000);

      return {
        left: (left / 1000) * displayWidth,
        top: (top / 1000) * displayHeight,
        width: ((right - left) / 1000) * displayWidth,
        height: ((bottom - top) / 1000) * displayHeight
      };
    })
    .filter(rect => rect.width >= 1 && rect.height >= 1);
}

function regionPriority(region: PdfEvidenceRegion) {
  const targetKind = (region.targetKind || '').toUpperCase();
  const confidence = (region.confidence || '').toUpperCase();

  if (targetKind === 'QUOTE' && confidence === 'EXACT') return 0;
  if (targetKind === 'QUOTE') return 1;
  if (targetKind === 'ELEMENT' && confidence === 'EXACT') return 2;
  if (targetKind === 'ELEMENT') return 3;
  if (targetKind === 'LOCATION') return 4;
  return 5;
}

function matchesPage(region: PdfEvidenceRegion, currentPage?: number) {
  return !currentPage || !region.pageNumber || region.pageNumber === currentPage;
}

function normalizeCoordinateSystem(value?: string | null) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[-\s]+/g, '_');
}

function finiteNumber(value: unknown) {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }

  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  return null;
}

function clamp(value: number, min: number, max: number) {
  if (max < min) {
    return min;
  }
  return Math.min(Math.max(value, min), max);
}
