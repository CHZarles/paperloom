import { request } from '@/service/request';

export function fetchArxivSearch(params: {
  query: string;
  start?: number;
  max?: number;
  sort?: 'relevance' | 'submitted';
}) {
  return request<Api.Discovery.ArxivSearchResponse>({
    url: '/discovery/arxiv/search',
    method: 'get',
    params,
    // arXiv 正常 1~5 秒，慢的时候要 20 秒以上；超时留给后端去报真正的错误
    timeout: 32000
  });
}

export function importArxivPaper(arxivId: string) {
  return request<Api.Discovery.ArxivImportResult>({
    url: '/discovery/arxiv/import',
    method: 'POST',
    data: { arxivId },
    // 下载 PDF 再入库，比检索慢
    timeout: 120000
  });
}
