import { onMounted, onUnmounted } from 'vue'
import { messageEventsApi, type EventType } from '@/api/messageEvents'

// 复合函数需要 messageId 来关联后端事件；threshold 留作可选是因为不同消息类型
// （长文/短回复）需要不同的「读完」判定阈值，由调用方决定更合理。
interface TrackOptions {
  messageId: string
  /** ms of dwell after which a READ_COMPLETE event is fired. Default 3000. */
  readCompleteThresholdMs?: number
}

/**
 * Per-message implicit signal tracker.
 *
 * Fires (at most once per session per signal):
 * - COPY      → when the user selects/copies text inside the bubble
 * - READ_COMPLETE → when the bubble is visible for >= threshold
 * - FOCUS     → not auto-fired (reserved for future hover integration)
 *
 * Events are best-effort: failures are swallowed to avoid noisy UI errors
 * since these are observability, not user-facing actions.
 */
// 每个消息气泡独立调用一次本组合式；其生命周期跟 Vue 组件实例绑定，
// 通过 IntersectionObserver 判断「用户是否真的在看」并触发隐式信号事件。
export function useMessageEvents(opts: TrackOptions) {
  // 3000ms 是经验阈值：短于这个时间更像「扫过」，长于这个时间更像「读完」，
  // 后端做参与度分析时以此为分界点。
  const threshold = opts.readCompleteThresholdMs ?? 3000
  let readFired = false
  let dwellTimer: ReturnType<typeof setTimeout> | null = null
  let mountedAt = 0
  let observer: IntersectionObserver | null = null

  // 封装上报：失败仅 debug 打印，不抛错，因为事件是「观测数据」而非用户主动操作，
  // 网络抖动不应污染 UI/UX。
  function fire(eventType: EventType, extra: { dwellMs?: number; metadata?: string } = {}) {
    messageEventsApi.record(opts.messageId, {
      eventType,
      dwellMs: extra.dwellMs,
      metadata: extra.metadata
    }).catch((e) => {
      // Observability only — log and move on.
      console.debug('[Events] record failed', e?.message)
    })
  }

  // 监听的是 document 级 capture 阶段，所以需要在事件回调里再判断来源是否落在本气泡内，
  // 否则同一个 copy 事件会被每条消息各记一次，造成重复上报。
  function onCopy(e: ClipboardEvent) {
    // Only count copies that originated inside this message bubble.
    const target = e.target as HTMLElement | null
    if (target && target.closest(`[data-message-id="${opts.messageId}"]`)) {
      fire('COPY', { metadata: truncate(target.innerText, 200) })
    }
  }

  // 双重 readFired 校验：入参早返 + 回调内再判一次，避免 IntersectionObserver
  // 在定时器触发前又触发一次「可见」回调导致重复发射 READ_COMPLETE。
  function startDwellTimer() {
    if (readFired) return
    dwellTimer = setTimeout(() => {
      if (!readFired) {
        readFired = true
        fire('READ_COMPLETE', { dwellMs: threshold })
      }
    }, threshold)
  }

  // 消息离开视口时要立刻清掉未触发的定时器，否则用户「看一眼就滑走」会被误判为读完。
  function stopDwellTimer() {
    if (dwellTimer) {
      clearTimeout(dwellTimer)
      dwellTimer = null
    }
  }

  // 阈值 0.5 要求「至少一半元素进入视口」才开始计时，避免用户滚动时消息刚露头就被算作「读完」。
  function setupObserver(el: HTMLElement) {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            startDwellTimer()
          } else {
            stopDwellTimer()
          }
        }
      },
      { threshold: 0.5 }
    )
    observer.observe(el)
  }

  // copy 用 capture（第三个参数 true）而不是 bubble，是为了在嵌套组件（代码高亮、
  // 工具栏按钮）可能 stopPropagation 之前拿到事件，避免漏报。
  onMounted(() => {
    mountedAt = Date.now()
    document.addEventListener('copy', onCopy, true)
  })

  // 组件销毁时必须把全局 copy 监听、待触发定时器和 IO 一起释放，否则
  // 会导致已卸载的消息仍然上报事件（内存泄漏 + 数据污染）。
  onUnmounted(() => {
    document.removeEventListener('copy', onCopy, true)
    stopDwellTimer()
    observer?.disconnect()
  })

  return {
    /** Call from the parent when the message bubble element is mounted. */
    setupObserver,
    /** Manually fire REWRITE when the user requests a regeneration. */
    fireRewrite: () => fire('REWRITE'),
    /** Total mount time, useful if you want to fire a different READ signal. */
    get mountedAt() { return mountedAt }
  }
}

// 截取而不是抛错：拷贝长文时只取前 200 字符做 metadata，避免大 payload 撑爆
// 事件表字段（后端 schema 对 metadata 长度有限制）。
function truncate(s: string, n: number): string {
  return s && s.length > n ? s.substring(0, n) : s
}
