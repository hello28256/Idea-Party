<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ThumbsUp, ThumbsDown, MessageSquare, Copy } from 'lucide-vue-next'
import { api } from '@/api/auth'
import type { AdminFeedbackDetail, AdminListParams } from '@/api/messageFeedback'
import AdminFeedbackDetailModal from '@/components/admin/AdminFeedbackDetailModal.vue'

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

interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

const STATUS_OPTIONS = [
  { value: '', label: '全部消息' },
  { value: 'UNRATED', label: '未反馈' },
  { value: 'AGGREGATED', label: '已反馈（汇总）' },
  { value: 'RATED', label: '当前用户已评' }
]

const items = ref<AdminMessageObservation[]>([])
const total = ref(0)
const page = ref(0)
const size = 20
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const filterStatus = ref<string>('')
const filterUserId = ref<string>('')
const filterFrom = ref<string>('')
const filterTo = ref<string>('')

const loading = ref(false)
const error = ref<string | null>(null)
const showDetail = ref(false)
const detail = ref<AdminFeedbackDetail | null>(null)

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
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div class="admin-feedback-view">
    <header class="page-header">
      <h1>消息总览</h1>
      <p class="subtitle">所有 AI 回复的反馈情况：已反馈的看用户评价，未反馈的主动跟进</p>
    </header>

    <section class="summary">
      <div class="stat">
        <span class="stat-num">{{ total }}</span>
        <span class="stat-label">总消息数</span>
      </div>
      <div class="stat" v-for="opt in STATUS_OPTIONS.filter(o => o.value)" :key="opt.value">
        <span class="stat-num">
          {{ items.filter(i => i.status === opt.value).length }}
        </span>
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
            <th>状态</th>
            <th>汇总</th>
            <th>消息预览</th>
            <th>角色 / 房间</th>
            <th>最后反馈</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.messageId" @click="openDetail(item)">
            <td>
              <span class="status-pill" :class="item.status.toLowerCase()">
                <MessageSquare v-if="item.status === 'UNRATED'" :size="12" />
                <ThumbsUp v-else-if="item.feedbackType === 'LIKE'" :size="12" />
                <ThumbsDown v-else-if="item.feedbackType === 'DISLIKE'" :size="12" />
                <Copy v-else :size="12" />
                {{
                  item.status === 'UNRATED' ? '未反馈'
                  : item.status === 'AGGREGATED' ? '已反馈'
                  : '当前用户已评'
                }}
              </span>
            </td>
            <td class="rollup">
              <ThumbsUp :size="12" /> {{ item.likeCount }}
              <ThumbsDown :size="12" /> {{ item.dislikeCount }}
              <span class="feedback-total">{{ item.feedbackCount }} 评</span>
            </td>
            <td class="preview-cell">{{ item.messagePreview || '—' }}</td>
            <td>
              <div class="ctx-cell">
                <span v-if="item.characterName">{{ item.characterName }}</span>
                <span v-if="item.roomName" class="room-name">· {{ item.roomName }}</span>
              </div>
            </td>
            <td class="time-cell">{{ formatTime(item.lastFeedbackAt || item.messageCreatedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="loading" class="empty">加载中...</div>
      <div v-else class="empty">没有匹配的消息</div>
    </section>

    <footer v-if="totalPages > 1" class="pagination">
      <button :disabled="page === 0" @click="goPage(page - 1)">上一页</button>
      <span>第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
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
.page-header h1 { font-size: 1.5rem; font-weight: 700; color: #0f172a; margin: 0 0 0.4rem; }
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

table { width: 100%; border-collapse: collapse; }
th, td { padding: 12px 14px; text-align: left; font-size: 0.85rem; color: #1E293B; }
th { background: #F8FAFC; font-weight: 600; color: #64748B; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.05em; }
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

.rollup {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
  font-size: 0.8rem;
  color: #475569;
}
.feedback-total {
  margin-left: 4px;
  color: #94A3B8;
  font-size: 0.72rem;
}

.preview-cell { max-width: 380px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #475569; }
.ctx-cell { display: flex; flex-direction: column; gap: 2px; }
.ctx-cell .room-name { font-size: 0.72rem; color: #94A3B8; }
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

@media (prefers-color-scheme: dark) {
  .page-header h1 { color: #e8e8f0; }
  .subtitle { color: #94a0b0; }
  .stat, .filters, .table-wrap { background: #1f1f28; border-color: #2a2a35; }
  .stat-num, .preview-cell, .ctx-cell { color: #e8e8f0; }
  th { background: #25252f; color: #94a0b0; }
  .filter input, .filter select { background: #25252f; border-color: #2a2a35; color: #e8e8f0; }
  tbody tr { border-color: #2a2a35; }
  tbody tr:hover { background: #25252f; }
}
</style>
