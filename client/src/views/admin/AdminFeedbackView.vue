<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { ThumbsUp, ThumbsDown } from 'lucide-vue-next'
import { messageFeedbackApi } from '@/api/messageFeedback'
import type {
  AdminFeedbackListItem,
  AdminFeedbackDetail,
  AdminListParams
} from '@/api/messageFeedback'
import AdminFeedbackDetailModal from '@/components/admin/AdminFeedbackDetailModal.vue'

const CATEGORY_OPTIONS = [
  { value: '', label: '全部原因' },
  { value: 'IRRELEVANT', label: '答非所问' },
  { value: 'INACCURATE', label: '事实不准' },
  { value: 'UNSAFE', label: '不安全/不当' },
  { value: 'STYLE_BAD', label: '风格差' },
  { value: 'OTHER', label: '其他' }
]

const CATEGORY_LABELS: Record<string, string> = Object.fromEntries(
  CATEGORY_OPTIONS.filter(o => o.value).map(o => [o.value, o.label])
)

const TYPE_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: 'LIKE', label: '点赞' },
  { value: 'DISLIKE', label: '点踩' }
]

const items = ref<AdminFeedbackListItem[]>([])
const total = ref(0)
const page = ref(0)
const size = 20
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const filterType = ref<string>('')
const filterCategory = ref<string>('')
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
    const params: AdminListParams = {
      page: page.value,
      size,
      type: filterType.value ? (filterType.value as 'LIKE' | 'DISLIKE') : undefined,
      category: filterCategory.value || undefined,
      userId: filterUserId.value.trim() || undefined,
      from: filterFrom.value || undefined,
      to: filterTo.value || undefined
    }
    const data = await messageFeedbackApi.adminList(params)
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
  filterType.value = ''
  filterCategory.value = ''
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

async function openDetail(item: AdminFeedbackListItem) {
  try {
    detail.value = await messageFeedbackApi.adminGet(item.id)
    showDetail.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载详情失败'
  }
}

function formatTime(iso: string): string {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div class="admin-feedback-view">
    <header class="page-header">
      <h1>反馈管理</h1>
      <p class="subtitle">查看用户对 AI 回复的反馈，用于发现答得不好的具体消息</p>
    </header>

    <section class="filters">
      <div class="filter-row">
        <label class="filter">
          <span>类型</span>
          <select v-model="filterType" @change="applyFilters">
            <option v-for="o in TYPE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </label>
        <label class="filter">
          <span>原因</span>
          <select v-model="filterCategory" @change="applyFilters">
            <option v-for="o in CATEGORY_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </label>
        <label class="filter">
          <span>用户 ID / 关键词</span>
          <input v-model="filterUserId" type="text" placeholder="模糊匹配" @keyup.enter="applyFilters" />
        </label>
        <label class="filter">
          <span>起始时间</span>
          <input v-model="filterFrom" type="datetime-local" @change="applyFilters" />
        </label>
        <label class="filter">
          <span>结束时间</span>
          <input v-model="filterTo" type="datetime-local" @change="applyFilters" />
        </label>
        <button class="reset-btn" @click="resetFilters">重置</button>
      </div>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section class="table-wrap">
      <table v-if="!loading && items.length > 0">
        <thead>
          <tr>
            <th>类型</th>
            <th>原因</th>
            <th>用户</th>
            <th>消息预览</th>
            <th>备注</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id" @click="openDetail(item)">
            <td>
              <span class="type-badge" :class="item.type.toLowerCase()">
                <ThumbsUp v-if="item.type === 'LIKE'" :size="12" />
                <ThumbsDown v-else :size="12" />
              </span>
            </td>
            <td>{{ item.category ? CATEGORY_LABELS[item.category] || item.category : '—' }}</td>
            <td>
              <div class="user-cell">
                <span class="display-name">{{ item.displayName }}</span>
                <span class="username">@{{ item.username }}</span>
              </div>
            </td>
            <td class="preview-cell">{{ item.messagePreview }}</td>
            <td class="comment-cell">{{ item.comment || '—' }}</td>
            <td class="time-cell">{{ formatTime(item.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else-if="loading" class="empty">加载中...</div>
      <div v-else class="empty">没有匹配的反馈</div>
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

.type-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
}
.type-badge.like { background: rgba(16, 185, 129, 0.12); color: #10B981; }
.type-badge.dislike { background: rgba(239, 68, 68, 0.12); color: #EF4444; }

.user-cell { display: flex; flex-direction: column; gap: 2px; }
.display-name { font-weight: 500; }
.username { font-size: 0.7rem; color: #94A3B8; }

.preview-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #475569; }
.comment-cell { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #475569; }
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
  .filters, .table-wrap { background: #1f1f28; border-color: #2a2a35; }
  th { background: #25252f; color: #94a0b0; }
  .filter input, .filter select { background: #25252f; border-color: #2a2a35; color: #e8e8f0; }
  tbody tr { border-color: #2a2a35; }
  tbody tr:hover { background: #25252f; }
}
</style>
