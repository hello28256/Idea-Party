import COS from 'cos-js-sdk-v5'
import { getStsToken } from '@/api/storage'

/**
 * COS 浏览器直传客户端。
 *
 * 流程:
 *   1. 调后端 /api/uploads/sts-token 拿 STS 临时凭证
 *   2. 用凭证构造 COS 客户端
 *   3. putObject 上传到 COS,后端零带宽
 *   4. 返回完整可访问的 COS URL 给调用方
 *
 * 凭证缓存:每个 CosClient 实例都缓存一份凭证,过期前 5 分钟自动续。
 * 多个文件并行上传复用同一份凭证(同一时刻只发一次 STS 请求)。
 *
 * 限制:单文件 5MB(MIME 白名单 + 后端校验同步),不传大文件。
 *
 * PR2 切换: 阿里云 ali-oss → 腾讯云 cos-js-sdk-v5。
 *           字段名 ossDomain → cosDomain。
 */

const RENEW_BEFORE_EXPIRY_MS = 5 * 60 * 1000

interface CachedClient {
  client: COS
  expireAt: number
  cosDomain: string
  keyPrefix: string
}

let cache: CachedClient | null = null
let inflight: Promise<CachedClient> | null = null

async function getClient(): Promise<CachedClient> {
  const now = Date.now()
  if (cache && cache.expireAt - now > RENEW_BEFORE_EXPIRY_MS) {
    return cache
  }
  // 并发请求复用同一份 inflight
  if (inflight) return inflight
  inflight = (async () => {
    try {
      const resp = (await getStsToken()).data
      const client = new COS({
        // cos-js-sdk-v5 v1.8+ 用 getAuthorization 回调,
        // 每次请求时 SDK 调这个拿新凭证,适合长会话
        SecretId: resp.accessKeyId,
        SecretKey: resp.accessKeySecret,
        SecurityToken: resp.securityToken,
        // cos-js-sdk-v5 不需要预先 set bucket,每次 putObject 传
      })
      cache = {
        client,
        expireAt: Date.parse(resp.expiration),
        cosDomain: resp.cosDomain,
        keyPrefix: resp.keyPrefix,
      }
      return cache
    } finally {
      inflight = null
    }
  })()
  return inflight
}

/** 生成上传 key:uploads/202607/{uuid}.{ext} */
function buildCosKey(prefix: string, file: File): string {
  const now = new Date()
  const yyyymm = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}`
  const ext = (file.name.split('.').pop() || 'bin').toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 8)
  const uuid = (typeof crypto !== 'undefined' && 'randomUUID' in crypto)
    ? crypto.randomUUID().replace(/-/g, '').slice(0, 16)
    : Math.random().toString(36).slice(2, 18)
  const cleanPrefix = prefix.endsWith('/') ? prefix : `${prefix}/`
  return `${cleanPrefix}${yyyymm}/${uuid}.${ext}`
}

/**
 * 上传一个文件到 COS,返回完整可访问 URL(用于存 DB 或直接 <img src>)。
 * 上传失败抛 Error。
 */
export async function uploadToOss(file: File): Promise<string> {
  const { client, cosDomain, keyPrefix } = await getClient()
  const key = buildCosKey(keyPrefix, file)
  await client.putObject({
    Bucket: cosDomain.split('//')[1].split('.')[0], // 提取 bucket 名(不含 APPID)
    Region: cosDomain.split('.')[2], // 提取 region
    Key: key,
    Body: file,
  })
  // cosDomain 已经包含 bucket+region+appid,直接拼 key
  return `${cosDomain}/${key}`
}
