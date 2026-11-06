import { request } from '../request';

export function fetchUsageOverview(days: number = 7) {
  return request<Api.Admin.UsageOverview>({ url: '/admin/usage/overview', params: { days } });
}

export function fetchModelProviders(scope: 'llm' | 'embedding') {
  return request<Api.Admin.ModelProviderOverview>({ url: '/admin/model-providers', params: { scope } });
}

export function saveModelProvider(
  scope: 'llm' | 'embedding',
  providerCode: string,
  data: Api.Admin.ModelProviderSaveRequest
) {
  return request<Api.Admin.ModelProviderOverview>({
    url: `/admin/model-providers/${scope}/${providerCode}`,
    method: 'put',
    data
  });
}
