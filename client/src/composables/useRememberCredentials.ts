import { ref, watch } from 'vue'

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

interface StoredCreds {
  identifier: string
  password: string
}

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

export function useRememberCredentials() {
  const enabled = ref(loadEnabled())
  // 旧方案的明文 identifier 优先回填一次（迁移），然后清掉
  const identifier = ref<string>(enabled.value ? loadIdentifier() : '')

  // 勾选状态变化时立即持久化
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
   */
  function hasStoredCreds(): boolean {
    if (!isCryptoAvailable()) return false
    try { return !!localStorage.getItem(CREDS_KEY) } catch { return false }
  }

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

function loadEnabled(): boolean {
  try {
    return localStorage.getItem(ENABLED_KEY) === '1'
  } catch {
    return false
  }
}

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

function migrateLegacy() {
  try {
    const legacy = localStorage.getItem(LEGACY_KEY)
    if (!legacy) return
    // 仅清除旧 key；具体回填由 LoginView 在挂载时读取 identifier 后再清
    clearLegacy()
  } catch { /* 忽略 */ }
}
