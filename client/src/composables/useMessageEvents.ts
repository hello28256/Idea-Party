import { onMounted, onUnmounted } from 'vue'
import { messageEventsApi, type EventType } from '@/api/messageEvents'

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
export function useMessageEvents(opts: TrackOptions) {
  const threshold = opts.readCompleteThresholdMs ?? 3000
  let readFired = false
  let dwellTimer: ReturnType<typeof setTimeout> | null = null
  let mountedAt = 0
  let observer: IntersectionObserver | null = null

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

  function onCopy(e: ClipboardEvent) {
    // Only count copies that originated inside this message bubble.
    const target = e.target as HTMLElement | null
    if (target && target.closest(`[data-message-id="${opts.messageId}"]`)) {
      fire('COPY', { metadata: truncate(target.innerText, 200) })
    }
  }

  function startDwellTimer() {
    if (readFired) return
    dwellTimer = setTimeout(() => {
      if (!readFired) {
        readFired = true
        fire('READ_COMPLETE', { dwellMs: threshold })
      }
    }, threshold)
  }

  function stopDwellTimer() {
    if (dwellTimer) {
      clearTimeout(dwellTimer)
      dwellTimer = null
    }
  }

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

  onMounted(() => {
    mountedAt = Date.now()
    document.addEventListener('copy', onCopy, true)
  })

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

function truncate(s: string, n: number): string {
  return s && s.length > n ? s.substring(0, n) : s
}
