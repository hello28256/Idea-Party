import { api } from './auth'

/**
 * 阿里云 STS 临时凭证响应。
 * 后端 GET /api/uploads/sts-token 返回,有效期通常 1 小时,前端拿到即传。
 */
export interface StsTokenResponse {
  /** STS 临时 AccessKeyId(以 STS. 开头) */
  accessKeyId: string
  /** STS 临时 AccessKeySecret */
  accessKeySecret: string
  /** STS SecurityToken,PutObject 时必须带 */
  securityToken: string
  /** 凭证过期时间,ISO-8601 字符串(Date.parse 可直接吃) */
  expiration: string
  /** OSS 桶名,如 idea-party-uploads */
  bucket: string
  /** OSS Region,如 oss-cn-shenzhen */
  region: string
  /** OSS 桶默认访问域名,如 https://idea-party-uploads.oss-cn-shenzhen.aliyuncs.com */
  ossDomain: string
  /** 上传 key 前缀,如 uploads/ */
  keyPrefix: string
}

/**
 * 拉取 STS 临时凭证。需要在登录态调用,后端会校验 JWT。
 * 失败抛 AxiosError(401/403/500 等)。
 */
export const getStsToken = () =>
  api.get<StsTokenResponse>('/uploads/sts-token')
