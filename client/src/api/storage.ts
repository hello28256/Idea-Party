import { api } from './auth'

/**
 * 腾讯云 CAM STS 临时凭证响应。
 * 后端 GET /api/uploads/sts-token 返回,有效期通常 1 小时,前端拿到即传。
 *
 * PR2 切换: 阿里云 OSS → 腾讯云 COS,字段名 ossDomain → cosDomain。
 */
export interface StsTokenResponse {
  /** STS 临时 SecretId(腾讯云字段名是 TmpSecretId) */
  accessKeyId: string
  /** STS 临时 SecretKey(腾讯云字段名是 TmpSecretKey) */
  accessKeySecret: string
  /** STS SecurityToken(腾讯云字段名是 Token) */
  securityToken: string
  /** 凭证过期时间,ISO-8601 字符串(Date.parse 可直接吃) */
  expiration: string
  /** COS 桶名(带 APPID),如 idea-party-uploads-1361890600 */
  bucket: string
  /** COS Region,如 ap-seoul */
  region: string
  /** COS 桶默认访问域名,如 https://idea-party-uploads-1361890600.cos.ap-seoul.myqcloud.com */
  cosDomain: string
  /** 上传 key 前缀,如 uploads/ */
  keyPrefix: string
}

/**
 * 拉取 STS 临时凭证。需要在登录态调用,后端会校验 JWT。
 * 失败抛 AxiosError(401/403/500 等)。
 */
export const getStsToken = () =>
  api.get<StsTokenResponse>('/uploads/sts-token')
