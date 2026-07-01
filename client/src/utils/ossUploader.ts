import OSS from 'ali-oss'
import { getStsToken } from '@/api/storage'

/**
 * OSS 浏览器直传客户端。
 *
 * 流程:
 *   1. 调后端 /api/uploads/sts-token 拿 STS 临时凭证
 *   2. 用凭证构造 OSS 客户端
 *   3. put(key, file) 直传到 OSS,后端零带宽
 *   4. 返回完整可访问的 OSS URL 给调用方
 *
 * 凭证缓存:每个 StsTokenClient 实例都缓存一份凭证,过期前 5 分钟自动续。
 * 多个文件并行上传复用同一份凭证(同一时刻只发一次 STS 请求)。
 *
 * 限制:单文件 5MB(MIME 白名单 + 后端校验同步),不传大文件。
 */

const RENEW_BEFORE_EXPIRY_MS = 5 * 60 * 1000

interface CachedClient {
  client: OSS
  expireAt: number
  ossDomain: string
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
      const client = new OSS({
        accessKeyId: resp.accessKeyId,
        accessKeySecret: resp.accessKeySecret,
        stsToken: resp.securityToken,
        bucket: resp.bucket,
        region: resp.region.replace(/^oss-/, ''), // ali-oss 要的是 cn-shenzhen 不是 oss-cn-shenzhen
        endpoint: resp.ossDomain,
        secure: true,
      })
      cache = {
        client,
        expireAt: Date.parse(resp.expiration),
        ossDomain: resp.ossDomain,
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
function buildOssKey(prefix: string, file: File): string {
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
 * 上传一个文件到 OSS,返回完整可访问 URL(用于存 DB 或直接 <img src>)。
 * 上传失败抛 Error。
 */
export async function uploadToOss(file: File): Promise<string> {
  const { client, ossDomain, keyPrefix } = await getClient()
  const key = buildOssKey(keyPrefix, file)
  await client.put(key, file)
  // ossDomain 已经包含 bucket,直接拼
  return `${ossDomain}/${key}`
}
