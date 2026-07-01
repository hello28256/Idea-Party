// 浏览器直连 OSS 的完整 URL。后端不再拼接,前端这里写死(只在登录/注册/sidebar 三处用到)。
// 修改 OSS 桶或迁移 CDN 时,只需改这两个常量。
// 路径规则: 与 hot-rooms/presets/scenarios 同级,都在 uploads/avatars/brand/ 下面,
// 保持桶内目录结构与 deploy Step 1.5 同步的 idea-server-uploads 卷路径一致。
const OSS_BUCKET_DOMAIN = 'https://idea-party-uploads.oss-cn-shenzhen.aliyuncs.com'

export const BRAND_LOGO = `${OSS_BUCKET_DOMAIN}/uploads/avatars/brand/image.png`
export const BRAND_LOGIN_BG = `${OSS_BUCKET_DOMAIN}/uploads/avatars/brand/login-bg.png`