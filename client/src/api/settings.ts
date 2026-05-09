import { api } from './auth'

export const settingsApi = {
  getApiKey: () =>
    api.get<{ apiKey: string }>('/settings/api-key'),

  setApiKey: (apiKey: string) =>
    api.post('/settings/api-key', { apiKey }),

  clearApiKey: () =>
    api.delete('/settings/api-key')
}
