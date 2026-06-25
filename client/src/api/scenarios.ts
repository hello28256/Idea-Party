import { api } from './auth'

// 面试场景相关接口的请求体：用户在创建面试官角色时填写的可选上下文
// 字段全部 optional 是为了让「只给岗位名」的极简场景也能直接生成 prompt
export interface InterviewScenarioRequest {
  position: string
  industry?: string
  experienceYears?: number
  jobDescription?: string
  resumeContent?: string
}

// 后端生成面试官角色后返回的最小契约：后续会作为普通角色加入聊天室
// 仅返回 characterName + prompt，不返回完整 Role 实体——前端拿到后自行封装
export interface InterviewScenarioResponse {
  characterName: string
  prompt: string
}

// 简历解析结果：text 用于回填表单/参与 prompt 生成，truncated 让 UI 提示用户「简历太长被截断」
export interface ParseResumeResponse {
  text: string
  length: number
  filename: string
  truncated: boolean
}

// JD 截图 OCR 结果：与 ParseResumeResponse 同构，便于前端用同一套回填/截断提示逻辑
export interface ExtractTextFromImageResponse {
  text: string
  length: number
  filename: string
  truncated: boolean
}

// 场景域 REST 客户端，对接后端 ScenarioController（/scenarios/interview/*）。
// 职责：为「面试」场景提供 prompt 生成、简历解析、JD 截图 OCR 等专用能力；
// 复用 auth.ts 中的 axios 实例，保持与其它模块一致的鉴权链路。
// 「面试」场景专用 API 集合：从 auth.ts 复用带 JWT 的 axios 实例，保持与其它模块一致的鉴权链路
export const scenariosApi = {
  /**
   * 根据岗位/行业/经验/JD/简历 动态生成面试官 prompt。
   * HTTP POST /scenarios/interview/generate-prompt。
   * 后端会调用 Firecrawl/LLM 把岗位上下文拼接成角色 prompt；返回的不是完整角色，需要前端二次封装入聊天室。
   * 调用方：InterviewScenarioView 提交表单。
   */
  // 后端会调用 Firecrawl/LLM 把岗位上下文拼接成角色 prompt；返回的不是完整角色，需要前端二次封装入聊天室
  generateInterviewPrompt: (data: InterviewScenarioRequest) =>
    api.post<InterviewScenarioResponse>('/scenarios/interview/generate-prompt', data),

  /**
   * 解析上传的简历文件（docx/pdf/txt），返回纯文本。
   * HTTP POST /scenarios/interview/parse-resume（multipart/form-data）。
   * 必须显式声明 multipart/form-data：axios 默认会把 FormData 当 JSON 序列化，导致后端拿不到文件流。
   * 调用方：InterviewScenarioView 简历上传组件。
   */
  parseResume: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post<ParseResumeResponse>('/scenarios/interview/parse-resume', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  /**
   * 识别 JD 截图（png/jpg/webp/gif），返回提取的纯文本。
   * HTTP POST /scenarios/interview/extract-text-from-image（multipart/form-data）。
   * OCR 接口同样走 multipart：浏览器会自动带上 boundary，axios 只需声明 Content-Type 即可。
   * 调用方：InterviewScenarioView JD 截图上传组件。
   */
  extractTextFromImage: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post<ExtractTextFromImageResponse>('/scenarios/interview/extract-text-from-image', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}

// ===== 用户私有场景（UserScenario）CRUD =====
//
// 用户通过 /scenarios 网格上的 "+ 自定义场景" 卡片创建私有场景模板。
// 与预设场景（前端 SEED_SCENARIOS 常量）不同：用户场景由后端 user_scenarios 表持久化。
// CRUD endpoint 前缀 /api/scenarios/user，由 UserScenarioController 提供。
//
// 字段集合与后端 UserScenarioRequest 一一对应：
// - emoji/title/description/characterName/promptTemplate 必填
// - userInputLabel/userInputPlaceholder 可选；前端 store 在最终消费时根据 label 是否
//   非空推导 requiresUserInput，避免让用户直接编辑硬编码行为字段
export interface UserScenarioRequest {
  emoji: string
  title: string
  description: string
  characterName: string
  userInputLabel?: string
  userInputPlaceholder?: string
  promptTemplate: string
}

// 后端 UserScenarioResponse：除上述字段外 + id/ownerId/isPreset/createdAt/updatedAt
// isPreset 恒为 false（预设场景由前端常量维护），保留字段便于前端 store 用 Scenario 单一接口消费
export interface UserScenarioResponse extends UserScenarioRequest {
  id: string
  ownerId: string
  isPreset: false
  createdAt: string
  updatedAt: string
}

export const userScenariosApi = {
  /**
   * 列出当前用户全部私有场景，按 updatedAt DESC。
   * 调用方：RoomListView 进入 /scenarios tab 时触发 fetchUserScenarios。
   */
  list: () =>
    api.get<UserScenarioResponse[]>('/scenarios/user'),

  /**
   * 创建用户私有场景。后端按 (owner_id, title) 幂等：命中时返回已存在那条。
   * 返回 201 + 完整实体表示，前端直接追加到 userScenarios 列表。
   */
  create: (data: UserScenarioRequest) =>
    api.post<UserScenarioResponse>('/scenarios/user', data),

  /**
   * 更新用户私有场景。后端校验 ownerId：不存在或非 owner 一律 403。
   * 成功返回完整最新实体，前端原地替换本地缓存。
   */
  update: (id: string, data: UserScenarioRequest) =>
    api.put<UserScenarioResponse>(`/scenarios/user/${id}`, data),

  /**
   * 删除用户私有场景。成功 204；不存在/非 owner 403。
   * 不级联影响历史房间——Room 只通过 character_id 引用 Character。
   */
  remove: (id: string) =>
    api.delete(`/scenarios/user/${id}`)
}
