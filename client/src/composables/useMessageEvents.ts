import { onMounted, onUnmounted } from 'vue'
import { messageEventsApi, type EventType } from '@/api/messageEvents'

// 单条消息的「隐式用户行为」埋点 composable：监听气泡可见性、复制事件等，
// 把 COPY / READ_COMPLETE / REWRITE 等非主动信号上报到后端做参与度分析。
// 调用方：MessageList 渲染每条消息气泡时各调用一次，setupObserver 需在气泡 ref 挂载后触发。

// 复合函数需要 messageId 来关联后端事件；threshold 留作可选是因为不同消息类型
// （长文/短回复）需要不同的「读完」判定阈值，由调用方决定更合理。
interface TrackOptions {
  messageId: string
  /** 用户停留多少毫秒后触发 READ_COMPLETE 事件，默认 3000。 */
  readCompleteThresholdMs?: number
}

/**
 * 单条消息的隐式信号追踪器。
 *
 * （同一会话内每种信号最多触发一次）
 * - COPY      → 用户在气泡内选中/复制文本时触发
 * - READ_COMPLETE → 气泡在视口内停留达到阈值时触发
 * - FOCUS     → 暂未自动触发（为未来 hover 集成预留）
 *
 * 事件为 best-effort：上报失败会被吞掉，避免噪声 UI 错误，
 * 因为它们只是观测数据，不是用户主动行为。
 *
 * 资源清理：document 级 copy 监听、定时器、IntersectionObserver 都在 onUnmounted 中释放，
 * 调用方无需手动 destroy。
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
      // 仅用于观测 —— 记日志后继续。
      console.debug('[Events] record failed', e?.message)
    })
  }

  // 监听的是 document 级 capture 阶段，所以需要在事件回调里再判断来源是否落在本气泡内，
  // 否则同一个 copy 事件会被每条消息各记一次，造成重复上报。
  function onCopy(e: ClipboardEvent) {
    // 仅统计源自本消息气泡内的 copy 事件。
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
    /** 当消息气泡元素挂载后由父组件调用。 */
    setupObserver,
    /** 用户请求重新生成时手动触发 REWRITE。 */
    fireRewrite: () => fire('REWRITE'),
    /** 总挂载时间，若想发出不同 READ 信号可使用。 */
    get mountedAt() { return mountedAt }
  }
}

// 截取而不是抛错：拷贝长文时只取前 200 字符做 metadata，避免大 payload 撑爆
// 事件表字段（后端 schema 对 metadata 长度有限制）。
function truncate(s: string, n: number): string {
  return s && s.length > n ? s.substring(0, n) : s
}
