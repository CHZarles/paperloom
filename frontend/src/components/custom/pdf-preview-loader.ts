import { PDFDataRangeTransport } from 'pdfjs-dist/legacy/build/pdf.mjs';
import type { DocumentInitParameters } from 'pdfjs-dist/types/src/display/api';

interface FetchPdfPreviewBytesOptions {
  authorization?: string | null;
  fetchImpl?: typeof fetch;
  shouldAttachAuthHeaders?: (url: string) => boolean;
}

interface NormalizedFetchOptions {
  authorization: string | null;
  useStoredAuthorization: boolean;
  fetchImpl: typeof fetch;
  shouldAttachAuthHeaders: (url: string) => boolean;
}

interface PdfPreviewDataPayload {
  contentBase64?: string;
  contentType?: string;
  sourceFileSizeBytes?: number;
  chunkSizeBytes?: number;
  rangeUrl?: string;
  originalFilename?: string;
}

interface PdfPreviewDataResponse {
  data?: PdfPreviewDataPayload;
  contentBase64?: string;
  contentType?: string;
  sourceFileSizeBytes?: number;
  chunkSizeBytes?: number;
  rangeUrl?: string;
  originalFilename?: string;
}

interface PdfPreviewRangeDataResponse {
  data?: {
    contentBase64?: string;
  };
  contentBase64?: string;
}

interface PdfPreviewRangeRequest {
  metadata: PdfPreviewDataPayload;
  metadataUrl: string;
  begin: number;
  end: number;
  options: NormalizedFetchOptions;
}

interface StandardPdfRangePayload {
  initialData: Uint8Array;
  totalSizeBytes: number;
  url: string;
}

const activePdfByteLoads = new Map<string, Promise<Uint8Array>>();
const activePdfPreviewSourceLoads = new Map<string, Promise<PdfPreviewSourcePayload>>();
const activePdfRangeLoads = new Map<string, Promise<Uint8Array>>();
const pdfPreviewSourceCache = new Map<string, PdfPreviewSourcePayload>();
const pdfRangeCache = new Map<string, Uint8Array>();
let pdfPreviewSourceCacheBytes = 0;
let pdfRangeCacheBytes = 0;
const storagePrefix = import.meta.env?.VITE_STORAGE_PREFIX || '';
const fallbackPdfRangeChunkSize = 1024 * 1024;
const maxCachedPdfSourceEntries = 16;
const maxCachedPdfSourceBytes = 16 * 1024 * 1024;
const maxCachedPdfRangeBytes = 48 * 1024 * 1024;
const pdfRangeResponseAccept = 'application/pdf, application/json';

type PdfPreviewSourcePayload =
  | { metadata: PdfPreviewDataPayload; standardRange?: StandardPdfRangePayload }
  | { bytes: Uint8Array };

export async function fetchPdfPreviewBytes(url: string, options: FetchPdfPreviewBytesOptions = {}) {
  const normalizedOptions = normalizeFetchOptions(options);
  const shouldAttachAuth = normalizedOptions.shouldAttachAuthHeaders(url);
  const cacheKey = buildPdfCacheKey(url, {
    ...normalizedOptions,
    shouldAttachAuthHeaders: () => shouldAttachAuth
  });
  const existingLoad = activePdfByteLoads.get(cacheKey);

  if (existingLoad) {
    return cloneBytes(await existingLoad);
  }

  const load = requestPdfPreviewBytes(url, {
    ...normalizedOptions,
    shouldAttachAuthHeaders: () => shouldAttachAuth
  }).finally(() => {
    if (activePdfByteLoads.get(cacheKey) === load) {
      activePdfByteLoads.delete(cacheKey);
    }
  });

  activePdfByteLoads.set(cacheKey, load);
  return cloneBytes(await load);
}

export function clearPdfPreviewByteLoadCacheForTest() {
  activePdfByteLoads.clear();
  activePdfPreviewSourceLoads.clear();
  activePdfRangeLoads.clear();
  pdfPreviewSourceCache.clear();
  pdfRangeCache.clear();
  pdfPreviewSourceCacheBytes = 0;
  pdfRangeCacheBytes = 0;
}

export async function createPdfPreviewSource(
  url: string,
  options: FetchPdfPreviewBytesOptions = {}
): Promise<DocumentInitParameters> {
  const normalizedOptions = normalizeFetchOptions(options);
  const payload = await loadPdfPreviewSourcePayload(url, normalizedOptions);

  if ('metadata' in payload) {
    if (payload.standardRange) {
      return buildStandardPdfRangeSource(payload.standardRange, normalizedOptions);
    }
    return buildPdfRangeSource(url, payload.metadata, normalizedOptions);
  }

  return buildPdfDataSource(payload.bytes);
}

function normalizeFetchOptions(options: FetchPdfPreviewBytesOptions): NormalizedFetchOptions {
  return {
    authorization: options.authorization ?? null,
    useStoredAuthorization: options.authorization === null || options.authorization === undefined,
    fetchImpl: options.fetchImpl ?? ((input, init) => fetch(input, init)),
    shouldAttachAuthHeaders: options.shouldAttachAuthHeaders ?? (() => true)
  };
}

function buildPdfCacheKey(url: string, options: NormalizedFetchOptions) {
  return `${url}\n${options.shouldAttachAuthHeaders(url) ? resolveAuthorization(options) || '' : ''}`;
}

async function loadPdfPreviewSourcePayload(
  url: string,
  options: NormalizedFetchOptions
): Promise<PdfPreviewSourcePayload> {
  const cacheKey = buildPdfCacheKey(url, options);
  const cachedSource = getCachedPdfPreviewSource(cacheKey);

  if (cachedSource) return cachedSource;

  const existingLoad = activePdfPreviewSourceLoads.get(cacheKey);
  if (existingLoad) {
    const existingPayload = await existingLoad;
    return clonePdfPreviewSourcePayload(existingPayload);
  }

  const load = requestPdfPreviewSourcePayload(url, options).finally(() => {
    if (activePdfPreviewSourceLoads.get(cacheKey) === load) {
      activePdfPreviewSourceLoads.delete(cacheKey);
    }
  });

  activePdfPreviewSourceLoads.set(cacheKey, load);

  const payload = await load;
  setCachedPdfPreviewSource(cacheKey, payload);

  return clonePdfPreviewSourcePayload(payload);
}

async function requestPdfPreviewSourcePayload(
  url: string,
  options: NormalizedFetchOptions
): Promise<PdfPreviewSourcePayload> {
  const response = await requestPdfPreviewResponse(url, options, {
    Accept: pdfRangeResponseAccept,
    Range: `bytes=0-${fallbackPdfRangeChunkSize - 1}`
  });
  const contentType = response.headers.get('content-type') || '';

  if (!contentType.includes('application/json')) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    validatePdfBytes(bytes, response.headers.get('content-type') || 'unknown content type');
    const contentRange = parsePdfContentRange(response.headers.get('content-range'));
    if (response.status === 206) {
      if (!contentRange || contentRange.begin !== 0 || contentRange.end + 1 !== bytes.length) {
        throw new Error('PDF initial Range response does not match its Content-Range');
      }
      if (contentRange.totalSizeBytes > bytes.length) {
        return {
          metadata: {
            chunkSizeBytes: fallbackPdfRangeChunkSize,
            rangeUrl: url,
            sourceFileSizeBytes: contentRange.totalSizeBytes
          },
          standardRange: {
            initialData: bytes,
            totalSizeBytes: contentRange.totalSizeBytes,
            url
          }
        };
      }
    }
    return { bytes };
  }

  const payload = (await response.json()) as PdfPreviewDataResponse;
  const data = getPdfPreviewDataPayload(payload);

  if (hasRangeMetadata(data)) {
    return { metadata: clonePdfPreviewDataPayload(data) };
  }

  const bytes = decodePdfPreviewDataResponse(payload);
  validatePdfBytes(bytes, response.headers.get('content-type') || 'unknown content type');
  return { bytes };
}

function clonePdfPreviewSourcePayload(payload: PdfPreviewSourcePayload): PdfPreviewSourcePayload {
  if ('bytes' in payload) return { bytes: cloneBytes(payload.bytes) };
  return {
    metadata: clonePdfPreviewDataPayload(payload.metadata),
    standardRange: payload.standardRange
      ? { ...payload.standardRange, initialData: cloneBytes(payload.standardRange.initialData) }
      : undefined
  };
}

function clonePdfPreviewDataPayload(payload: PdfPreviewDataPayload): PdfPreviewDataPayload {
  return { ...payload };
}

function getCachedPdfPreviewSource(cacheKey: string) {
  const cached = pdfPreviewSourceCache.get(cacheKey);
  if (!cached) return null;
  pdfPreviewSourceCache.delete(cacheKey);
  pdfPreviewSourceCache.set(cacheKey, cached);
  return clonePdfPreviewSourcePayload(cached);
}

function setCachedPdfPreviewSource(cacheKey: string, payload: PdfPreviewSourcePayload) {
  const cachedPayload = clonePdfPreviewSourcePayload(payload);
  const cachedBytes = pdfPreviewSourcePayloadBytes(cachedPayload);
  if (cachedBytes > maxCachedPdfSourceBytes) return;

  const existing = pdfPreviewSourceCache.get(cacheKey);
  if (existing) {
    pdfPreviewSourceCacheBytes -= pdfPreviewSourcePayloadBytes(existing);
    pdfPreviewSourceCache.delete(cacheKey);
  }

  pdfPreviewSourceCache.set(cacheKey, cachedPayload);
  pdfPreviewSourceCacheBytes += cachedBytes;
  while (
    pdfPreviewSourceCache.size > maxCachedPdfSourceEntries ||
    pdfPreviewSourceCacheBytes > maxCachedPdfSourceBytes
  ) {
    const oldestKey = pdfPreviewSourceCache.keys().next().value;
    if (!oldestKey) break;
    const oldest = pdfPreviewSourceCache.get(oldestKey);
    pdfPreviewSourceCache.delete(oldestKey);
    pdfPreviewSourceCacheBytes -= oldest ? pdfPreviewSourcePayloadBytes(oldest) : 0;
  }
}

function pdfPreviewSourcePayloadBytes(payload: PdfPreviewSourcePayload) {
  if ('bytes' in payload) return payload.bytes.byteLength;
  return payload.standardRange?.initialData.byteLength || 0;
}

function cloneBytes(bytes: Uint8Array) {
  return new Uint8Array(bytes);
}

function getStoredAuthorization() {
  if (typeof localStorage === 'undefined') {
    return null;
  }

  const rawToken = localStorage.getItem(`${storagePrefix}token`);
  const token = parseStoredToken(rawToken);

  return token ? `Bearer ${token}` : null;
}

function parseStoredToken(rawToken: string | null) {
  if (!rawToken) return '';

  try {
    const token = JSON.parse(rawToken);
    return typeof token === 'string' ? token : '';
  } catch {
    return rawToken;
  }
}

async function requestPdfPreviewBytes(url: string, options: NormalizedFetchOptions) {
  const response = await requestPdfPreviewResponse(url, options, {
    Accept: 'application/json, application/pdf'
  });
  const contentType = response.headers.get('content-type') || '';
  const bytes = contentType.includes('application/json')
    ? await decodeOrDownloadPdfPreviewDataResponse((await response.json()) as PdfPreviewDataResponse, url, options)
    : new Uint8Array(await response.arrayBuffer());

  validatePdfBytes(bytes, response.headers.get('content-type') || 'unknown content type');

  return bytes;
}

async function requestPdfPreviewResponse(
  url: string,
  options: NormalizedFetchOptions,
  requestHeaders: Record<string, string>
) {
  const response = await options.fetchImpl(url, {
    headers: buildPdfPreviewHeaders(url, options, requestHeaders),
    credentials: 'same-origin'
  });

  if (!response.ok) {
    throw new Error(`PDF request failed: ${response.status}`);
  }

  return response;
}

function buildPdfPreviewHeaders(url: string, options: NormalizedFetchOptions, requestHeaders: Record<string, string>) {
  const headers = { ...requestHeaders };

  const authorization = resolveAuthorization(options);
  if (authorization && options.shouldAttachAuthHeaders(url)) {
    headers.Authorization = authorization;
  }

  return headers;
}

function resolveAuthorization(options: NormalizedFetchOptions) {
  return options.useStoredAuthorization ? getStoredAuthorization() : options.authorization;
}

async function decodeOrDownloadPdfPreviewDataResponse(
  payload: PdfPreviewDataResponse,
  metadataUrl: string,
  options: NormalizedFetchOptions
) {
  const data = getPdfPreviewDataPayload(payload);

  if (hasRangeMetadata(data)) {
    return downloadPdfPreviewRanges(data, metadataUrl, options);
  }

  return decodePdfPreviewDataResponse(payload);
}

function decodePdfPreviewDataResponse(payload: PdfPreviewDataResponse) {
  const contentBase64 = getPdfPreviewDataPayload(payload).contentBase64 || '';

  if (!contentBase64) {
    throw new Error('PDF preview response does not contain contentBase64');
  }

  return decodeBase64Bytes(contentBase64);
}

function getPdfPreviewDataPayload(payload: PdfPreviewDataResponse): PdfPreviewDataPayload {
  return payload.data || payload;
}

function hasRangeMetadata(payload: PdfPreviewDataPayload) {
  return Boolean(payload.rangeUrl && payload.sourceFileSizeBytes && payload.sourceFileSizeBytes > 0);
}

function buildPdfRangeSource(
  metadataUrl: string,
  metadata: PdfPreviewDataPayload,
  options: NormalizedFetchOptions
): DocumentInitParameters {
  return {
    range: new PaperLoomPdfRangeTransport(metadataUrl, metadata, options),
    rangeChunkSize: normalizeChunkSize(metadata.chunkSizeBytes),
    disableAutoFetch: true,
    disableStream: true
  };
}

function buildStandardPdfRangeSource(
  payload: StandardPdfRangePayload,
  options: NormalizedFetchOptions
): DocumentInitParameters {
  return {
    range: new PaperLoomStandardPdfRangeTransport(payload, options),
    rangeChunkSize: fallbackPdfRangeChunkSize,
    disableAutoFetch: true,
    disableStream: true
  };
}

async function downloadPdfPreviewRanges(
  metadata: PdfPreviewDataPayload,
  metadataUrl: string,
  options: NormalizedFetchOptions
) {
  const totalSize = normalizeTotalSize(metadata.sourceFileSizeBytes);
  const chunkSize = normalizeChunkSize(metadata.chunkSizeBytes);
  const bytes = new Uint8Array(totalSize);
  const ranges: Array<{ begin: number; end: number }> = [];

  for (let begin = 0; begin < totalSize; begin += chunkSize) {
    ranges.push({ begin, end: Math.min(begin + chunkSize, totalSize) });
  }

  const chunks = await Promise.all(
    ranges.map(range =>
      requestPdfPreviewRange({
        metadata,
        metadataUrl,
        begin: range.begin,
        end: range.end,
        options
      })
    )
  );
  chunks.forEach(({ begin, bytes: chunk }) => bytes.set(chunk, begin));

  return bytes;
}

async function requestPdfPreviewRange(request: PdfPreviewRangeRequest) {
  const rangeUrl = buildPdfPreviewRangeRequestUrl(
    resolvePdfPreviewRangeUrl(request.metadata.rangeUrl, request.metadataUrl),
    request.begin,
    request.end
  );
  const { options } = request;
  const cacheKey = buildPdfCacheKey(rangeUrl, options);
  const cachedBytes = getCachedPdfRange(cacheKey);

  if (cachedBytes) {
    return {
      begin: request.begin,
      bytes: cachedBytes
    };
  }

  const existingLoad = activePdfRangeLoads.get(cacheKey);
  if (existingLoad) {
    return {
      begin: request.begin,
      bytes: cloneBytes(await existingLoad)
    };
  }

  const load = requestPdfPreviewResponse(rangeUrl, options, { Accept: pdfRangeResponseAccept })
    .then(decodePdfPreviewRangeBytes)
    .finally(() => {
      if (activePdfRangeLoads.get(cacheKey) === load) {
        activePdfRangeLoads.delete(cacheKey);
      }
    });
  activePdfRangeLoads.set(cacheKey, load);

  const bytes = await load;
  setCachedPdfRange(cacheKey, bytes);

  return {
    begin: request.begin,
    bytes: cloneBytes(bytes)
  };
}

async function decodePdfPreviewRangeBytes(response: Response) {
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    return decodePdfPreviewRangeResponse((await response.json()) as PdfPreviewRangeDataResponse);
  }
  return new Uint8Array(await response.arrayBuffer());
}

function getCachedPdfRange(cacheKey: string) {
  const cached = pdfRangeCache.get(cacheKey);
  if (!cached) return null;
  pdfRangeCache.delete(cacheKey);
  pdfRangeCache.set(cacheKey, cached);
  return cloneBytes(cached);
}

function setCachedPdfRange(cacheKey: string, bytes: Uint8Array) {
  if (bytes.byteLength > maxCachedPdfRangeBytes) return;

  const existing = pdfRangeCache.get(cacheKey);
  if (existing) {
    pdfRangeCacheBytes -= existing.byteLength;
    pdfRangeCache.delete(cacheKey);
  }

  pdfRangeCache.set(cacheKey, cloneBytes(bytes));
  pdfRangeCacheBytes += bytes.byteLength;

  while (pdfRangeCacheBytes > maxCachedPdfRangeBytes) {
    const oldestKey = pdfRangeCache.keys().next().value;
    if (!oldestKey) break;
    const oldest = pdfRangeCache.get(oldestKey);
    pdfRangeCache.delete(oldestKey);
    pdfRangeCacheBytes -= oldest?.byteLength || 0;
  }
}

function decodePdfPreviewRangeResponse(payload: PdfPreviewRangeDataResponse) {
  const contentBase64 = payload.data?.contentBase64 || payload.contentBase64 || '';

  if (!contentBase64) {
    throw new Error('PDF preview range response does not contain contentBase64');
  }

  return decodeBase64Bytes(contentBase64);
}

function buildPdfDataSource(bytes: Uint8Array): DocumentInitParameters {
  return {
    data: bytes,
    disableAutoFetch: true,
    disableStream: true,
    rangeChunkSize: fallbackPdfRangeChunkSize
  };
}

function validatePdfBytes(bytes: Uint8Array, contentType: string) {
  const isPdf = bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46;

  if (!isPdf) {
    throw new Error(`PDF response is not a PDF: ${contentType}`);
  }
}

function normalizeTotalSize(sourceFileSizeBytes?: number) {
  if (!sourceFileSizeBytes || !Number.isFinite(sourceFileSizeBytes) || sourceFileSizeBytes <= 0) {
    throw new Error('PDF preview response does not contain sourceFileSizeBytes');
  }

  return Math.floor(sourceFileSizeBytes);
}

function normalizeChunkSize(chunkSizeBytes?: number) {
  if (!chunkSizeBytes || !Number.isFinite(chunkSizeBytes) || chunkSizeBytes <= 0) {
    return fallbackPdfRangeChunkSize;
  }

  return Math.floor(chunkSizeBytes);
}

function resolvePdfPreviewRangeUrl(rangeUrl: string | undefined, metadataUrl: string) {
  if (!rangeUrl) {
    return `${metadataUrl.replace(/\/$/, '')}/range`;
  }

  if (/^https?:\/\//i.test(rangeUrl) || rangeUrl.startsWith('/proxy-')) {
    return rangeUrl;
  }

  if (rangeUrl.startsWith('/api/')) {
    const proxyPrefix = getProxyPrefix(metadataUrl);
    if (proxyPrefix) {
      return `${proxyPrefix}${rangeUrl.replace(/^\/api\/v\d+/, '')}`;
    }

    return rangeUrl;
  }

  if (rangeUrl.startsWith('/')) {
    return rangeUrl;
  }

  return resolveRelativeUrl(rangeUrl, metadataUrl);
}

function getProxyPrefix(url: string) {
  const path = getUrlPathname(url);
  return path.match(/^\/proxy-[^/]+/)?.[0] || '';
}

function getUrlPathname(url: string) {
  if (url.startsWith('/')) {
    return url;
  }

  try {
    return new URL(url).pathname;
  } catch {
    return url;
  }
}

function resolveRelativeUrl(url: string, baseUrl: string) {
  if (baseUrl.startsWith('/')) {
    const resolved = new URL(url, `http://paperloom.local${baseUrl}`);
    return `${resolved.pathname}${resolved.search}${resolved.hash}`;
  }

  return new URL(url, baseUrl).toString();
}

function buildPdfPreviewRangeRequestUrl(rangeUrl: string, begin: number, end: number) {
  const separator = rangeUrl.includes('?') ? '&' : '?';
  return `${rangeUrl}${separator}begin=${encodeURIComponent(String(begin))}&end=${encodeURIComponent(String(end))}`;
}

function parsePdfContentRange(rawValue: string | null) {
  const match = rawValue?.trim().match(/^bytes (\d+)-(\d+)\/(\d+)$/i);
  if (!match) return null;

  const begin = Number(match[1]);
  const end = Number(match[2]);
  const totalSizeBytes = Number(match[3]);
  if (![begin, end, totalSizeBytes].every(Number.isSafeInteger) || begin < 0 || end < begin || end >= totalSizeBytes) {
    return null;
  }

  return { begin, end, totalSizeBytes };
}

function decodeBase64Bytes(contentBase64: string) {
  const binary = atob(contentBase64);
  const bytes = new Uint8Array(binary.length);

  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }

  return bytes;
}

class PaperLoomPdfRangeTransport extends PDFDataRangeTransport {
  private aborted = false;

  private readonly metadataUrl: string;

  private readonly metadata: PdfPreviewDataPayload;

  private readonly options: NormalizedFetchOptions;

  private readonly controllers = new Set<AbortController>();

  constructor(metadataUrl: string, metadata: PdfPreviewDataPayload, options: NormalizedFetchOptions) {
    super(normalizeTotalSize(metadata.sourceFileSizeBytes), null, true, metadata.originalFilename || '');
    this.metadataUrl = metadataUrl;
    this.metadata = metadata;
    this.options = options;
  }

  override requestDataRange(begin: number, end: number) {
    if (this.aborted) return;
    this.loadRange(begin, end).catch(() => undefined);
  }

  override abort() {
    this.aborted = true;
    this.controllers.forEach(controller => controller.abort());
    this.controllers.clear();
  }

  private async loadRange(begin: number, end: number) {
    const controller = new AbortController();
    this.controllers.add(controller);

    try {
      const range = await requestPdfPreviewRange({
        metadata: this.metadata,
        metadataUrl: this.metadataUrl,
        begin,
        end,
        options: this.options
      });

      if (!this.aborted) {
        this.onDataRange(range.begin, range.bytes);
      }
    } finally {
      this.controllers.delete(controller);
    }
  }
}

class PaperLoomStandardPdfRangeTransport extends PDFDataRangeTransport {
  private aborted = false;

  constructor(
    private readonly payload: StandardPdfRangePayload,
    private readonly options: NormalizedFetchOptions
  ) {
    super(payload.totalSizeBytes, cloneBytes(payload.initialData), true);
  }

  override requestDataRange(begin: number, end: number) {
    if (this.aborted) return;
    this.loadRange(begin, end).catch(() => undefined);
  }

  override abort() {
    this.aborted = true;
  }

  private async loadRange(begin: number, end: number) {
    const response = await requestPdfPreviewResponse(this.payload.url, this.options, {
      Accept: pdfRangeResponseAccept,
      Range: `bytes=${begin}-${end - 1}`
    });
    const contentRange = parsePdfContentRange(response.headers.get('content-range'));
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (
      !contentRange ||
      contentRange.begin !== begin ||
      contentRange.end !== end - 1 ||
      contentRange.totalSizeBytes !== this.payload.totalSizeBytes ||
      bytes.length !== end - begin
    ) {
      throw new Error('PDF Range response does not match the requested range');
    }
    if (!this.aborted) this.onDataRange(begin, bytes);
  }
}
