/**
 * 把各种形态的图片 URL 归一化成浏览器可直接 <img src> 的完整 URL。
 *
 * 处理:
 *   - 完整 https://... URL → 原样返回(已经是 OSS / 外链直链)
 *   - 相对路径 /uploads/... → 拼 VITE_OSS_BUCKET_DOMAIN
 *   - null / undefined / '' → 返回 undefined(让调用方决定 placeholder)
 *
 * 没配 VITE_OSS_BUCKET_DOMAIN 时,/uploads/ 相对路径由 nginx 301 到 OSS,前端能渲染。
 * 配了之后,浏览器直接拼完整 URL 走 OSS,省一跳 nginx 301。
 */
const OSS_DOMAIN = (import.meta.env.VITE_OSS_BUCKET_DOMAIN as string | undefined) || ''

export function resolveImageUrl(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  if (/^https?:\/\//i.test(url)) return url
  if (!OSS_DOMAIN) {
    // 没配 OSS 域名时,相对路径走 nginx(开发或迁移期)
    return url
  }
  // 去掉开头的 / 或 //,确保只有一段 /
  const path = url.replace(/^\/+/, '')
  return `${OSS_DOMAIN}/${path}`
}
