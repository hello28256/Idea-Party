// 浏览器直连 OSS 的完整 URL。后端不再拼接,前端这里写死(只在登录/注册/sidebar 三处用到)。
// 修改 OSS 桶或迁移 CDN 时,只需改这两个常量。
const OSS_BUCKET_DOMAIN = 'https://idea-party-uploads.oss-cn-shenzhen.aliyuncs.com'

export const BRAND_LOGO = `${OSS_BUCKET_DOMAIN}/uploads/brand/image.png`
export const BRAND_LOGIN_BG = `${OSS_BUCKET_DOMAIN}/uploads/brand/login-bg.png`