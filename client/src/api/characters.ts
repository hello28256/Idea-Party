import { api } from './auth'
import type { Character, CharacterRequest } from '@/types'

// 角色（AI persona）相关的 REST API 客户端。
// 复用 ./auth 导出的 axios 实例，自动带上 JWT 与统一错误处理；
// 与后端 /characters、/upload/avatar 端点一一对应，被角色管理、聊天室编排等页面共享。
export const charactersApi = {
  list: () => api.get<Character[]>('/characters'),

  // 后端返回系统内置的预置角色（只读），用于"快速创建聊天室"场景，避免用户从零配置。
  getPresets: () => api.get<Character[]>('/characters/presets'),

  // 后端基于当前用户画像/热点推荐的个性化角色列表，与 presets 的区别是会随用户行为变化。
  getRecommended: () => api.get<Character[]>('/characters/recommended'),

  getById: (id: string) => api.get<Character>(`/characters/${id}`),

  create: (data: CharacterRequest) => api.post<Character>('/characters', data),

  // 调用后端 Firecrawl + LLM 流水线，根据 name/description 自动生成角色 system prompt。
  // 两字段都可选：只给 name 时依赖联网检索补全，只给 description 时跳过检索直接生成。
  generatePrompt: (data: { name?: string; description?: string }) =>
    api.post<{ prompt: string }>('/characters/generate-prompt', data),

  update: (id: string, data: CharacterRequest) => api.put<Character>(`/characters/${id}`, data),

  remove: (id: string) => api.delete(`/characters/${id}`),

  // 头像走独立的 /upload 路由而非 /characters，返回 OSS/静态资源 URL 而不是 Character 实体。
  // 必须显式声明 multipart 头：axios 默认会把 FormData 序列化成 application/json，导致文件丢失。
  uploadAvatar: (file: File) => {
    const formData = new FormData()
    formData.append('avatar', file)
    return api.post<{ url: string }>('/upload/avatar', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
