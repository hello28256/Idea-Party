// 浏览器凭据存储封装 —— 把 Credential Management API 的能力检测 + 调用 + 静默降级收敛到一处。
// 选用浏览器原生 API（navigator.credentials.store / preventSilentAccess）而非自实现加密：
//   - Chrome / Edge 已自带强密码管理（含泄露检测、自动跨设备同步），重复造轮子既增加风险面又落后于浏览器。
//   - 复用浏览器原生 UX：登录成功后地址栏弹"是否保存密码"气泡，与 GitHub / Notion 一致。
// 调用方：LoginView（登录成功时按用户勾选调 storeCredential）、stores/auth.ts 的 logout（preventSilentAccess）。
// 注意：本文件依赖 Web 标准 Credential Management API；Safari 当前不支持，所有调用必须走 isSupported() 早返回。

/**
 * 能力检测：判断当前浏览器是否支持 PasswordCredential 的保存与阻止自动填充。
 * 检查五个条件：window / navigator / credentials 容器 / store 方法 / preventSilentAccess 方法 / PasswordCredential 构造器。
 * 任一缺失即视为不支持（典型场景：Safari 不支持 PasswordCredential；隐私模式下部分 API 被禁用）。
 */
export function isSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    'credentials' in navigator &&
    typeof navigator.credentials.store === 'function' &&
    typeof navigator.credentials.preventSilentAccess === 'function' &&
    typeof window.PasswordCredential !== 'undefined'
  )
}

/**
 * 主动保存凭据到浏览器密码管理器。
 * 用户在浏览器原生气泡里点"保存"才会真正写入；用户拒绝或浏览器不支持则静默吞错。
 *
 * @param id       用户标识（用户名或邮箱）。浏览器按 origin + id 唯一索引凭据。
 * @param password 明文密码。浏览器内部会按其安全策略加密存储，不经过我们的代码。
 * @param name     显示名（可选）。Chrome / Edge 在密码管理器列表里展示。
 */
export async function storeCredential(
  id: string,
  password: string,
  name?: string
): Promise<void> {
  if (!isSupported()) return

  try {
    // PasswordCredential 构造要求 id + password；name 可缺省时传空串。
    const credential = new window.PasswordCredential({
      id,
      password,
      name: name ?? ''
    })
    await navigator.credentials.store(credential)
  } catch {
    // 静默吞错：失败场景（Safari reject / 隐私模式 / 用户拒绝 / 非 Secure Context）均不应阻断登录主流程。
    // 不打印 console 避免噪音——这是"锦上添花"功能，失败属于降级而非错误。
  }
}

/**
 * 阻止浏览器在下次访问同一 origin 时无声自动登录/填充密码。
 * 用户主动点击密码框触发的"使用已存密码"建议（mediated access）不受影响——这只阻断 silent 路径。
 *
 * 调用方：authStore.logout()。登出后用户期望"账号状态已清空"，不应再被自动登录。
 */
export async function preventSilentAccess(): Promise<void> {
  if (!isSupported()) return

  try {
    await navigator.credentials.preventSilentAccess()
  } catch {
    // 同 storeCredential：静默降级，不应让登出流程失败。
  }
}