import { ref, watch } from 'vue'

// 登录页「记住密码」状态 composable：用 Web Crypto 加密 identifier+password 落盘 localStorage，
// 调用方：LoginView 在挂载时读 hasStoredCreds() 决定是否显示解锁框，登录成功后调 setCredentials 落盘。

/**
 * 加密保存登录凭据的 composable。
 *
 * 设计要点：
 * - 用 Web Crypto API（AES-GCM 256 + PBKDF2-SHA-256 100k 轮）加密 identifier + password
 * - salt (16B) + iv (12B) 与密文一起 base64 后存 localStorage
 * - 主密码复用登录密码（不引入新的密码）
 * - 旧的明文 key `idea-party-remember` 在首次读取时被识别并清除
 */

const ENABLED_KEY = 'idea-party-remember-enabled'
const CREDS_KEY = 'idea-party-creds-v1'        // 加密凭据：base64(salt|iv|ct)
const LEGACY_KEY = 'idea-party-remember'      // 旧明文 identifier，迁移用

const PBKDF2_ITERATIONS = 100_000
const SALT_LEN = 16
const IV_LEN = 12

// 加密后落盘的凭据载荷：明文 JSON 仅存在于加密边界内，落盘即密文
interface StoredCreds {
  identifier: string
  password: string
}

// 旧明文迁移的最小契约：只保留 identifier，方便 LoginView 自动回填用户名框
interface Remembered {
  identifier: string
}

/**
 * 检查 Web Crypto API 是否可用（不支持的环境直接降级到仅缓存 identifier）
 */
function isCryptoAvailable(): boolean {
  return typeof crypto !== 'undefined'
    && typeof crypto.subtle !== 'undefined'
    && typeof crypto.getRandomValues === 'function'
}

// ===== 编码工具 =====

// 手动拼接 + btoa：避免在不支持 Buffer 的浏览器环境引入第三方 polyfill
function bytesToBase64(bytes: Uint8Array): string {
  let bin = ''
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
  return btoa(bin)
}

function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

// ===== 加密原语 =====

// PBKDF2 把「主密码」拉伸为 AES-GCM 密钥；迭代次数跟随 OWASP 2023 推荐（≥600k for SHA-256）
async function deriveKey(passphrase: string, salt: Uint8Array): Promise<CryptoKey> {
  const enc = new TextEncoder()
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    enc.encode(passphrase),
    'PBKDF2',
    false,
    ['deriveKey']
  )
  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      // 把 salt 的 buffer 拷成新的 ArrayBuffer 视图，避开 TS5 的 SharedArrayBuffer 误判
      salt: salt.slice().buffer,
      iterations: PBKDF2_ITERATIONS,
      hash: 'SHA-256',
    },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
}

// 每次加密都生成新 salt + iv，与密文拼接后整体 base64：salt 和 iv 无需单独存储，解密侧按固定偏移切分即可
async function encryptJSON(payload: unknown, passphrase: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_LEN))
  const iv = crypto.getRandomValues(new Uint8Array(IV_LEN))
  const key = await deriveKey(passphrase, salt)
  const plaintext = new TextEncoder().encode(JSON.stringify(payload))
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext)

  const packed = new Uint8Array(salt.length + iv.length + ct.byteLength)
  packed.set(salt, 0)
  packed.set(iv, salt.length)
  packed.set(new Uint8Array(ct), salt.length + iv.length)
  return bytesToBase64(packed)
}

// 解密端必须按 SALT_LEN / IV_LEN 的固定偏移切分；AES-GCM 鉴权失败会抛错，调用方据此判定密码错误
async function decryptJSON<T>(b64: string, passphrase: string): Promise<T> {
  const packed = base64ToBytes(b64)
  const salt = packed.slice(0, SALT_LEN)
  const iv = packed.slice(SALT_LEN, SALT_LEN + IV_LEN)
  const ct = packed.slice(SALT_LEN + IV_LEN)
  const key = await deriveKey(passphrase, salt)
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct)
  return JSON.parse(new TextDecoder().decode(pt)) as T
}

// ===== 旧数据迁移 =====

function loadLegacyIdentifier(): string {
  try {
    const raw = localStorage.getItem(LEGACY_KEY)
    if (!raw) return ''
    const parsed: Remembered = JSON.parse(raw)
    return parsed.identifier ?? ''
  } catch {
    return ''
  }
}

function clearLegacy() {
  try { localStorage.removeItem(LEGACY_KEY) } catch { /* 忽略 */ }
}

// ===== Composable =====

// 登录页「记住密码」的状态胶囊：暴露 enabled/identifier 响应式状态 + set/unlock/hasStoredCreds/clear 操作
// 调用方：LoginView 在挂载时读 hasStoredCreds() 决定是否显示解锁框，登录成功后调 setCredentials 落盘
export function useRememberCredentials() {
  const enabled = ref(loadEnabled())
  // 旧方案的明文 identifier 优先回填一次（迁移），然后清掉
  // 重要：只在 enabled 为 true 时才尝试读 identifier，避免「未勾选记住」时也从旧 key 回填出用户名。
  const identifier = ref<string>(enabled.value ? loadIdentifier() : '')

  // 勾选状态变化时立即持久化；关闭时连带清掉密文与 identifier，避免「勾掉复选框但密文仍在」的隐私残留
  watch(enabled, (val) => {
    if (val) {
      localStorage.setItem(ENABLED_KEY, '1')
    } else {
      localStorage.removeItem(ENABLED_KEY)
      localStorage.removeItem(CREDS_KEY)
      identifier.value = ''
    }
  })

  /**
   * 登录成功后保存凭据（用登录密码作为加密密钥）
   * 副作用：写入 localStorage(CREDS_KEY) 加密 blob，标识同时回填到 identifier ref。
   * 调用方：LoginView 的 submit handler 成功分支。
   */
  async function setCredentials(identifierValue: string, password: string) {
    identifier.value = identifierValue
    if (!enabled.value) return
    if (!identifierValue || !password) return
    if (!isCryptoAvailable()) return  // 环境不支持则只保留 identifier

    try {
      const payload: StoredCreds = { identifier: identifierValue, password }
      const encrypted = await encryptJSON(payload, password)
      localStorage.setItem(CREDS_KEY, encrypted)
    } catch (err) {
      console.error('[useRememberCredentials] encrypt failed:', err)
    }
  }

  /**
   * 兼容旧 API：仅保存 identifier（明文）。新代码请用 setCredentials。
   */
  function setIdentifier(value: string) {
    identifier.value = value
    // 新方案不再单独持久化 identifier（已包含在加密凭据中）
  }

  /**
   * 用主密码（=登录密码）解锁已保存的凭据。
   * 成功时回填 identifier + password；密码错误时返回 null。
   * 注意：传入的是用户当前输入的密码，与 setCredentials 时的密码必须完全一致（PBKDF2 是确定性的）
   * 调用方：LoginView 的「解锁已保存凭据」表单。
   */
  async function unlock(passphrase: string): Promise<StoredCreds | null> {
    if (!isCryptoAvailable()) return null
    const raw = localStorage.getItem(CREDS_KEY)
    if (!raw) return null

    try {
      const creds = await decryptJSON<StoredCreds>(raw, passphrase)
      identifier.value = creds.identifier
      return creds
    } catch {
      // 解密失败 = 密码错误
      return null
    }
  }

  /**
   * 是否存在加密凭据（即「记住我」生效且曾成功登录过）
   * 用途：LoginView 挂载时据此决定显示「解锁」入口还是普通登录表单。
   */
  function hasStoredCreds(): boolean {
    if (!isCryptoAvailable()) return false
    try { return !!localStorage.getItem(CREDS_KEY) } catch { return false }
  }

  // 与「关闭复选框」不同：clear() 由用户在已解锁页主动调用，仅清密文+enabled flag，不影响当前会话内的 identifier 引用重置
  function clear() {
    identifier.value = ''
    enabled.value = false
    localStorage.removeItem(CREDS_KEY)
    localStorage.removeItem(ENABLED_KEY)
  }

  // 首次加载时执行一次旧数据迁移
  migrateLegacy()

  return {
    enabled,
    identifier,
    setCredentials,
    setIdentifier,
    unlock,
    hasStoredCreds,
    clear,
  }
}

// 用字符串 '1' 而非 boolean 序列化：避免某些浏览器把 'true'/'false' 当字符串解析时与历史值产生歧义
function loadEnabled(): boolean {
  try {
    return localStorage.getItem(ENABLED_KEY) === '1'
  } catch {
    return false
  }
}

// 加密凭据存在时不主动回填 identifier——避免在用户未输入密码前就把密文里的标识暴露到 DOM 输入框
function loadIdentifier(): string {
  try {
    const raw = localStorage.getItem(CREDS_KEY)
    if (raw) return ''  // 加密凭据存在时，identifier 走 unlock 流程
    // 回退到旧明文（仅迁移用）
    return loadLegacyIdentifier()
  } catch {
    return ''
  }
}

// 旧数据清理刻意只删 key、不搬数据：避免迁移期密码错误时把唯一可恢复的 identifier 也清掉
function migrateLegacy() {
  try {
    const legacy = localStorage.getItem(LEGACY_KEY)
    if (!legacy) return
    // 仅清除旧 key；具体回填由 LoginView 在挂载时读取 identifier 后再清
    clearLegacy()
  } catch { /* 忽略 */ }
}
