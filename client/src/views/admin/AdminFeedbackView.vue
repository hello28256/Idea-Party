<script setup lang="ts">
// AdminFeedbackView：路由 /admin/feedbacks（管理后台域）
// AdminFeedbackView：管理员后台的「消息反馈总览」页
// 从后端聚合视图拉取所有 AI 回复消息 + 反馈汇总，支持按状态/用户筛选、
// 分页浏览、点击行打开反馈详情。配合 AdminFeedbackDetailModal 展示单条反馈。
import { onMounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import { ThumbsUp, ThumbsDown, CheckCircle2, AlertCircle, XCircle } from 'lucide-vue-next'
import { api } from '@/api/auth'
import type { AdminFeedbackDetail, AdminListParams } from '@/api/messageFeedback'
import AdminFeedbackDetailModal from '@/components/admin/AdminFeedbackDetailModal.vue'

// AdminMessageObservation：后端 /admin/messages 聚合视图的单行 DTO
// 把「一条 AI 消息」和「它累计收到的反馈（任意用户的 like/dislike）」聚合在一起，
// 避免前端多次拼装。AGGREGATED 即「被任意用户评过」的合并语义。
interface AdminMessageObservation {
  messageId: string
  roomId: string
  roomName: string | null
  characterId: string | null
  characterName: string | null
  userId: string | null
  username: string | null
  displayName: string | null
  messagePreview: string | null
  messageCreatedAt: string | null
  streamStatus?: 'COMPLETE' | 'EMPTY' | 'FAILED' | null
  userPrompt?: string | null
  userPromptAt?: string | null
  promptUserId?: string | null
  promptUsername?: string | null
  promptDisplayName?: string | null
  feedbackCount: number
  likeCount: number
  dislikeCount: number
  lastFeedbackAt: string | null
  status: 'RATED' | 'UNRATED' | 'AGGREGATED'
  feedbackType: 'LIKE' | 'DISLIKE' | null
  feedbackCategory: string | null
  feedbackComment: string | null
  userFeedbackAt: string | null
}

// PageResponse：与后端 Spring Data Page 序列化对齐的最小分页壳
// 之所以不复用公共类型：本页只关心 content + totalElements，自带壳更轻、无外部依赖。
interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

// STATUS_OPTIONS：状态过滤器下拉项
// 空字符串表示「不过滤」，与服务端约定的 status 参数语义一致（空=全部）。
const STATUS_OPTIONS = [
  { value: '', label: '全部消息' },
  { value: 'UNRATED', label: '未反馈' },
  { value: 'AGGREGATED', label: '已反馈（汇总）' },
  { value: 'RATED', label: '当前用户已评' }
]

const router = useRouter()

const items = ref<AdminMessageObservation[]>([])
const total = ref(0)
const page = ref(0)
const size = 10 // 固定每页 10 条：管理员巡检场景下 10 行刚好一屏可览，分页粒度足够细
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const jumpToInput = ref('')

// Server-side stat counts (driven by totalElements of each filtered query).
// FEEDBACK_EXISTS 是后端的「任意用户评过」桶，前端把 RATED/AGGREGATED 卡片都映射到它，
// 因此服务端无需为两个卡片各发一次查询，节省一半的 count 请求。
const statTotals = ref<{ UNRATED: number; RATED: number; AGGREGATED: number; FEEDBACK_EXISTS: number }>({
  UNRATED: 0, RATED: 0, AGGREGATED: 0, FEEDBACK_EXISTS: 0
})

const filterStatus = ref<string>('')
const filterUserId = ref<string>('')
// filterFrom / filterTo 暂未拼接到请求：保留 UI 占位便于后续接入服务端时间区间过滤，
// 而不必改动布局与管理员已有的操作习惯。
const filterFrom = ref<string>('')
const filterTo = ref<string>('')

const loading = ref(false)
const error = ref<string | null>(null)
const showDetail = ref(false)
const detail = ref<AdminFeedbackDetail | null>(null)

// load()：拉取当前筛选条件下的消息分页，并异步刷新顶部统计卡片
// 入参：依赖外部 ref（filterStatus/filterUserId/page/size）；副作用：写 items/total/loading/error。
// 每次翻页或筛条件变化都重新触发；统计刷新另发小查询，结果不影响主表格。
async function load() {
  loading.value = true
  error.value = null
  try {
    const params: Record<string, string | number> = {
      page: page.value,
      size
    }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterUserId.value.trim()) params.userId = filterUserId.value.trim()

    const data = await api.get<PageResponse<AdminMessageObservation>>('/admin/messages', { params })
      .then(r => r.data)
    items.value = data.content
    total.value = data.totalElements
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
  // Refresh the global counters (cheap: size=1)
  refreshStatTotals()
}

function applyFilters() {
  page.value = 0
  load()
}

function resetFilters() {
  filterStatus.value = ''
  filterUserId.value = ''
  filterFrom.value = ''
  filterTo.value = ''
  page.value = 0
  load()
}

function goPage(p: number) {
  if (p < 0 || p >= totalPages.value) return
  page.value = p
  load()
}

// jumpToPage()：把用户输入的 1-based 页码转换为 0-based 并跳转
// 输入越界时吸附到最近的有效页而非报错——管理员手抖常见，体验优先。
function jumpToPage() {
  const target = parseInt(jumpToInput.value, 10)
  if (Number.isNaN(target)) return
  if (target < 1 || target > totalPages.value) {
    // Snap to valid range instead of silently failing
    goPage(Math.min(Math.max(1, target), totalPages.value) - 1)
    jumpToInput.value = ''
    return
  }
  goPage(target - 1)
  jumpToInput.value = ''
}

// refreshStatTotals()：best-effort 拉取顶部卡片的全局计数
// 失败时只静默退化（卡片显示 0），不阻塞主表格——统计属于辅助信息，不应成为错误源。
async function refreshStatTotals() {
  // Issue one count-only query per status so the summary cards reflect
  // the full database, not just the current page slice.
  const statuses = ['UNRATED', 'FEEDBACK_EXISTS'] as const
  try {
    const results = await Promise.all(
      statuses.map(s =>
        api.get<PageResponse<AdminMessageObservation>>('/admin/messages', {
          params: { page: 0, size: 1, status: s }
        }).then(r => r.data.totalElements)
      )
    )
    statTotals.value.UNRATED = results[0]
    statTotals.value.FEEDBACK_EXISTS = results[1]
  } catch (e) {
    // Best-effort; cards will just show 0
    console.debug('[Stats] refresh failed', e)
  }
}

function statCountFor(value: string): number {
  switch (value) {
    case 'UNRATED': return statTotals.value.UNRATED
    case 'RATED': return statTotals.value.FEEDBACK_EXISTS // same server count
    case 'AGGREGATED': return statTotals.value.FEEDBACK_EXISTS
    default: return 0
  }
}

// openDetail()：打开某条消息的反馈详情弹窗
// 列表视图不携带单条 feedbackId，所以走「先按 messageId 命中，否则用列表兜底」的两段式查找。
async function openDetail(item: AdminMessageObservation) {
  // Only meaningful for messages that have at least one feedback.
  if (item.feedbackCount === 0) {
    error.value = '该消息还没有任何反馈，无法查看详情'
    return
  }
  try {
    // The list page doesn't know per-user feedback id. We fetch the latest
    // feedback row for this message via the existing admin feedback list
    // with a server-side filter (messageId). Simpler: just fetch by the
    // most recent feedback row for the message — but we don't expose that.
    // Easiest: open the existing detail modal by querying the message's
    // first feedback id from the legacy endpoint.
    const list = await api.get<PageResponse<AdminFeedbackDetail>>('/admin/feedbacks', {
      params: { page: 0, size: 1, userId: item.userId ?? undefined } as AdminListParams
    }).then(r => r.data)
    // Fallback: pick the first feedback where messageId matches.
    const direct = await fetchAnyFeedbackForMessage(item.messageId)
    if (direct) {
      detail.value = direct
      showDetail.value = true
    } else if (list.content.length > 0) {
      detail.value = list.content[0]
      showDetail.value = true
    } else {
      error.value = '未找到该消息的反馈记录'
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载详情失败'
  }
}

// fetchAnyFeedbackForMessage()：MVP 兜底查询，给定 messageId 找一条反馈详情
// 仅在 openDetail 的精确路径失败时调用；外部不直接依赖。
async function fetchAnyFeedbackForMessage(messageId: string): Promise<AdminFeedbackDetail | null> {
  // The legacy list endpoint accepts page/size only — we cannot filter by
  // messageId server-side. Page through up to 50 entries looking for a
  // match. Cheap fallback for the MVP.
  for (let p = 0; p < 5; p++) {
    const data = await api.get<PageResponse<AdminFeedbackDetail>>('/admin/feedbacks', {
      params: { page: p, size: 20 } as AdminListParams
    }).then(r => r.data)
    const hit = data.content.find(c => c.messageId === messageId)
    if (hit) return hit
    if (data.content.length < 20) break
  }
  return null
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  // Compact: MM-DD HH:mm:ss — fits in a narrow column
  const d = new Date(iso)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function streamStatusClass(s: string | null | undefined): string {
  if (!s || s === 'COMPLETE') return 'ok'
  if (s === 'EMPTY') return 'empty'
  return 'failed'
}

/**
 * Response latency = AI reply timestamp - prior user prompt timestamp.
 * Returns null when there's no prior user message (legacy or root prompts).
 */
function responseLatencyMs(item: AdminMessageObservation): number | null {
  if (!item.userPromptAt || !item.messageCreatedAt) return null
  const prompt = new Date(item.userPromptAt).getTime()
  const reply = new Date(item.messageCreatedAt).getTime()
  const diff = reply - prompt
  return diff >= 0 ? diff : null
}

/** Bucket for color: green <2s, amber 2-5s, orange 5-10s, red >10s. */
function latencyClass(ms: number): string {
  if (ms < 2000) return 'latency-fast'
  if (ms < 5000) return 'latency-ok'
  if (ms < 10000) return 'latency-slow'
  return 'latency-bad'
}

onMounted(load)
</script>

<template>
  <div class="admin-feedback-view">
    <header class="page-header">
      <div class="title-row">
        <button class="back-btn" @click="router.push('/')" aria-label="返回主页">
          <ArrowLeft :size="16" />
          <span>返回主页</span>
        </button>
        <h1>消息总览</h1>
      </div>
      <p class="subtitle">所有 AI 回复的反馈情况：已反馈的看用户评价，未反馈的主动跟进</p>
    </header>

    <section class="summary">
      <div class="stat">
        <span class="stat-num">{{ total }}</span>
        <span class="stat-label">总消息数</span>
      </div>
      <div class="stat" v-for="opt in STATUS_OPTIONS.filter(o => o.value)" :key="opt.value">
        <span class="stat-num">{{ statCountFor(opt.value) }}</span>
        <span class="stat-label">{{ opt.label }}</span>
      </div>
    </section>

    <section class="filters">
      <div class="filter-row">
        <label class="filter">
          <span>状态</span>
          <select v-model="filterStatus" @change="applyFilters">
            <option v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </label>
        <label class="filter">
          <span>反馈用户</span>
          <input v-model="filterUserId" type="text" placeholder="用户名关键词" @keyup.enter="applyFilters" />
        </label>
        <button class="reset-btn" @click="resetFilters">重置</button>
      </div>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section class="table-wrap">
      <table v-if="!loading && items.length > 0">
        <thead>
          <tr>
            <th>提问用户 ID</th>
            <th>用户提问</th>
            <th>AI 回复</th>
            <th>输出</th>
            <th>汇总</th>
            <th>角色 / 房间</th>
            <th>最后反馈</th>
            <th>响应延迟</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.messageId" @click="openDetail(item)">
            <td class="user-cell">
              <span v-if="item.promptUserId" class="user-id" :title="item.promptUserId">
                {{ item.promptUserId.slice(0, 8) }}
              </span>
              <span v-else class="muted">—</span>
            </td>
            <td class="prompt-cell">
              <span v-if="item.userPrompt" class="prompt-text">{{ item.userPrompt }}</span>
              <span v-else class="muted">（无上下文）</span>
            </td>
            <td class="preview-cell">
              <div class="preview-text">{{ item.messagePreview || '—' }}</div>
            </td>
            <td>
              <span class="status-pill" :class="streamStatusClass(item.streamStatus)">
                <CheckCircle2 v-if="item.streamStatus === 'COMPLETE' || !item.streamStatus" :size="12" />
                <AlertCircle v-else-if="item.streamStatus === 'EMPTY'" :size="12" />
                <XCircle v-else :size="12" />
                {{
                  !item.streamStatus || item.streamStatus === 'COMPLETE' ? '成功'
                  : item.streamStatus === 'EMPTY' ? '空'
                  : '失败'
                }}
              </span>
            </td>
            <td class="rollup">
              <template v-if="item.likeCount > 0 || item.dislikeCount > 0">
                <ThumbsUp
                  v-if="item.likeCount >= item.dislikeCount"
                  :size="14"
                  class="thumbs-up"
                />
                <ThumbsDown v-else :size="14" class="thumbs-down" />
                <span
                  class="count"
                  :class="item.likeCount >= item.dislikeCount ? 'count-up' : 'count-down'"
                >
                  {{ Math.max(item.likeCount, item.dislikeCount) }}
                </span>
              </template>
              <span v-else class="muted">—</span>
            </td>
            <td>
              <div class="ctx-cell">
                <span v-if="item.characterName" class="char-name">{{ item.characterName }}</span>
                <span v-if="item.characterName && item.roomName" class="dot">·</span>
                <span v-if="item.roomName" class="room-name">{{ item.roomName }}</span>
              </div>
            </td>
            <td class="time-cell">{{ formatTime(item.lastFeedbackAt || item.messageCreatedAt) }}</td>
            <td class="latency-cell">
              <span v-if="responseLatencyMs(item) !== null"
                    class="latency"
                    :class="latencyClass(responseLatencyMs(item)!)">
                {{ responseLatencyMs(item) }} ms
              </span>
              <span v-else class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="loading" class="empty">加载中...</div>
      <div v-else class="empty">没有匹配的消息</div>
    </section>

    <footer v-if="totalPages > 1" class="pagination">
      <button :disabled="page === 0" @click="goPage(page - 1)">上一页</button>
      <span>第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
      <div class="jump-to">
        <span>跳到</span>
        <input
          v-model="jumpToInput"
          type="number"
          min="1"
          :max="totalPages"
          placeholder="页码"
          @keyup.enter="jumpToPage"
        />
        <button class="jump-btn" @click="jumpToPage">Go</button>
      </div>
      <button :disabled="page >= totalPages - 1" @click="goPage(page + 1)">下一页</button>
    </footer>

    <AdminFeedbackDetailModal
      :show="showDetail"
      :detail="detail"
      @close="showDetail = false"
    />
  </div>
</template>

<style scoped>
.admin-feedback-view {
  max-width: 1200px;
  margin: 0 auto;
  padding: 2rem 1.5rem 4rem;
}

.page-header { margin-bottom: 1.5rem; }
.page-header .title-row { display: flex; align-items: center; gap: 12px; margin-bottom: 0.4rem; }
.page-header .title-row h1 { margin: 0; }
.page-header h1 { font-size: 1.5rem; font-weight: 700; color: #0f172a; margin: 0 0 0.4rem; }

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  background: #ffffff;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 0.82rem;
  color: #475569;
  cursor: pointer;
  font-family: inherit;
}
.back-btn:hover { background: #F8FAFC; border-color: #CBD5E1; }
.subtitle { color: #64748B; font-size: 0.9rem; margin: 0; }

.summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 1.5rem;
}
.stat {
  background: #ffffff;
  border: 1px solid #E2E8F0;
  border-radius: 12px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-num { font-size: 1.4rem; font-weight: 700; color: #0f172a; }
.stat-label { font-size: 0.75rem; color: #64748B; }

.filters {
  background: #ffffff;
  border: 1px solid #E2E8F0;
  border-radius: 12px;
  padding: 1rem;
  margin-bottom: 1rem;
}
.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: flex-end;
}
.filter { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 140px; }
.filter span { font-size: 0.7rem; color: #64748B; font-weight: 500; }
.filter input, .filter select {
  padding: 6px 10px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 0.85rem;
  background: #F8FAFC;
  font-family: inherit;
  color: #0f172a;
}
.filter input:focus, .filter select:focus {
  outline: none;
  border-color: var(--color-gold);
  background: #ffffff;
}
.reset-btn {
  height: 32px;
  padding: 0 14px;
  background: #F1F5F9;
  border: 1px solid #E2E8F0;
  color: #475569;
  border-radius: 8px;
  font-size: 0.85rem;
  cursor: pointer;
}
.reset-btn:hover { background: #E2E8F0; }

.error-banner {
  background: #FEF2F2;
  border: 1px solid #FECACA;
  color: #B91C1C;
  padding: 10px 14px;
  border-radius: 10px;
  margin-bottom: 1rem;
  font-size: 0.85rem;
}

.table-wrap {
  background: #ffffff;
  border: 1px solid #E2E8F0;
  border-radius: 12px;
  overflow: hidden;
}

table { width: 100%; border-collapse: collapse; table-layout: fixed; }
th, td {
  padding: 14px 12px;
  text-align: left;
  font-size: 0.85rem;
  color: #1E293B;
  vertical-align: middle;
  white-space: nowrap;
}
th {
  background: #F8FAFC;
  font-weight: 600;
  color: #64748B;
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  white-space: nowrap;
}
th:nth-child(1) { width: 100px; }  /* 提问用户 ID */
th:nth-child(2) { width: 18%; }    /* 用户提问 */
th:nth-child(3) { /* AI 回复 - 弹性 */
}
th:nth-child(4) { width: 80px; }   /* 输出 */
th:nth-child(5) { width: 80px; }   /* 汇总 */
th:nth-child(6) { width: 160px; }  /* 角色/房间 */
th:nth-child(7) { width: 160px; }  /* 最后反馈 */
th:nth-child(8) { width: 100px; }  /* 响应延迟 */
tbody tr { border-top: 1px solid #F1F5F9; cursor: pointer; }
tbody tr:hover { background: #FAFAF7; }

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 600;
}
.status-pill.unrated { background: #F1F5F9; color: #64748B; }
.status-pill.aggregated { background: #EEF2FF; color: #4F46E5; }
.status-pill.rated { background: #FEF3C7; color: #B45309; }
.status-pill.ok { background: rgba(16, 185, 129, 0.1); color: #10B981; }
.status-pill.empty { background: rgba(245, 158, 11, 0.1); color: #D97706; }
.status-pill.failed { background: rgba(239, 68, 68, 0.1); color: #EF4444; }

.latency-cell { white-space: nowrap; }
.latency {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 6px;
  font-family: monospace;
  font-size: 0.78rem;
  font-weight: 600;
}
.latency-fast { background: rgba(16, 185, 129, 0.12); color: #059669; }
.latency-ok   { background: rgba(245, 158, 11, 0.12); color: #B45309; }
.latency-slow { background: rgba(234, 88, 12, 0.14);  color: #C2410C; }
.latency-bad  { background: rgba(239, 68, 68, 0.12);  color: #B91C1C; }

.rollup {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  font-size: 0.95rem;
  color: #475569;
}
.rollup .thumbs-up { color: #10B981; }
.rollup .thumbs-down { color: #EF4444; }
.rollup .count { font-weight: 600; }
.rollup .count-up { color: #10B981; }
.rollup .count-down { color: #EF4444; }

.preview-cell { color: #475569; }
.preview-text {
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  white-space: normal;
  line-height: 1.4;
  word-break: break-word;
}
.prompt-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #475569;
}
.prompt-text {
  background: #EEF2FF;
  color: #3730A3;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 0.78rem;
}
.muted { color: #94A3B8; font-size: 0.78rem; }

.user-cell { min-width: 90px; }
.user-id {
  font-family: monospace;
  font-size: 0.75rem;
  color: #1E293B;
  white-space: nowrap;
}
.muted { color: #94A3B8; font-size: 0.78rem; }
.ctx-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.8rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ctx-cell .char-name { font-weight: 500; color: #1E293B; }
.ctx-cell .dot { color: #CBD5E1; }
.ctx-cell .room-name { color: #64748B; }
.time-cell { font-family: monospace; font-size: 0.75rem; color: #64748B; white-space: nowrap; }

.empty { padding: 3rem 1rem; text-align: center; color: #94A3B8; font-size: 0.9rem; }

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 1rem;
  margin-top: 1.5rem;
  color: #64748B;
  font-size: 0.85rem;
}
.pagination button {
  padding: 6px 14px;
  background: #ffffff;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  cursor: pointer;
  color: #475569;
}
.pagination button:hover:not(:disabled) { background: #F8FAFC; }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }

.jump-to {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #64748B;
  font-size: 0.85rem;
}
.jump-to input {
  width: 60px;
  padding: 5px 8px;
  border: 1px solid #E2E8F0;
  border-radius: 6px;
  font-size: 0.85rem;
  background: #ffffff;
  color: #0f172a;
  text-align: center;
  font-family: monospace;
}
.jump-to input:focus {
  outline: none;
  border-color: var(--color-gold);
}
.jump-btn {
  padding: 5px 10px;
  font-size: 0.8rem;
}

@media (prefers-color-scheme: dark) {
  .page-header h1 { color: #e8e8f0; }
  .subtitle { color: #94a0b0; }
  .stat, .filters, .table-wrap { background: #1f1f28; border-color: #2a2a35; }
  .stat-num, .preview-cell, .ctx-cell { color: #e8e8f0; }
  th { background: #25252f; color: #94a0b0; }
  .filter input, .filter select { background: #25252f; border-color: #2a2a35; color: #e8e8f0; }
  tbody tr { border-color: #2a2a35; }
  tbody tr:hover { background: #25252f; }
  .user-id { color: #e8e8f0; }
  .latency-fast { color: #34D399; }
  .latency-ok   { color: #FBBF24; }
  .latency-slow { color: #FB923C; }
  .latency-bad  { color: #F87171; }
  .back-btn { background: #25252f; border-color: #2a2a35; color: #e8e8f0; }
  .back-btn:hover { background: #2f2f3a; border-color: #3a3a48; }
}
</style>
