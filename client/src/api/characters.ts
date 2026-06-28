import { api } from './auth'
import type { Character, CharacterRequest } from '@/types'

/**
 * 角色被引用的聊天室精简信息（仅 id + name，不暴露 ownerId）。
 * 与后端 CharacterReferencesResponse.ReferencedRoom 对齐。
 */
export interface ReferencedRoom {
  id: string
  name: string
}

/**
 * 角色引用查询的响应：列出引用了指定角色的全部聊天室，供删除前的"级联确认"弹窗使用。
 * 与后端 CharacterReferencesResponse 对齐。
 */
export interface CharacterReferences {
  characterId: string
  roomCount: number
  rooms: ReferencedRoom[]
}

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
   * HTTP GET /characters/recommended[?category=SCIENTIST|STAR|...]。
   * 不传 category 返回全部预设；传单枚举名按"包含"语义过滤（用于发现页"分类标签条"）。
   * 后端会把角色的 categories 集合与入参做 contains 判断，多分类的角色会被多个 chip 命中。
   * 调用方：CharacterLibraryView 推荐 Tab、RoomListView 发现页。
   */
  // 后端基于当前用户画像/热点推荐的个性化角色列表，与 presets 的区别是会随用户行为变化。
  getRecommended: (category?: string) => {
    // 加时间戳参数强制不走浏览器/中间层缓存，避免角色头像更新后还显示旧数据
    const params: Record<string, string> = category ? { category } : {}
    params._t = String(Date.now())
    return api.get<Character[]>('/characters/recommended', { params })
  },

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
   * 头像搜索：根据角色名从维基百科拉 2-3 个候选头像缩略图 URL，供用户挑选。
   * HTTP GET /characters/avatar-search?name=xxx
   * 调用方：CreateCharacterModal 的"自动获取头像"按钮。
   */
  searchAvatars: (name: string) =>
    api.get<Array<{ thumbnailUrl: string; title: string; wikiUrl: string }>>(
      '/characters/avatar-search',
      { params: { name } }
    ),

  /**
   * 更新角色。
   * HTTP PUT /characters/{id}。
   * 调用方：EditCharacterModal。
   */
  update: (id: string, data: CharacterRequest) => api.put<Character>(`/characters/${id}`, data),

  /**
   * 删除角色。
   * HTTP DELETE /characters/{id}[?cascade=true]。
   * 调用方：CharacterLibraryView 行内删除按钮（带确认弹窗）。
   * cascade=true 时后端会一并删除引用该角色的全部聊天室（事务原子）；
   * 缺省或 false 保持旧行为：被引用则 400，由前端兜底提示。
   */
  remove: (id: string, cascade = false) =>
    api.delete(`/characters/${id}${cascade ? '?cascade=true' : ''}`),

  /**
   * 查询角色被哪些聊天室引用：删除前的"级联确认"弹窗使用，
   * 返回精简的 {id, name} 列表，便于前端做"是否一并删除 N 个聊天室"决策。
   * HTTP GET /characters/{id}/references。
   * 调用方：CreateCharacterModal 的级联删除流程。
   */
  getReferences: (id: string) =>
    api.get<CharacterReferences>(`/characters/${id}/references`),

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
