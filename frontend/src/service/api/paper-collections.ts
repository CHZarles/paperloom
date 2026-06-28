import { request } from '@/service/request';

export function fetchPaperCollections() {
  return request<Api.PaperCollection.Item[]>({ url: '/paper-collections' });
}

export function createPaperCollection(payload: Api.PaperCollection.UpsertPayload) {
  return request<Api.PaperCollection.Item>({ url: '/paper-collections', method: 'POST', data: payload });
}

export function updatePaperCollection(id: number, payload: Api.PaperCollection.UpsertPayload) {
  return request<Api.PaperCollection.Item>({ url: `/paper-collections/${id}`, method: 'PUT', data: payload });
}

export function deletePaperCollection(id: number) {
  return request({ url: `/paper-collections/${id}`, method: 'DELETE' });
}

export function fetchPaperCollection(id: number) {
  return request<Api.PaperCollection.Detail>({ url: `/paper-collections/${id}` });
}

export function addPapersToCollection(id: number, paperIds: string[]) {
  return request<Api.PaperCollection.Detail>({
    url: `/paper-collections/${id}/papers`,
    method: 'POST',
    data: { paperIds }
  });
}

export function removePaperFromCollection(id: number, paperId: string) {
  return request({
    url: `/paper-collections/${id}/papers/${paperId}`,
    method: 'DELETE'
  });
}
