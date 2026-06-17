import { api } from './auth'

export interface InterviewScenarioRequest {
  position: string
  industry?: string
  experienceYears?: number
  jobDescription?: string
  resumeContent?: string
}

export interface InterviewScenarioResponse {
  characterName: string
  prompt: string
}

export interface ParseResumeResponse {
  text: string
  length: number
  filename: string
  truncated: boolean
}

export interface ExtractTextFromImageResponse {
  text: string
  length: number
  filename: string
  truncated: boolean
}

export const scenariosApi = {
  /**
   * 根据岗位/行业/经验/JD/简历 动态生成面试官 prompt
   */
  generateInterviewPrompt: (data: InterviewScenarioRequest) =>
    api.post<InterviewScenarioResponse>('/scenarios/interview/generate-prompt', data),

  /**
   * 解析上传的简历文件（docx/pdf/txt），返回纯文本
   */
  parseResume: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post<ParseResumeResponse>('/scenarios/interview/parse-resume', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  /**
   * 识别 JD 截图（png/jpg/webp/gif），返回提取的纯文本
   */
  extractTextFromImage: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return api.post<ExtractTextFromImageResponse>('/scenarios/interview/extract-text-from-image', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  }
}
