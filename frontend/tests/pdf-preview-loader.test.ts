import assert from 'node:assert/strict';
import {
  clearPdfPreviewByteLoadCacheForTest,
  createPdfPreviewSource,
  fetchPdfPreviewBytes
} from '../src/components/custom/pdf-preview-loader';

clearPdfPreviewByteLoadCacheForTest();

let releaseFetch: (() => void) | null = null;
let fetchCallCount = 0;
let receivedAuthorization = '';
const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31]);

const fetchImpl: typeof fetch = async (_url, init) => {
  fetchCallCount += 1;
  receivedAuthorization = String((init?.headers as Record<string, string>)?.Authorization || '');

  await new Promise<void>(resolve => {
    releaseFetch = resolve;
  });

  return new Response(pdfBytes, {
    status: 200,
    headers: {
      'content-type': 'application/pdf'
    }
  });
};

const firstLoad = fetchPdfPreviewBytes('/proxy-default/papers/paper-1/preview/pdf', {
  authorization: 'Bearer token-1',
  fetchImpl
});
const secondLoad = fetchPdfPreviewBytes('/proxy-default/papers/paper-1/preview/pdf', {
  authorization: 'Bearer token-1',
  fetchImpl
});

assert.equal(fetchCallCount, 1, 'same PDF URL should share the in-flight fetch');
assert.equal(receivedAuthorization, 'Bearer token-1');

releaseFetch?.();

const [firstBytes, secondBytes] = await Promise.all([firstLoad, secondLoad]);
assert.deepEqual([...firstBytes], [...pdfBytes]);
assert.deepEqual([...secondBytes], [...pdfBytes]);
assert.notEqual(firstBytes, secondBytes, 'each caller receives its own Uint8Array copy');

const immediateFetchImpl: typeof fetch = async () => {
  fetchCallCount += 1;
  return new Response(pdfBytes, {
    status: 200,
    headers: {
      'content-type': 'application/pdf'
    }
  });
};

await fetchPdfPreviewBytes('/proxy-default/papers/paper-1/preview/pdf', {
  authorization: 'Bearer token-1',
  fetchImpl: immediateFetchImpl
});

assert.equal(fetchCallCount, 2, 'completed PDF loads are not retained indefinitely');

const jsonBytes = await fetchPdfPreviewBytes('/proxy-default/papers/paper-2/preview/pdf', {
  authorization: 'Bearer token-1',
  fetchImpl: async () =>
    new Response(
      JSON.stringify({
        code: 200,
        data: {
          contentBase64: 'JVBERi0x'
        }
      }),
      {
        status: 200,
        headers: {
          'content-type': 'application/json'
        }
      }
    )
});

assert.deepEqual([...jsonBytes], [...pdfBytes]);

const rangeRequests: string[] = [];
const rangeRequestHeaders: Record<string, string>[] = [];
const rangeFetchImpl: typeof fetch = async (input, init) => {
  const url = String(input);
  rangeRequests.push(url);
  rangeRequestHeaders.push((init?.headers as Record<string, string>) || {});

  if (url === '/proxy-default/papers/paper-3/preview/pdf-data') {
    return new Response(
      JSON.stringify({
        code: 200,
        data: {
          paperId: 'paper-3',
          originalFilename: 'paper-3.pdf',
          contentType: 'application/pdf',
          sourceFileSizeBytes: 8,
          chunkSizeBytes: 262144,
          rangeUrl: '/api/v1/papers/paper-3/preview/pdf-data/range'
        }
      }),
      {
        status: 200,
        headers: {
          'content-type': 'application/json'
        }
      }
    );
  }

  if (url === '/proxy-default/papers/paper-3/preview/pdf-data/range?begin=1&end=5') {
    return new Response(
      JSON.stringify({
        code: 200,
        data: {
          paperId: 'paper-3',
          begin: 1,
          end: 5,
          offset: 1,
          length: 4,
          totalSizeBytes: 8,
          contentBase64: 'UERGLQ=='
        }
      }),
      {
        status: 206,
        headers: {
          'content-type': 'application/json'
        }
      }
    );
  }

  throw new Error(`unexpected range request: ${url}`);
};

const previewSource = await createPdfPreviewSource('/proxy-default/papers/paper-3/preview/pdf-data', {
  authorization: 'Bearer token-3',
  fetchImpl: rangeFetchImpl
});

assert.equal(previewSource.rangeChunkSize, 262144);
assert.ok(previewSource.range, 'pdf.js source should expose a range transport');
assert.equal('url' in previewSource, false, 'pdf.js source should not point at a browser-loadable PDF URL');
assert.equal('data' in previewSource, false, 'range metadata should not eagerly load the full PDF');
assert.equal(rangeRequestHeaders[0].Accept, 'application/json');
assert.equal(rangeRequestHeaders[0].Authorization, 'Bearer token-3');

const rangeResult = new Promise<{ begin: number; chunk: Uint8Array | null }>(resolve => {
  previewSource.range!.onDataRange = (begin, chunk) => resolve({ begin, chunk });
});

previewSource.range!.requestDataRange(1, 5);
const loadedRange = await rangeResult;

assert.equal(loadedRange.begin, 1);
assert.deepEqual([...(loadedRange.chunk || [])], [0x50, 0x44, 0x46, 0x2d]);
assert.equal(rangeRequests[1], '/proxy-default/papers/paper-3/preview/pdf-data/range?begin=1&end=5');
assert.equal(rangeRequestHeaders[1].Accept, 'application/json');
assert.equal(rangeRequestHeaders[1].Authorization, 'Bearer token-3');
