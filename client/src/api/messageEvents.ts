import { api } from './auth'

export type EventType = 'REWRITE' | 'COPY' | 'READ_COMPLETE' | 'EDIT' | 'FOCUS'

export interface RecordEventBody {
  eventType: EventType
  dwellMs?: number
  metadata?: string
}

export interface MessageSignals {
  messageId: string
  rewriteCount: number
  copyCount: number
  readCompleteCount: number
  editCount: number
  averageDwellMs: number | null
  uniqueUsers: number
}

export const messageEventsApi = {
  record: (messageId: string, body: RecordEventBody) =>
    api.post(`/messages/${messageId}/events`, body).then(res => res.data),

  adminSignals: (messageId: string) =>
    api.get<MessageSignals>(`/admin/messages/${messageId}/signals`).then(res => res.data)
}
