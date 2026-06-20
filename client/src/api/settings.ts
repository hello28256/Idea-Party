import { api } from './auth'

// 用户设置域 REST 客户端，对接后端 SettingsController（/settings/api-key）。
// 集中封装用户级 AI Provider API Key 的读写。
// 复用 auth.ts 中的 axios 实例，保证自动附带 JWT 与统一错误处理；
// 之所以单独抽出一层 settingsApi（而非散落在组件内联调用），
// 是为了后续扩展其他用户偏好（如模型选择、温度）时有统一的入口。
export const settingsApi = {
  /**
   * 读取当前用户保存的 AI Provider API Key。
   * HTTP GET /settings/api-key。
   * 后端对已登录用户返回其保存的 API Key（明文回传，因为前端需在调用 AI 时回填）。
   * 调用方：ApiKeySettingsModal 初始化表单。
   */
  // 后端对已登录用户返回其保存的 API Key（明文回传，因为前端需在调用 AI 时回填）。
  getApiKey: () =>
    api.get<{ apiKey: string }>('/settings/api-key'),

  /**
   * 保存或覆盖当前用户的 API Key。
   * HTTP POST /settings/api-key（body: { apiKey }）。
   * 后端按当前登录用户做隔离，不传 userId。
   * 调用方：ApiKeySettingsModal 提交。
   */
  // 保存或覆盖当前用户的 API Key；后端按当前登录用户做隔离，不传 userId。
  setApiKey: (apiKey: string) =>
    api.post('/settings/api-key', { apiKey }),

  /**
   * 删除当前用户的 API Key，恢复为后端默认/未配置状态。
   * HTTP DELETE /settings/api-key。
   * 调用方：ApiKeySettingsModal「清除 Key」按钮（带确认弹窗）。
   */
  // 删除当前用户的 API Key；恢复为后端默认/未配置状态。
  clearApiKey: () =>
    api.delete('/settings/api-key')
}
