// 浏览器直连 COS 的完整 URL。后端不再拼接,前端这里写死(只在登录/注册/sidebar 三处用到)。
// 修改 COS 桶或迁移 CDN 时,只需改这两个常量。
// 路径规则: 与 hot-rooms/presets/scenarios 同级,都在 uploads/avatars/brand/ 下面,
// 保持桶内目录结构与 deploy Step 1.5 同步的 idea-server-uploads 卷路径一致。
// 桶 idea-party-uploads-1361890600 在腾讯云首尔 (ap-seoul),与 ECS 同地域,零跨境。
const COS_BUCKET_DOMAIN = 'https://idea-party-uploads-1361890600.cos.ap-seoul.myqcloud.com'

export const BRAND_LOGO = `${COS_BUCKET_DOMAIN}/uploads/avatars/brand/image.png`
export const BRAND_LOGIN_BG = `${COS_BUCKET_DOMAIN}/uploads/avatars/brand/login-bg.png`