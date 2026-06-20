import { api } from './auth'
import type { Character, CharacterRequest } from '@/types'

// 角色域 REST 客户端，对接后端 CharacterController（/characters）与 UploadController（/upload/avatar）。
// 复用 ./auth 导出的 axios 实例，自动带上 JWT 与统一错误处理；
// 角色管理页（CharacterLibraryView）、角色创建/编辑弹窗、聊天室编排面板均消费此模块。
export const charactersApi = {
  /**
   * 列出当前用户可见的全部角色（含系统 preset 与本人私有角色）。
   * HTTP GET /characters。
   * 调用方：CharacterLibraryView、CreateRoom 角色选择器。
   */
  list: () => api.get<Character[]>('/characters'),

  /**
   * 列出系统内置预置角色（只读，对所有用户可见）。
   * HTTP GET /characters/presets。
   * 用于"快速创建聊天室"场景，避免用户从零配置。
   * 调用方：CreateRoom 快速模板。
   */
  // 后端返回系统内置的预置角色（只读），用于"快速创建聊天室"场景，避免用户从零配置。
  getPresets: () => api.get<Character[]>('/characters/presets'),

  /**
   * 列出基于用户画像/热点的个性化推荐角色。
   * HTTP GET /characters/recommended。
   * 调用方：CharacterLibraryView 推荐 Tab。
   */
  // 后端基于当前用户画像/热点推荐的个性化角色列表，与 presets 的区别是会随用户行为变化。
  getRecommended: () => api.get<Character[]>('/characters/recommended'),

  /**
   * 获取单个角色详情。
   * HTTP GET /characters/{id}。
   * 调用方：CharacterDetailModal、编辑页回显。
   */
  getById: (id: string) => api.get<Character>(`/characters/${id}`),

  /**
   * 新建角色。
   * HTTP POST /characters。
   * 调用方：CreateCharacterModal 提交。
   */
  create: (data: CharacterRequest) => api.post<Character>('/characters', data),

  /**
   * AI 生成角色 system prompt。
   * HTTP POST /characters/generate-prompt。
   * 后端调用 Firecrawl + LLM 流水线，根据 name/description 自动生成 system prompt；
   * 两字段都可选：只给 name 时依赖联网检索补全，只给 description 时跳过检索直接生成。
   * 调用方：CreateCharacterModal 的"AI 生成"按钮。
   */
  generatePrompt: (data: { name?: string; description?: string }) =>
    api.post<{ prompt: string }>('/characters/generate-prompt', data),

  /**
   * 更新角色。
   * HTTP PUT /characters/{id}。
   * 调用方：EditCharacterModal。
   */
  update: (id: string, data: CharacterRequest) => api.put<Character>(`/characters/${id}`, data),

  /**
   * 删除角色。
   * HTTP DELETE /characters/{id}。
   * 调用方：CharacterLibraryView 行内删除按钮（带确认弹窗）。
   */
  remove: (id: string) => api.delete(`/characters/${id}`),

  /**
   * 上传角色头像，返回资源 URL。
   * HTTP POST /upload/avatar（multipart/form-data）。
   * 头像走独立 /upload 路由而非 /characters，返回 OSS/静态资源 URL 而不是 Character 实体；
   * 必须显式声明 multipart 头：axios 默认会把 FormData 序列化成 application/json，导致文件丢失。
   * 调用方：CreateCharacterModal / EditCharacterModal 的头像裁剪组件。
   */
  uploadAvatar: (file: File) => {
    const formData = new FormData()
    formData.append('avatar', file)
    return api.post<{ url: string }>('/upload/avatar', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
