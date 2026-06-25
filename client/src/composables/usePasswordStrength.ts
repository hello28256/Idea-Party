// 密码强度评估 —— 模块级单例函数，与后端 StrongPasswordValidator 规则严格对齐。
//
// 调用方：LoginView 注册模式、RegisterView 等任何需要实时反馈密码强度的场景。
// 设计要点：
//   - 不依赖 Vue ref：调用方把 password 字符串传进来即可，避免和组件 reactivity 紧耦合。
//   - 规则同步：黑名单 30 条、长度阈值、必须字母+必须数字，都必须与后端一字不差，
//     否则会出现「前端说通过、后端拒」的体验割裂（前端是「即时反馈」，后端是「最终防线」）。
//   - 大小写归一：黑名单匹配前 .toLowerCase()，避免 Admin123 这类大小写变体绕过。

/**
 * 密码强度的四档评分：0 = 空；1 = 弱（任一规则未满足）；2 = 中（预留，目前规则下不会触发）；3 = 强（全通过）。
 * UI 层根据 score 决定色块颜色与文字提示。
 */
export type PasswordStrength = 0 | 1 | 2 | 3

/**
 * 强度的中文标签，供 UI 直接展示。
 */
export type StrengthLabel = '空' | '弱' | '中' | '强'

/**
 * 四项规则的逐项检查结果：UI 想做"✓ 长度足够 / ✗ 需要数字"那种清单时直接遍历。
 */
export interface StrengthChecks {
  /** 长度 >= 8 */
  length: boolean
  /** 至少含一个字母 [A-Za-z] */
  hasLetter: boolean
  /** 至少含一个数字 [0-9] */
  hasDigit: boolean
  /** 不在 30 条常见弱密码黑名单中（lowercase 后比较） */
  notCommon: boolean
}

/**
 * 评估结果：score 用于驱动色块颜色，message 用于显示单行中文说明，checks 用于做清单 UI（如未来需要）。
 */
export interface StrengthResult {
  score: PasswordStrength
  label: StrengthLabel
  checks: StrengthChecks
  /** 单行中文：空态时是「请输入密码…」；非空时列出当前不满足的规则，全满足时是「密码强度符合要求」 */
  message: string
}

// 黑名单：与 server/src/main/java/com/ideaparty/validation/StrongPasswordValidator.java
// 内的 COMMON_PASSWORDS 完全一致。修改时务必同步两边。
const COMMON_PASSWORDS: ReadonlySet<string> = new Set([
  'password', '12345678', '123456789', '1234567890',
  'qwerty', 'qwerty123', '11111111', '00000000',
  'admin', 'admin123', 'admin1234', 'administrator',
  'letmein', 'welcome', 'monkey', 'dragon',
  'iloveyou', 'princess', 'football', 'baseball',
  'sunshine', 'master', 'shadow', 'superman',
  'trustno1', 'abc12345', 'abcd1234', 'asdf1234',
  'qazwsx', 'zxcvbnm', '1q2w3e4r'
])

const LETTER_RE = /[A-Za-z]/
const DIGIT_RE = /[0-9]/

/**
 * 评估一个明文密码的强度。返回结构化结果，UI 按需消费。
 *
 * @param raw 用户输入的密码（明文）；空串 / null 视为「空态」。
 */
export function evaluatePassword(raw: string | null | undefined): StrengthResult {
  const password = raw ?? ''

  // 空态：score=0、label='空'、checks 全 false、message 引导用户开始输入。
  if (password.length === 0) {
    return {
      score: 0,
      label: '空',
      checks: { length: false, hasLetter: false, hasDigit: false, notCommon: false },
      message: '请输入密码（至少 8 位，含字母和数字）'
    }
  }

  const checks: StrengthChecks = {
    length: password.length >= 8,
    hasLetter: LETTER_RE.test(password),
    hasDigit: DIGIT_RE.test(password),
    // 大小写归一：黑名单是全小写，避免 "Admin123" 绕过
    notCommon: !COMMON_PASSWORDS.has(password.toLowerCase())
  }

  // 评分：当前规则下只有「任一失败 → 弱」与「全通过 → 强」两种情况，'中' 预留供未来加复杂度。
  // 黑名单命中单独走 score=1，让 message 能区分「弱密码」和「缺字符」。
  let score: PasswordStrength
  let label: StrengthLabel
  if (!checks.length || !checks.hasLetter || !checks.hasDigit) {
    score = 1
    label = '弱'
  } else if (!checks.notCommon) {
    score = 1
    label = '弱'
  } else {
    score = 3
    label = '强'
  }

  // 把当前不满足的规则拼成一句：UI 直接展示即可。
  const missing: string[] = []
  if (!checks.length) missing.push('至少 8 位')
  if (!checks.hasLetter) missing.push('含字母')
  if (!checks.hasDigit) missing.push('含数字')
  if (!checks.notCommon) missing.push('不能是常见弱密码')
  const message = missing.length === 0
    ? '密码强度符合要求'
    : `密码需：${missing.join('、')}`

  return { score, label, checks, message }
}