import { api } from './auth'
import type { MessageFeedbackPayload } from '@/composables/useSocket'

export interface SubmitFeedbackBody {
  type: 'LIKE' | 'DISLIKE'
  category?: string | null
  comment?: string | null
}

export interface FeedbackResponse {
  id: string
  messageId: string
  type: 'LIKE' | 'DISLIKE'
  category: string | null
  comment: string | null
  createdAt: string
  updatedAt: string
}

export interface AdminFeedbackListItem {
  id: string
  messageId: string
  messagePreview: string
  type: 'LIKE' | 'DISLIKE'
  category: string | null
  comment: string | null
  userId: string
  username: string
  displayName: string
  createdAt: string
}

export interface AdminFeedbackDetail extends AdminFeedbackListItem {
  messageContent: string
  messageCreatedAt: string
  roomId: string
  roomName: string
  characterId: string | null
  characterName: string | null
  /** Most recent USER message that prompted the AI reply. May be null. */
  userPrompt?: string | null
  userPromptAt?: string | null
}

export interface AdminListParams {
  page?: number
  size?: number
  category?: string
  userId?: string
  type?: 'LIKE' | 'DISLIKE'
  from?: string
  to?: string
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export const messageFeedbackApi = {
  submit: (messageId: string, body: SubmitFeedbackBody) =>
    api.post<FeedbackResponse>(`/messages/${messageId}/feedback`, body).then(res => res.data),

  get: (messageId: string) =>
    api.get<FeedbackResponse>(`/messages/${messageId}/feedback`).then(res => res.data),

  remove: (messageId: string) =>
    api.delete(`/messages/${messageId}/feedback`),

  adminList: (params: AdminListParams) =>
    api.get<PageResponse<AdminFeedbackListItem>>('/admin/feedbacks', { params })
      .then(res => res.data),

  adminGet: (id: string) =>
    api.get<AdminFeedbackDetail>(`/admin/feedbacks/${id}`).then(res => res.data)
}

/** Convert server FeedbackResponse to ChatMessage.feedback payload shape. */
export function toFeedbackPayload(r: FeedbackResponse): MessageFeedbackPayload {
  return {
    type: r.type,
    category: r.category,
    comment: r.comment,
    createdAt: r.createdAt
  }
}
