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
