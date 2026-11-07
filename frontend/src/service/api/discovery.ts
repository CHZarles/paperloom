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

// ---------------- 智能探索（Discovery Agent，v3 聊天单例） ----------------

export function postDiscoveryAgentMessage(text: string) {
  return request<Api.Discovery.AgentPostResult>({
    url: '/discovery/agent/chat/messages',
    method: 'post',
    data: { text },
    // 模型 turn 开跑即返回；指令处理（/clear 等）也在服务端同步完成
    timeout: 30000
  });
}

/** 快照轮询：含运行中 turn 的实况（文本流、候选卡片、用量） */
export function fetchDiscoveryAgentChat(after?: number) {
  return request<Api.Discovery.AgentChatThread>({
    url: 'discovery/agent/chat',
    method: 'get',
    params: after ? { after } : undefined,
    timeout: 15000
  });
}

export function cancelDiscoveryAgentTurn(turnId: string) {
  return request<{ turnId: string; status: string }>({
    url: `discovery/agent/chat/cancel`,
    method: 'post',
    data: { turnId }
  });
}

/** 候选栏一键/单篇入库：用户自决路径，不经模型；最多 20 篇逐篇走现有链路 */
export function importDiscoveryAgentCandidates(arxivIds: string[]) {
  return request<Api.Discovery.AgentImportsResponse>({
    url: '/discovery/agent/chat/import',
    method: 'post',
    data: { arxivIds },
    timeout: 600000
  });
}

export function fetchDiscoveryAgentMemory() {
  return request<{ items: Api.Discovery.AgentMemoryItem[] }>({
    url: 'discovery/agent/memory',
    method: 'get'
  });
}

export function createDiscoveryAgentMemory(kind: 'ROLE' | 'PREF' | 'INSIGHT', content: string) {
  return request<Api.Discovery.AgentMemoryItem>({
    url: 'discovery/agent/memory',
    method: 'post',
    data: { kind, content }
  });
}

export function updateDiscoveryAgentMemory(id: number, params: { content?: string; active?: boolean }) {
  return request<Api.Discovery.AgentMemoryItem>({
    url: `discovery/agent/memory/${id}`,
    method: 'put',
    data: params
  });
}

export function deleteDiscoveryAgentMemory(id: number) {
  return request<{ ok: boolean }>({
    url: `discovery/agent/memory/${id}`,
    method: 'delete'
  });
}

/** 清除全部对话 + 候选（一键回白板） */
export function clearDiscoveryAgentChat() {
  return request<{ ok: boolean }>({
    url: 'discovery/agent/chat',
    method: 'delete'
  });
}
