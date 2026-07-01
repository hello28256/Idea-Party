/**
 * 把各种形态的图片 URL 归一化成浏览器可直接 <img src> 的完整 URL。
 *
 * 处理:
 *   - 完整 https://... URL → 原样返回(已经是 OSS / 外链直链)
 *   - 相对路径 /uploads/... → 拼 VITE_OSS_BUCKET_DOMAIN
 *   - 相对路径 /api/upload/avatars/... → 同上(老 nginx 301 兜底)
 *   - null / undefined / '' → 返回 undefined(让调用方决定 placeholder)
 *
 * 不在 OSS 时代的迁移期内,/uploads/ 路径由 nginx 301 到 OSS,所以即便
 * DB 里没改全,前端也能渲染。迁移完后 DB 全是完整 URL,这里就走 default 分支。
 */
const OSS_DOMAIN = (import.meta.env.VITE_OSS_BUCKET_DOMAIN as string | undefined) || ''

export function resolveImageUrl(url: string | null | undefined): string | undefined {
  if (!url) return undefined
  if (/^https?:\/\//i.test(url)) return url
  if (!OSS_DOMAIN) {
    // 没配 OSS 域名时,相对路径走 nginx(开发或迁移期)
    return url
  }
  // 去掉开头的 /uploads 或 /,确保只有一段 /
  const path = url.replace(/^\/+/, '')
  return `${OSS_DOMAIN}/${path}`
}
