<script setup lang="ts">
// RoomListView：路由 /rooms（默认 ?tab=discover） 以及 /rooms?tab=my-rooms&roomId=xxx 等
// RoomListView 是应用的核心"中枢"页面：
// 通过 URL query (?tab=...&roomId=...) 同时承载「发现/角色库/场景/我的聊天」多个视图，
// 并在 my-rooms tab 下用三栏布局（房间列表 + 聊天面板 + 角色面板）替代独立 /chat 路由。
// 关键依赖：
//   - roomStore / characterStore / scenarioStore / authStore：领域数据源
//   - charactersApi / scenariosApi：纯 HTTP 通道（场景 prompt 生成、简历解析、JD OCR）
//   - ChatRoomPanel：在 my-rooms tab 中以 embedded 模式渲染（替代 /chat/:roomId 路由）
//   - CreateRoomModal / CreateCharacterModal / ConfirmDialog：复用弹窗
//   - CustomScenarioModal：用户私有场景创建/编辑弹窗（场景 tab 末尾的 + 卡片触发）
//   - useToast：统一提示（删除成功/失败等）
import { ref, reactive, onMounted, onUnmounted, computed, watch } from 'vue'
import { charactersApi } from '@/api/characters'
import { hotRoomsApi, type HotRoom } from '@/api/hotRooms'
import { scenariosApi } from '@/api/scenarios'
import { Compass, UsersRound, Sparkles, MessageSquare } from 'lucide-vue-next'
import { useRouter, useRoute } from 'vue-router'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import CreateRoomModal from '@/components/room/CreateRoomModal.vue'
import { BRAND_LOGO } from '@/constants/brand'
import CreateCharacterModal from '@/components/character/CreateCharacterModal.vue'
import UserDropdown from '@/components/ui/UserDropdown.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import CustomScenarioModal from '@/components/scenario/CustomScenarioModal.vue'
import { useToast } from '@/composables/useToast'
import AppSidebar from '@/components/ui/AppSidebar.vue'
import { MINIMAL_NAV_ITEMS } from '@/config/sidebar'
import { useScenarioStore, type Scenario } from '@/stores/scenario'
import ChatRoomPanel from '@/components/chat/ChatRoomPanel.vue'

const router = useRouter()
const route = useRoute()
const roomStore = useRoomStore()
const toast = useToast()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

// 侧边栏 nav item.icon (PascalCase 字符串) -> lucide 组件。
// 用静态映射而非 resolveComponent：保留 Vite tree-shake，避免把整个 lucide 包打进去。
const NAV_ICONS: Record<string, unknown> = {
  Compass,
  UsersRound,
  Sparkles,
  MessageSquare
}
function navIcon(name?: string) {
  return name ? NAV_ICONS[name] : null
}

const showCreateModal = ref(false)
const showCreateCharacterModal = ref(false)
const showAddExistingCharacterModal = ref(false)
const showCreateDropdown = ref(false)
const showEditCharacterModal = ref(false)
const editingCharacter = ref<any>(null)

// 在聊天室内创建角色的上下文状态
const createCharacterRoomId = ref<string | null>(null)
const dropdownRef = ref<HTMLElement | null>(null)
// Teleport 到 body 后的下拉菜单位置（fixed 定位）。
// 注意：必须在每次展开、resize、滚动、sidebar 折叠态切换时基于按钮 getBoundingClientRect
// 重算——如果只在 toggle 时缓存，按钮随后移动（侧边栏展开/收起、滚动等）菜单就会留在
// 旧坐标，看起来像"飘到了头像下面"。
const dropdownPos = ref({ top: 0, left: 0 })
const selectedCategory = ref('all')
const searchQuery = ref('')
// mounted 在 onMounted 延迟 50ms 后置 true，触发 .page-layout 渐显动画（避免首帧空白闪烁）
const mounted = ref(false)

// Selected room for chat panel (my-rooms tab two-column layout)
const selectedRoomId = ref<string | null>(null)

// 标记下一次 tab 切换到 my-rooms 时跳过 fetchMyRooms（已被 startChat 等调用方预热过）
// 用一次性标志而不是全局禁 watch，避免正常浏览时也跳过刷新。
const skipNextMyRoomsFetch = ref(false)

// Three-way collapsible layout state
// localStorage key：保存三栏折叠状态，让刷新后仍能恢复用户偏好的布局
const SIDEBAR_STATE_KEY = 'idea-party-chat-layout-state'
const isGlobalSidebarCollapsed = ref(false)
// 默认折叠中间和右侧栏：进入 my-rooms 时优先展示列表，选中房间后才展开聊天面板
const isRoomListCollapsed = ref(true)
const isRolePanelCollapsed = ref(true)

// Members panel state
const showMembersTab = ref(false)
const showInviteModal = ref(false)
const inviteKeyword = ref('')
const inviteLoading = ref(false)
const inviteError = ref<string | null>(null)

// Per-row more menu state for the My-Rooms list
const openMenuRoomId = ref<string | null>(null)
const moreBtnRefs = ref<Record<string, HTMLElement | null>>({})
// 删除房间确认弹窗
const showDeleteRoomConfirm = ref(false)
const deletingRoomId = ref<string | null>(null)
const deletingRoomName = ref('')
const deletingRoomLoading = ref(false)
const menuRefs = ref<Record<string, HTMLElement | null>>({})

// startChat 跨并发的"正在 clone 的角色 name"锁。
// 后端去重只在"事务内"有效——若用户点 N 次同一推荐，前端会同时发 N 个 create 请求，
// 每个请求都 SELECT 查重（都查不到，因首次还没 INSERT）→ 全部走 INSERT → 重复入库。
// 前端用这层锁把"同名 + in-flight"的状态截下来：第二次点击直接忽略，避免并发穿透。
const cloningCharacterNames = ref<Set<string>>(new Set())

function toggleRoomMenu(roomId: string, event: MouseEvent) {
  event.stopPropagation()
  openMenuRoomId.value = openMenuRoomId.value === roomId ? null : roomId
}

function closeRoomMenu() {
  openMenuRoomId.value = null
}

function handleDeleteRoom(roomId: string, event: MouseEvent) {
  event.stopPropagation()
  closeRoomMenu()
  const room = roomStore.myRooms.find((r: any) => r.id === roomId)
  deletingRoomId.value = roomId
  deletingRoomName.value = room?.name || '该聊天室'
  showDeleteRoomConfirm.value = true
}

// 确认删除：调用 store 真正删；如果删的恰好是当前选中的房间，把 URL 上的 roomId 清掉并停留在 my-rooms，
// 避免右侧聊天面板继续渲染一个已不存在的房间。
async function confirmDeleteRoom() {
  const roomId = deletingRoomId.value
  const name = deletingRoomName.value
  if (!roomId) return
  try {
    deletingRoomLoading.value = true
    // roomStore.deleteRoom 失败时 throw，成功时无返回值
    await roomStore.deleteRoom(roomId)
    toast.success(`已删除聊天室「${name}」`)
    showDeleteRoomConfirm.value = false
    if (selectedRoomId.value === roomId) {
      selectedRoomId.value = null
      router.replace({
        path: '/rooms',
        query: { ...route.query, tab: 'my-rooms', roomId: undefined }
      })
    }
  } catch (e: any) {
    console.error('[DEBUG] Failed to delete room:', e)
    const msg = e?.response?.data?.message || e?.message || '删除聊天室失败，请重试'
    toast.error(msg)
    showDeleteRoomConfirm.value = false
  } finally {
    deletingRoomLoading.value = false
    deletingRoomId.value = null
  }
}

// 全局 document click 监听：当点击落在「三点按钮 + 弹出菜单」之外时关闭该行的 more menu。
function onRoomMenuOutsideClick(e: MouseEvent) {
  if (!openMenuRoomId.value) return
  const t = e.target as Node
  const inBtn = moreBtnRefs.value[openMenuRoomId.value]?.contains(t)
  const inMenu = menuRefs.value[openMenuRoomId.value]?.contains(t)
  if (!inBtn && !inMenu) closeRoomMenu()
}

// 场景卡片封面渐变色:用 id 哈希挑 1 个调色板,保证 22 个预设场景视觉差异明显
// 没用 1 张 cover 图,避免新增静态资源;用线性渐变 + emoji 居中已经能撑起 16:9 封面的视觉重量。
// 私有场景(id 是 UUID 字符串)也走同一函数,哈希结果均匀分布,不会和预设场景撞色。
const SCENARIO_COVER_PALETTES: [string, string][] = [
  ['#fef3c7', '#f59e0b'], // 暖黄
  ['#dbeafe', '#3b82f6'], // 天空蓝
  ['#ede9fe', '#8b5cf6'], // 紫罗兰
  ['#fce7f3', '#ec4899'], // 粉
  ['#d1fae5', '#10b981'], // 薄荷
  ['#ffe4e6', '#f43f5e'], // 玫红
  ['#e0e7ff', '#6366f1'], // 靛
  ['#fef9c3', '#eab308'], // 柠檬
  ['#ccfbf1', '#14b8a6'], // 青
  ['#fee2e2', '#ef4444'], // 番茄
  ['#f3e8ff', '#a855f7'], // 薰衣草
  ['#cffafe', '#06b6d4']  // 湖蓝
]
function scenarioCover(id: string): string {
  // 简单 djb2 字符串哈希:稳态分布在 0..palette.length-1
  let h = 5381
  for (let i = 0; i < id.length; i++) {
    h = ((h << 5) + h + id.charCodeAt(i)) >>> 0
  }
  const [c1, c2] = SCENARIO_COVER_PALETTES[h % SCENARIO_COVER_PALETTES.length]
  return `linear-gradient(135deg, ${c1} 0%, ${c2} 100%)`
}

// 从 localStorage 加载折叠状态
// 解析失败时静默回退到默认值：避免一次坏数据导致整个页面布局异常。
function loadLayoutState() {
  try {
    const saved = localStorage.getItem(SIDEBAR_STATE_KEY)
    if (saved) {
      const state = JSON.parse(saved)
      isGlobalSidebarCollapsed.value = !!state.isGlobalSidebarCollapsed
      isRoomListCollapsed.value = state.isRoomListCollapsed ?? true
      isRolePanelCollapsed.value = state.isRolePanelCollapsed ?? true
    }
  } catch (e) {
    console.error('[DEBUG] Failed to load layout state:', e)
  }
}

// 将折叠状态保存到 localStorage
// 写入失败（隐私模式 / 配额耗尽）只 log，不打扰用户：布局状态本身是体验优化，非核心。
function saveLayoutState() {
  try {
    localStorage.setItem(
      SIDEBAR_STATE_KEY,
      JSON.stringify({
        isGlobalSidebarCollapsed: isGlobalSidebarCollapsed.value,
        isRoomListCollapsed: isRoomListCollapsed.value,
        isRolePanelCollapsed: isRolePanelCollapsed.value
      })
    )
  } catch (e) {
    console.error('[DEBUG] Failed to save layout state:', e)
  }
}

// 监听折叠状态变化
watch(
  [isGlobalSidebarCollapsed, isRoomListCollapsed, isRolePanelCollapsed],
  () => {
    saveLayoutState()
  }
)

// Current room characters - read from currentRoom which is updated by addCharacterToRoom
// 优先读 currentRoom（由 addCharacterToRoom 等写操作更新），回退到 myRooms 列表：
// 这样切换已加载的房间无需再发请求。
const currentRoomCharacters = computed(() => {
  if (!selectedRoomId.value) return []
  if (roomStore.currentRoom?.id === selectedRoomId.value) {
    return roomStore.currentRoom.characters || []
  }
  const room = roomStore.myRooms.find(r => r.id === selectedRoomId.value)
  return room?.characters || []
})

// Current room conversation mode (single 1-on-1 vs group multi-character).
// Driven by the backend room.mode field; legacy rooms default to 'group' on the server.
const currentRoomMode = computed<'single' | 'group'>(() => {
  if (!selectedRoomId.value) return 'group'
  if (roomStore.currentRoom?.id === selectedRoomId.value) {
    return (roomStore.currentRoom as any).mode || 'group'
  }
  const room = roomStore.myRooms.find(r => r.id === selectedRoomId.value)
  return ((room as any)?.mode as 'single' | 'group') || 'group'
})

// Characters available to add to the current room (all known - already-in-room)
const availableCharactersForRoom = computed(() => {
  const inRoom = new Set(currentRoomCharacters.value.map((c: any) => c.id))
  return characterStore.characters.filter((c: any) => !inRoom.has(c.id))
})

// 当前聊天室的对话模式
// 'dialogue' = 多角色并行响应；'discussion' = 轮流讨论。由后端决定编排逻辑，前端只展示与切换。
const currentChatMode = computed(() => {
  if (!selectedRoomId.value) return 'dialogue'
  if (roomStore.currentRoom?.id === selectedRoomId.value) {
    return roomStore.currentRoom.chatMode || 'dialogue'
  }
  const room = roomStore.myRooms.find(r => r.id === selectedRoomId.value)
  return room?.chatMode || 'dialogue'
})

// 在对话模式与讨论模式之间切换
// 同模式重复点击直接返回，避免无意义的 PUT 请求。
// 失败时仍用 alert（暂未迁移到 toast）：遗留行为，后续可统一改为 toast。
async function switchMode(mode: 'dialogue' | 'discussion') {
  console.log('[DEBUG] switchMode called with mode:', mode)
  console.log('[DEBUG] selectedRoomId:', selectedRoomId.value)
  console.log('[DEBUG] currentChatMode:', currentChatMode.value)

  if (!selectedRoomId.value) {
    console.log('[DEBUG] No room selected, returning')
    return
  }
  if (mode === currentChatMode.value) {
    console.log('[DEBUG] Mode already set to:', mode, ', skipping')
    return
  }

  try {
    console.log('[DEBUG] Calling roomStore.updateRoomMode with mode:', mode)
    const result = await roomStore.updateRoomMode(selectedRoomId.value, { chatMode: mode })
    console.log('[DEBUG] updateRoomMode succeeded, result:', result)
  } catch (e) {
    console.error('[DEBUG] Failed to switch mode:', e)
    // 显示错误给用户
    const errorMsg = e instanceof Error ? e.message : '切换模式失败，请重试'
    console.error('[DEBUG] Error details:', errorMsg)
    alert('切换模式失败: ' + errorMsg)
  }
}

// 将 selectedRoomId 与 URL query 保持同步
// URL 上的 'null'/'undefined' 视为缺失：兼容 router.replace 时显式传 undefined 被序列化成字符串的情况。
watch(
  () => route.query.roomId as string | undefined,
  (roomId) => {
    if (roomId && roomId !== 'null' && roomId !== 'undefined' && roomId.trim() !== '') {
      selectedRoomId.value = roomId
    } else {
      selectedRoomId.value = null
    }
  },
  { immediate: true }
)

// Auto-expand room list when entering my-rooms tab without a selected room
watch(
  [() => route.query.tab, () => route.query.roomId],
  ([tab, roomId]) => {
    if ((tab === 'my-rooms' || tab === 'recent') && !roomId) {
      isRoomListCollapsed.value = false
      // roomId 变为空：通常是删除/离开房间后回到列表，重新拉取以反映最新状态
      roomStore.fetchMyRooms()
    }
  },
  { immediate: true }
)

// 监听分类切换：selectedCategory 变化时重新拉取对应分类的推荐角色
// immediate=false：避免初始化时拉两遍（onMounted 也会拉）
watch(selectedCategory, (newCat) => {
  fetchFeaturedCharacters(newCat)
})

// 根据当前路由决定激活的导航项
// 优先级：query.tab > 路径前缀。'/chat/*' 仍归为 discover：因为新版三栏布局已替代独立聊天路由。
const activeNavId = computed(() => {
  const path = route.path
  const tab = route.query.tab as string
  if (tab === 'my-rooms') return 'my-rooms'
  if (tab === 'recent') return 'recent'
  if (tab === 'scenarios' || path === '/scenarios') return 'scenarios'
  if (path === '/rooms' || path === '/') return 'discover'
  if (path.startsWith('/characters')) return 'characters'
  if (path.startsWith('/chat')) return 'discover'
  return 'discover'
})

// 是否显示角色库内容
const isCharactersView = computed(() => {
  return route.path.startsWith('/characters') && !route.path.includes('/create')
})

// 是否显示「我的聊天」内容
const isMyRoomsView = computed(() => {
  return activeNavId.value === 'my-rooms' || activeNavId.value === 'recent'
})

// 是否显示场景卡片网格
const isScenariosView = computed(() => {
  return activeNavId.value === 'scenarios'
})

const scenarioStore = useScenarioStore()
const activeScenario = ref<Scenario | null>(null)
const userInput = ref('')
// 面试场景专用：岗位描述（JD）
const jobDescription = ref('')
// 弹窗步骤：'input' = 第一步填描述, 'preview' = 第二步预览动态生成的 prompt
const scenarioStep = ref<'input' | 'preview'>('input')
const creatingScenario = ref(false)
const createError = ref<string | null>(null)
// 静态场景（dynamicPrompt=false）的 promptTemplate 用户编辑状态
// 始终是字符串；openScenario 时回填为 scenario.promptTemplate，用户可改
// finalizeScenario 通过对比当前值与原值决定是否跳过 LLM
const editablePromptTemplate = ref<string>('')
// ===== 用户私有场景弹窗状态 =====
// 控制 CustomScenarioModal 显示；editingScenario 决定是创建还是编辑
const showCustomScenarioModal = ref(false)
const editingScenario = ref<Scenario | null>(null)
// 删除确认（用户私有场景）
const deletingScenarioId = ref<string | null>(null)
const deletingScenarioTitle = ref('')
const deletingScenarioLoading = ref(false)
// 动态生成的 prompt + 角色名（面试场景第二步展示用）
const generatedPrompt = ref('')
const generatedCharacterName = ref('')
// 简历上传状态
const resumeFile = ref<File | null>(null)
const resumeFilename = ref('')
const resumeText = ref('')
const resumeTruncated = ref(false)
const resumeUploading = ref(false)
const resumeError = ref<string | null>(null)
const isDragging = ref(false)
// JD 截图 OCR 状态
const jdImageUploading = ref(false)
const jdImageError = ref<string | null>(null)

// 打开场景弹窗时全量重置状态：避免上一次场景的输入（岗位/JD/简历）泄漏到下一个。
function openScenario(s: Scenario) {
  activeScenario.value = s
  userInput.value = ''
  jobDescription.value = ''
  scenarioStep.value = 'input'
  generatedPrompt.value = ''
  generatedCharacterName.value = ''
  resumeFile.value = null
  resumeFilename.value = ''
  resumeText.value = ''
  resumeTruncated.value = false
  resumeError.value = null
  jdImageUploading.value = false
  jdImageError.value = null
  createError.value = null
  // 每次打开新场景时回填 promptTemplate 到编辑器（用户可改）
  editablePromptTemplate.value = s.promptTemplate || ''
}
function closeScenario() {
  activeScenario.value = null
  userInput.value = ''
  jobDescription.value = ''
  scenarioStep.value = 'input'
  generatedPrompt.value = ''
  generatedCharacterName.value = ''
  resumeFile.value = null
  resumeFilename.value = ''
  resumeText.value = ''
  resumeTruncated.value = false
  resumeError.value = null
  jdImageUploading.value = false
  jdImageError.value = null
  createError.value = null
}

// 「我的聊天」列表的本地搜索过滤：
// 命中规则：房间名 / 主题 / 任一角色名 任一包含关键字（不区分大小写）。
// store 的 sortedMyRooms 已经做了 LRU 排序，本 computed 只在它基础上做一次子串过滤，
// 不影响排序结果，也不改 store 源数据——纯派生，保留"清空关键字恢复原顺序"的行为。
const filteredMyRooms = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return roomStore.sortedMyRooms
  return roomStore.sortedMyRooms.filter((room: any) => {
    if (room.name?.toLowerCase().includes(q)) return true
    if (room.topic?.toLowerCase().includes(q)) return true
    const chars = room.characters || []
    return chars.some((c: any) => c.name?.toLowerCase().includes(q))
  })
})

// 单房间展示用：取该房间角色数组的前 9 个作为头像堆叠。
// 模板里直接调函数更省事但每次渲染都重算（room-list-item 数量多时浪费），
// 用参数化的 computed 不可行——这里用方法 + template 内 `:src` 缓存保证 Vue 仍按依赖追踪重渲染。
// 复杂度 O(N*9) 远小于一次网络往返。
function displayedRoomAvatars(room: any) {
  const chars = room?.characters
  if (!Array.isArray(chars) || chars.length === 0) return []
  return chars.slice(0, 9)
}

// ===== 用户私有场景 CRUD 入口 =====
// 打开「+」卡片：创建模式
function openCreateCustomScenario() {
  editingScenario.value = null
  showCustomScenarioModal.value = true
}

// 打开用户私有场景的「编辑」按钮：编辑模式（@click.stop 阻止冒泡到外层 card）
function openEditCustomScenario(s: Scenario, e: Event) {
  e.stopPropagation()
  editingScenario.value = s
  showCustomScenarioModal.value = true
}

// 打开用户私有场景的「删除」按钮：先弹 ConfirmDialog 二次确认（@click.stop 防冒泡）
function openDeleteCustomScenario(s: Scenario, e: Event) {
  e.stopPropagation()
  deletingScenarioId.value = s.id
  deletingScenarioTitle.value = s.title
}

// CustomScenarioModal saved 事件回调：弹窗内部已经 toast 成功/失败提示，
// 这里只需关闭弹窗（弹窗已自动 close）。保留 handler 是为了 future hook
// （如埋点、自动选中刚创建的场景等）。
function onCustomScenarioSaved(_scenario: Scenario) {
  // store 已经在 createUserScenario / updateUserScenario 内部更新 userScenarios
  // computed scenarios 会自动响应，无需手动刷新
  // 弹窗已自动关闭，无需手动处理 showCustomScenarioModal
}

// 确认删除：调 store action。失败由 store 内部捕获并写 error.value
async function confirmDeleteCustomScenario() {
  if (!deletingScenarioId.value) return
  deletingScenarioLoading.value = true
  try {
    const success = await scenarioStore.removeUserScenario(deletingScenarioId.value)
    if (success) {
      toast.success(`已删除场景「${deletingScenarioTitle.value}」`)
      deletingScenarioId.value = null
      deletingScenarioTitle.value = ''
    } else {
      const msg = scenarioStore.error || '删除失败'
      toast.error(msg)
      deletingScenarioId.value = null
    }
  } catch (e: any) {
    console.error('[DEBUG] confirmDeleteCustomScenario failed:', e)
    toast.error(e.message || '删除失败')
    deletingScenarioId.value = null
  } finally {
    deletingScenarioLoading.value = false
  }
}

// 简历上传：选择文件或拖拽
// 前端只做轻量预校验（大小/扩展名），真正的解析在后端完成；这样能减少无效网络请求并给出更准确的错误。
async function handleResumeFile(file: File) {
  if (!file) return
  // 前端预校验：大小 + 扩展名
  if (file.size > 5 * 1024 * 1024) {
    resumeError.value = '文件超过 5MB 上限'
    return
  }
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!['pdf', 'docx', 'doc', 'txt'].includes(ext)) {
    resumeError.value = '仅支持 PDF / Word / TXT 格式'
    return
  }
  resumeUploading.value = true
  resumeError.value = null
  try {
    const resp = await scenariosApi.parseResume(file)
    resumeFile.value = file
    resumeFilename.value = resp.data?.filename || file.name
    resumeText.value = resp.data?.text || ''
    resumeTruncated.value = !!resp.data?.truncated
  } catch (e) {
    console.error('[DEBUG] parseResume failed:', e)
    resumeError.value = e instanceof Error ? e.message : '简历解析失败'
    resumeFile.value = null
    resumeFilename.value = ''
    resumeText.value = ''
  } finally {
    resumeUploading.value = false
  }
}

function onResumeFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) handleResumeFile(file)
}

function onResumeDrop(e: DragEvent) {
  e.preventDefault()
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) handleResumeFile(file)
}

function onResumeDragOver(e: DragEvent) {
  e.preventDefault()
  isDragging.value = true
}

function onResumeDragLeave() {
  isDragging.value = false
}

function clearResume() {
  resumeFile.value = null
  resumeFilename.value = ''
  resumeText.value = ''
  resumeTruncated.value = false
  resumeError.value = null
}

// JD 截图 OCR：支持点击、拖拽、Ctrl+V 粘贴
// 追加而非覆盖：用户可能想叠加多张 JD 截图，自动换行拼接保留所有来源。
async function handleJdImageFile(file: File) {
  if (!file) return
  // 前端预校验
  if (file.size > 5 * 1024 * 1024) {
    jdImageError.value = '图片超过 5MB 上限'
    return
  }
  if (!file.type.startsWith('image/')) {
    jdImageError.value = '请提供图片文件'
    return
  }
  jdImageUploading.value = true
  jdImageError.value = null
  try {
    const resp = await scenariosApi.extractTextFromImage(file)
    const text = (resp.data?.text || '').trim()
    if (!text) {
      jdImageError.value = '图片中未识别到文字内容'
      return
    }
    // 把识别结果追加到现有 JD 文本（如果 textarea 已有内容就换行拼接）
    const existing = jobDescription.value.trim()
    jobDescription.value = existing ? `${existing}\n\n${text}` : text
  } catch (e) {
    console.error('[DEBUG] extractTextFromImage failed:', e)
    jdImageError.value = e instanceof Error ? e.message : 'OCR 识别失败'
  } finally {
    jdImageUploading.value = false
  }
}

function onJdImageFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) handleJdImageFile(file)
  target.value = '' // 重置，允许选同一文件
}

function onJdImageDrop(e: DragEvent) {
  e.preventDefault()
  const file = e.dataTransfer?.files?.[0]
  if (file) handleJdImageFile(file)
}

function onJdImageDragOver(e: DragEvent) {
  e.preventDefault()
}

function onJdImagePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        e.preventDefault()
        handleJdImageFile(file)
        return
      }
    }
  }
}

// 简单解析岗位字符串，提取行业和经验年限（"前端 / SaaS / 5年" -> 行业=SaaS, 年限=5）
// 启发式规则：纯数字+"年"视为年限；短词视为行业。不做强语义理解，交给后端 LLM 做更精准的拆解。
function parsePositionMeta(input: string): { industry: string; experienceYears: number | null } {
  const parts = input.split(/[／/、,，]/).map(s => s.trim()).filter(Boolean)
  let industry = ''
  let experienceYears: number | null = null
  for (const p of parts) {
    if (/^\d+\s*年(以上)?$/.test(p)) {
      experienceYears = parseInt(p, 10)
    } else if (!industry && p.length <= 10) {
      // 简单启发式：短词可能是行业
      industry = p
    }
  }
  return { industry, experienceYears }
}

// 场景弹窗「下一步」按钮的统一入口：
// - 动态生成场景（面试）：先调用后端 LLM 生成面试官 prompt，弹窗进入 preview 步骤让用户编辑。
// - 静态场景：跳过预览，直接调用 finalizeScenario 创建角色+房间。
async function nextStep() {
  if (!activeScenario.value) return
  const scenario = activeScenario.value
  const input = userInput.value.trim()

  if (scenario.requiresUserInput && !input) {
    createError.value = '请先填写岗位信息'
    return
  }

  // 走动态生成流程：面试场景
  if (scenario.dynamicPrompt) {
    creatingScenario.value = true
    createError.value = null
    try {
      const meta = parsePositionMeta(input)
      const resp = await scenariosApi.generateInterviewPrompt({
        position: input,
        industry: meta.industry || undefined,
        experienceYears: meta.experienceYears ?? undefined,
        jobDescription: jobDescription.value.trim() || undefined,
        resumeContent: resumeText.value.trim() || undefined
      })
      generatedCharacterName.value = resp.data?.characterName || ''
      generatedPrompt.value = resp.data?.prompt || ''
      if (!generatedCharacterName.value || !generatedPrompt.value) {
        throw new Error('生成结果为空，请重试')
      }
      scenarioStep.value = 'preview'
    } catch (e) {
      console.error('[DEBUG] generateInterviewPrompt failed:', e)
      createError.value = e instanceof Error ? e.message : '生成面试官 prompt 失败，请重试'
    } finally {
      creatingScenario.value = false
    }
    return
  }

  // 非动态生成的场景：直接进入创建流程
  await finalizeScenario()
}

// 真正落地创建角色+房间。两类入口：
// 1) dynamicPrompt 场景：复用 preview 步骤的 prompt 创建角色，避免重复调用 LLM。
// 2) 旧场景：调用通用 generatePrompt，按 scenario.id 映射固定的 characterName。
async function finalizeScenario() {
  if (!activeScenario.value) return
  const scenario = activeScenario.value
  const input = userInput.value.trim()

  creatingScenario.value = true
  createError.value = null
  try {
    // 动态生成场景：使用 preview 步骤里生成的 prompt
    if (scenario.dynamicPrompt) {
      const newCharacter = await charactersApi.create({
        name: generatedCharacterName.value,
        description: input,
        prompt: generatedPrompt.value
      })
      const charId = newCharacter.data?.id
      if (!charId) {
        createError.value = '创建角色失败：未拿到 ID'
        return
      }
      // topic 受 500 字符限制，完整 prompt 存 character.prompt 里
      const room = await roomStore.createRoom(
        scenario.title,
        scenario.title,
        [charId],
        scenario.mode
      )
      if (!room?.id) {
        createError.value = roomStore.error || '创建失败'
        return
      }
      router.push(`/rooms?tab=my-rooms&roomId=${room.id}`)
      return
    }

    // 兼容旧场景：走通用生成路径
    const description = scenario.requiresUserInput ? input : scenario.description
    // 角色名直接从 scenario 配置中读取（dynamicPrompt=true 场景无 characterName，由 finalizeScenario 上方分支处理）
    const characterName = scenario.characterName || scenario.title + ' 助手'

    // 决定 Character.prompt 走哪条路径：
    // 1) 用户在弹窗里编辑过 prompt（与原值不一致）→ 直接用用户版，跳过 LLM
    //    避免用户辛苦改的 prompt 被后端重新生成覆盖
    // 2) 用户没改过 → 走 LLM generatePrompt（让 AI 基于 description 合成更贴合的 prompt）
    let finalPrompt: string
    const originalTemplate = scenario.promptTemplate || ''
    if (editablePromptTemplate.value.trim() && editablePromptTemplate.value !== originalTemplate) {
      finalPrompt = editablePromptTemplate.value.trim()
    } else {
      const promptResp = await charactersApi.generatePrompt({
        name: characterName,
        description
      })
      finalPrompt = promptResp.data?.prompt || ''
    }

    const newCharacter = await charactersApi.create({
      name: characterName,
      description,
      prompt: finalPrompt
    })
    const charId = newCharacter.data?.id
    if (!charId) {
      createError.value = '创建角色失败：未拿到 ID'
      return
    }
    // topic 受 500 字符限制，完整 prompt 存 character.prompt
    const room = await roomStore.createRoom(
      scenario.title,
      scenario.title,
      [charId],
      scenario.mode
    )
    if (!room?.id) {
      createError.value = roomStore.error || '创建失败'
      return
    }
    router.push(`/rooms?tab=my-rooms&roomId=${room.id}`)
  } catch (e) {
    console.error('[DEBUG] startScenario failed:', e)
    createError.value = e instanceof Error ? e.message : '创建失败'
  } finally {
    creatingScenario.value = false
  }
}

// 监听 tab 变化以拉取我的聊天列表
watch(
  () => route.query.tab as string | undefined,
  (tab) => {
    if (tab === 'my-rooms' || tab === 'recent') {
      // startChat 等调用方已经在 router 切换前 await 过 fetchMyRooms，
      // 跳过这一次避免进入房间时多打一次 GET /rooms。
      if (skipNextMyRoomsFetch.value) {
        skipNextMyRoomsFetch.value = false
        return
      }
      roomStore.fetchMyRooms()
    } else if (tab === 'scenarios') {
      // 进入场景 tab 时拉取用户私有场景。
      // fetchUserScenarios 失败时不动 userScenarios.value，确保预设场景仍可见。
      scenarioStore.fetchUserScenarios()
    }
  },
  { immediate: true }
)

// Get current user's characters
// 基础过滤:当前用户私有角色（排除系统预设）。
// 叠加 searchQuery 时按 name / description 做大小写不敏感的子串匹配;
// 空串退化为完整列表，保持原有行为。
const characterSearchQuery = ref('')
const myCharacters = computed(() => {
  if (!authStore.user) return []
  const base = characterStore.characters.filter(
    c => c.ownerId === authStore.user!.id && !c.isPreset
  )
  const q = characterSearchQuery.value.trim().toLowerCase()
  if (!q) return base
  return base.filter(c =>
    c.name.toLowerCase().includes(q) ||
    (c.description || '').toLowerCase().includes(q)
  )
})

// 场景页搜索:与角色库 myCharacters 同款,按 title / description 子串匹配;
// 预制和用户自定义都参与过滤,避免搜不到自定义场景。
const scenarioSearchQuery = ref('')
const filteredScenarios = computed(() => {
  const q = scenarioSearchQuery.value.trim().toLowerCase()
  const base = scenarioStore.scenarios
  if (!q) return base
  return base.filter(s =>
    s.title.toLowerCase().includes(q) ||
    (s.description || '').toLowerCase().includes(q)
  )
})

// Format date
function formatDate(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const days = Math.floor(diff / (1000 * 60 * 60 * 24))

  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  if (days < 7) return `${days}天前`
  if (days < 30) return `${Math.floor(days / 7)}周前`
  return date.toLocaleDateString('zh-CN')
}

// 编辑角色弹窗相关函数
function openEditCharacterModal(character: any) {
  editingCharacter.value = { ...character }
  showEditCharacterModal.value = true
}

function closeEditCharacterModal() {
  showEditCharacterModal.value = false
  editingCharacter.value = null
}

function handleCharacterUpdated(updatedCharacter: any) {
  // 更新 store 中的角色信息
  const index = characterStore.characters.findIndex((c: any) => c.id === updatedCharacter.id)
  if (index !== -1) {
    characterStore.characters[index] = updatedCharacter
  }
  // 如果当前在聊天室内则刷新房间数据
  if (selectedRoomId.value) {
    roomStore.fetchRoomById(selectedRoomId.value)
  }
  closeEditCharacterModal()
}

// Start chat with a character — creates/joins a chat room (mode='group') seeded with this character.
// 与之前"单角色单聊"的差别：
//   1) 现在点推荐角色是创建一个群聊房间（mode='group'，包含 1 个初始角色），用户进入后可点"+ 邀请"继续扩充角色。
//   2) 如果点击的是 preset 角色（ownerId=null / isPreset=true），先在当前用户的角色库下 clone 一份副本
//      （同名同描述同 prompt），再以副本建群聊——这样角色就"加入了我的角色库"，可在「角色库」页管理。
//      已经 clone 过的（ownerId=currentUser）跳过 clone 直接复用。
// 幂等保证：每次先 fetchMyRooms 再查找已有房间，避免跨页面/跨设备创建重复会话。
// 防止重复点击的护栏：同一角色在请求进行中时直接忽略后续点击
const isStartingChat = ref(false)

async function startChat(character: any) {
  if (isStartingChat.value) {
    console.log('[DEBUG] startChat already in progress, ignoring click on:', character?.name)
    return
  }
  isStartingChat.value = true
  try {
    console.log('[DEBUG] startChat called with character:', character)

    // 增量拉取：仅在 store 还没数据时才拉，避免每次点推荐角色都重打两次 GET。
    // 这里保留"找到既有房间"的能力（依赖 myRooms），但跳过没必要的 characters 重拉——
    // preset 角色的 clone 走的是后端 owner+name 去重，前端缓存不命中也不会破坏正确性。
    if (roomStore.myRooms.length === 0) {
      await roomStore.fetchMyRooms()
    }
    // 标记接下来一次 my-rooms tab 切换由 router push 引起，watch 应跳过 fetchMyRooms
    skipNextMyRoomsFetch.value = true

    // 1) 决定用哪个 character.id 建房间
    //    - preset（ownerId=null）→ 先 clone 到当前用户的角色库，再用副本 id
    //    - 已属于当前用户 → 直接用
    //    - 属于别的用户 → 不能加入，跳过 clone 仅尝试复用已有房间（一般不会有）
    const myUserId = authStore.user?.id
    const isMine = character.ownerId && myUserId && character.ownerId === myUserId
    const isPreset = !character.ownerId

    let characterIdForRoom = character.id

    if (isPreset) {
      // 在我的角色库里查重名/同源角色（避免重复 clone）
      const dup = characterStore.characters.find(c =>
        c.ownerId === myUserId && c.name === character.name
      )
      if (dup) {
        characterIdForRoom = dup.id
        console.log('[DEBUG] Reusing already-cloned character:', dup.id)
        // 修复老库 clone 出来头像为空的问题：
        // 如果之前 clone 时没传 avatarUrl（因为 featuredCharacters 没保留原字段），
        // 这里就地补一次——用当前 preset 的 avatarUrl 调 update 写回 dup。
        // 防止：每次进「角色库」页都看到一堆首字母占位符。
        if (!dup.avatarUrl && character.avatarUrl) {
          console.log('[DEBUG] Patching missing avatarUrl for cloned character:', dup.name)
          await characterStore.updateCharacter(dup.id, {
            name: dup.name,
            description: dup.description || '',
            avatarUrl: character.avatarUrl,
            prompt: dup.prompt || character.prompt || ''
          })
        }
      } else {
        // 并发去重锁：若同名已经在 clone 中（典型场景：用户快速点击同一个推荐卡片），
        // 直接拦截。否则会发出 N 个并发 create 请求穿透后端查重：
        // 后端 create() 内的"先查重 → 联网 → AI → INSERT"有 10s+ 的窗口，
        // 第一个请求还没 INSERT 时，后续 N-1 个请求都查不到，都会走 INSERT → 重复入库。
        // 这层锁保证同一时刻同一个角色名只有 1 个请求在飞。
        if (cloningCharacterNames.value.has(character.name)) {
          console.log('[DEBUG] Already cloning character, skipping duplicate click:', character.name)
          return
        }
        cloningCharacterNames.value.add(character.name)
        try {
          const cloned = await characterStore.createCharacter({
            name: character.name,
            description: character.description || '',
            avatarUrl: character.avatarUrl || '',
            prompt: character.prompt || ''
          })
          if (!cloned) {
            // 加入角色库失败（典型原因：后端 AI prompt 生成慢/超时）。
            // 不弹 alert 打断用户——静默 fallback，用原始 preset id 继续建房间。
            // 用户先进入对话，"加入我的角色库"是辅助功能，失败不应阻塞主流程。
            // 后端 RoomService.create 不校验角色所有权（见 Service 代码注释），所以 preset id 也能建房间。
            console.warn('[DEBUG] clone failed for', character.name, '- falling back to preset id for room creation')
          } else {
            // 后端 CharacterService.create 会按 owner+name 去重：
            // 若服务端已经存在同名角色，会返回那条已存在记录的 id（不是新建的 id）。
            // 此时要把刚返回的"多余副本"从 store 移除，并使用真实已有那条的 id，
            // 避免下次再点击又落到新建分支、且 existingRoom 查询因 id 不匹配而漏掉既有房间。
            const serverId = cloned.id
            const localExisting = characterStore.characters.find(c =>
              c.ownerId === myUserId && c.name === character.name && c.id !== serverId
            )
            if (localExisting) {
              console.log('[DEBUG] Server dedup returned existing character, merging local store')
              characterIdForRoom = localExisting.id
              const staleIdx = characterStore.characters.findIndex(c => c.id === serverId)
              if (staleIdx !== -1) characterStore.characters.splice(staleIdx, 1)
            } else {
              characterIdForRoom = serverId
              console.log('[DEBUG] Cloned preset character to my library:', serverId)
            }
          }
        } finally {
          cloningCharacterNames.value.delete(character.name)
        }
      }
    } else if (!isMine) {
      // 属于别的用户：不能 clone 到我的库（避免越权），只能尝试复用既有房间
      console.log('[DEBUG] Character belongs to another user, cannot clone')
    }

    // 2) 检查该角色是否已经有群聊房间（用副本 id 找，因为房间 members 是按副本绑定的）
    // 匹配规则必须与后端 RoomService.create 的查重保持一致："角色集合完全相等"才算同一房间。
    // 旧实现用 .some() 会把"孔子+老子"命中为孔子的房间，造成跳错房间或误判已存在。
    const sortedRequested = [characterIdForRoom].slice().sort()
    const existingRoom = roomStore.myRooms.find(room => {
      const ids = (room.characters ?? []).map(c => c.id).sort()
      return ids.length === sortedRequested.length &&
        ids.every((id, i) => id === sortedRequested[i])
    })

    if (existingRoom) {
      router.replace({
        path: '/rooms',
        query: {
          ...route.query,
          tab: 'my-rooms',
          roomId: existingRoom.id
        }
      })
    } else {
      // 3) 创建一个群聊房间（mode='group'），副本角色作为初始成员
      const room = await roomStore.createRoom(
        character.name,
        undefined,
        [characterIdForRoom],
        'group'
      )
      router.replace({
        path: '/rooms',
        query: {
          ...route.query,
          tab: 'my-rooms',
          roomId: room.id
        }
      })
    }
  } catch (e) {
    console.error('[DEBUG] Failed to start chat:', e)
    alert('创建对话失败，请重试')
  } finally {
    isStartingChat.value = false
  }
}

// Navigation handler
function handleNavClick(itemId: string) {
  if (itemId === 'discover') {
    router.push('/rooms')
  } else if (itemId === 'characters') {
    router.push('/characters')
  } else if (itemId === 'scenarios') {
    router.push('/scenarios')
  } else if (itemId === 'trending') {
    router.push('/rooms?tab=trending')
  } else if (itemId === 'categories') {
    router.push('/rooms?tab=categories')
  } else if (itemId === 'my-rooms') {
    router.push('/rooms?tab=my-rooms')
  } else if (itemId === 'recent') {
    router.push('/rooms?tab=recent')
  }
}

// Categories for tabs
// 分类标签：id 跟后端 CharacterCategory 枚举名严格对齐（除 'all' 走"全部"分支）
// 后端接口 /recommended?category=SCIENTIST 会按枚举名过滤，乱写会导致过滤不生效
const categories = [
  { id: 'all', label: '全部', emoji: '✨' },
  { id: 'SCIENTIST', label: '科学家', emoji: '🔬', color: '#4F7DF3' },
  { id: 'STAR', label: '明星', emoji: '🌟', color: '#F472B6' },
  { id: 'ENTREPRENEUR', label: '企业家', emoji: '🚀', color: '#FB923C' },
  { id: 'PHILOSOPHER', label: '哲学家', emoji: '💭', color: '#8B5CF6' },
  { id: 'ATHLETE', label: '运动员', emoji: '🏆', color: '#10B981' },
  { id: 'WRITER', label: '作家', emoji: '📖', color: '#34D399' },
  { id: 'HISTORICAL', label: '历史人物', emoji: '🏛️', color: '#D4AF6A' },
  { id: 'ARTIST', label: '艺术家', emoji: '🖼️', color: '#A78BFA' },
  { id: 'FICTIONAL', label: '虚构角色', emoji: '🎭', color: '#EC4899' },
  { id: 'POLITICIAN', label: '政治家', emoji: '🏛️', color: '#B91C1C' },
  { id: 'MILITARY_LEADER', label: '军事家', emoji: '⚔️', color: '#475569' },
]

// Featured characters - loaded from API
const featuredCharacters = ref<any[]>([])
const featuredCharactersLoading = ref(false)

// 「热门聊天室」列表：来自后端 GET /api/rooms/hot（classpath:hotRooms.json）。
// 静态配置集中在后端 JSON 文件，前端只在 mount 时拉一次；JSON 是低频营销数据，
// 不走 DB 也避免改一张卡得改前端代码重新打包。
const hotRooms = ref<HotRoom[]>([])
const hotRoomsLoading = ref(false)
// 热门聊天室参与者头像查找表：name → avatarUrl。
// 后端 HotRoomResponse 没返回 participantAvatars 字段，前端用已加载的
// characterStore.presets 按名匹配补上真实头像，避免 DiceBear 假人脸。
// 用 featuredCharacters + presets 兜底：featured 是当前分类子集，presets 是全量。
const presetAvatarMap = computed(() => {
  const m = new Map<string, string>()
  for (const c of featuredCharacters.value) {
    if (c?.name && c.avatarUrl) m.set(c.name, c.avatarUrl)
  }
  for (const c of characterStore.presets) {
    if (c?.name && c.avatarUrl && !m.has(c.name)) m.set(c.name, c.avatarUrl)
  }
  return m
})
async function fetchHotRooms() {
  hotRoomsLoading.value = true
  try {
    hotRooms.value = await hotRoomsApi.list()
  } catch (e) {
    console.error('[DEBUG] Failed to fetch hot rooms:', e)
    hotRooms.value = []
  } finally {
    hotRoomsLoading.value = false
  }
}
// 推荐角色头像加载失败记录（按 char.id），避免个别维基 404 时整张卡片显示破图
const avatarLoadFailed = reactive<Record<string, boolean>>({})
// 「推荐角色」展示状态机：
// - BATCH_SIZE: 每批展示多少个。120 ÷ 18 ≈ 7 批，6 列 × 3 行的网格刚好放下。
// - showAllFeatured: false（分批态，默认）= 按当前批次展示 18 人 + 「换一批」按钮可点；
//                    true（显示全部态）= 一次性铺全部 120 人 + 「换一批」禁用，
//                    按钮文案切换为「收起」让用户能回到分批态。
//                    仅"全部"分类下生效：其他分类默认就是全集，没有"展开"概念。
// - 「换一批」在所有分类下都可用（包括科学家/明星/企业家等具体分类），让用户能浏览同分类下更多角色。
// 之前试过"始终展示当前批 + 换一批"的纯单层交互，但 36/12=3 批切换时
// 用户容易感觉"卡住"——所以加了"显示全部"作为兜底。
// 注意：120 人展开会很长（一屏 30+ 行卡片），所以默认仍按分批态呈现；
// 用户主动点「显示全部」才全铺。6 列网格响应式断点：≥1100px 6 列 / 700-1100 4 列 / <700 3 列。
const BATCH_SIZE = 18
const showAllFeatured = ref(false)
const currentFeaturedBatch = ref(0)
const featuredTotalBatches = computed(() =>
  Math.max(1, Math.ceil(featuredCharacters.value.length / BATCH_SIZE))
)
// 当前页内实际渲染的角色列表。
//   所有分类都按 currentFeaturedBatch 切片 18 人，让"换一批"在所有分类下都能切换。
//   showAllFeatured 仅在"全部"分类时为 true（其他分类没有"展开全部"概念），为 true 时返回全集。
//   searchQuery 必须先于切片过滤：否则 base 只是当前批 18 人，搜不到其它批次里的角色（如雷军）。
//   这里共用 searchQuery ref：发现页与"我的聊天"tab 的搜索框是同一变量——切换 tab 后旧关键字会
//   留存在发现页输入框。这是预先存在的行为，本任务不修，仅注释说明。
const displayedFeatured = computed(() => {
  // 先按 searchQuery 从全集过滤（顺序关键：必须先过滤再切片，否则搜不到其它批次的角色）。
  const q = searchQuery.value.trim().toLowerCase()
  const filtered = q
    ? featuredCharacters.value.filter(c =>
        c.name?.toLowerCase().includes(q) ||
        c.role?.toLowerCase().includes(q) ||
        c.description?.toLowerCase().includes(q)
      )
    : featuredCharacters.value

  // 没搜索关键字时按"换一批"逻辑切片（showAllFeatured 或批次号）。
  // 有搜索关键字时直接返回全集：用户搜索时希望看到所有命中，而不是当前批次的子集。
  if (q) return filtered
  return showAllFeatured.value
    ? filtered
    : filtered.slice(
        currentFeaturedBatch.value * BATCH_SIZE,
        (currentFeaturedBatch.value + 1) * BATCH_SIZE
      )
})
function toggleShowAllFeatured() {
  showAllFeatured.value = !showAllFeatured.value
}
function shuffleFeaturedBatch() {
  if (showAllFeatured.value) return
  currentFeaturedBatch.value = (currentFeaturedBatch.value + 1) % featuredTotalBatches.value
}

// 发现页"推荐角色"列表的拉取与兜底：头像缺失时用 DiceBear SVG 生成确定性占位（seed=name），
// 这样同一角色无论何时显示都是同一张图，提升品牌识别一致性。
// category='all' 时不传参数给后端，返回全部；其他值传枚举名给后端按 category 过滤。
async function fetchFeaturedCharacters(category?: string) {
  featuredCharactersLoading.value = true
  try {
    const characters = await charactersApi.getRecommended(
      category && category !== 'all' ? category : undefined
    )
    featuredCharacters.value = characters.data.map((char: any) => ({
      id: char.id,
      name: char.name,
      role: char.description || 'AI 角色',
      avatar: char.avatarUrl || `https://api.dicebear.com/7.x/personas/svg?seed=${encodeURIComponent(char.name)}&backgroundColor=c0aede`,
      online: false,
      // 把后端原始字段也保留下来，让 startChat 在 clone 时能拿到完整数据：
      // 不带这些字段会导致加入角色库后头像/描述/prompt 全为空，触发前端首字母占位符 fallback。
      avatarUrl: char.avatarUrl || '',
      description: char.description || '',
      prompt: char.prompt || ''
    }))
    // 切分类时把"换一批"的批次重置为 0，避免批次索引越界（每个分类人数不同）
    currentFeaturedBatch.value = 0
    // 同时收起"显示全部"——切换分类后新分类从收起态开始，避免上一个分类的展开态被带过来
    showAllFeatured.value = false
  } catch (e) {
    console.error('[DEBUG] Failed to fetch featured characters:', e)
    featuredCharacters.value = []
  } finally {
    featuredCharactersLoading.value = false
  }
}


// Recent chats - computed from sortedMyRooms (max 4)
// 与「我的聊天」中间列表共用 displayedRoomAvatars 提取角色头像，
// 渲染时也复刻中间列表的 3×3 网格样式，保证侧栏与列表的视觉一致。
const recentChats = computed(() => {
  return roomStore.sortedMyRooms.slice(0, 4).map(room => ({
    id: room.id,
    name: room.name,
    lastMessage: room.topic || '开始聊天吧',
    // 透传整个 room 给模板，模板里再调 displayedRoomAvatars 拿前 9 个角色
    room
  }))
})

// 挂载时并发拉取多份数据（不互依赖），让发现/我的/角色库等多个 tab 都能秒开。
// fetchPresets 用于「推荐角色」标题旁的默认角色总数展示。
// document 级 click 监听注册在此处而非组件内：避免 dropdown 被 portal 出去后点不到关闭。
onMounted(() => {
  roomStore.fetchRooms()
  roomStore.fetchMyRooms()
  characterStore.fetchCharacters()
  characterStore.fetchPresets()
  fetchFeaturedCharacters()
  fetchHotRooms()
  setTimeout(() => { mounted.value = true }, 50)

  // 点击外部时关闭下拉菜单
  document.addEventListener('click', handleClickOutside)
  document.addEventListener('click', onRoomMenuOutsideClick)

  // + 创建菜单跟随按钮：
  // - resize：视口尺寸变化时按钮位置可能变（断点切换等）
  // - scroll：用 capture 模式监听任意滚动容器（包括菜单内部、未来若嵌套 scrollview 也能触发）
  // 任意一个事件触发时，如果菜单是开的，就重算坐标保证菜单紧贴按钮。
  window.addEventListener('resize', handleDropdownRelocate)
  window.addEventListener('scroll', handleDropdownRelocate, true)

  // 加载已保存的折叠状态
  loadLayoutState()
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  document.removeEventListener('click', onRoomMenuOutsideClick)
  window.removeEventListener('resize', handleDropdownRelocate)
  window.removeEventListener('scroll', handleDropdownRelocate, true)
})

// resize/scroll 共用：菜单开着就重算坐标
function handleDropdownRelocate() {
  if (showCreateDropdown.value) updateDropdownPos()
}

// sidebar 折叠态切换会改变按钮的 rect.left/right（72px ↔ 260px）。
// 折叠切换的瞬间即使菜单没在动，也要把 fixed 菜单拉回按钮旁边——这是之前「菜单
// 飘到头像下面」的真正原因：折叠后按钮横移 ~190px，菜单还停留在旧坐标。
watch(isGlobalSidebarCollapsed, () => {
  if (showCreateDropdown.value) updateDropdownPos()
})

function handleClickOutside(e: MouseEvent) {
  if (dropdownRef.value && !dropdownRef.value.contains(e.target as Node)) {
    closeCreateDropdown()
  }
}

function handleRoomCreated(roomId: string) {
  // Navigate to my-rooms tab with the new room selected
  router.replace({
    path: '/rooms',
    query: {
      tab: 'my-rooms',
      roomId: roomId
    }
  })
}

// 我的聊天列表点击：先上报一次访问（用于后端统计/排序），再把 URL 切换到对应 roomId。
// 用 router.replace 而非 push：避免每次切换房间都堆积一条历史记录。
function selectRoom(roomId: string) {
  roomStore.recordEnter(roomId)
  selectedRoomId.value = roomId
  router.replace({
    path: '/rooms',
    query: {
      ...route.query,
      tab: 'my-rooms',
      roomId: roomId
    }
  })
}

// 热门聊天室卡片点击入口：把 demo 卡片里的"参与者名"翻译成 preset 真实 id，直接建群聊。
// 之前实现的 clone 路线（先复制角色到自己的角色库）有两个问题：
// 热门房间点击入口：先确保每个参与者的 preset 角色已经 clone 到当前用户的角色库，
// 再用 clone 后的 ID 集合调 roomStore.createRoom 创建群聊。
//
// 为什么走 clone 而不是直接用 preset.id（早期 try 直接拿来建）：
//   后端 V10 之后预设角色仅在内存 PresetCharacterCache 里，没有写入 characters 表；
//   RoomService.create 走 CharacterRepository.findById 时找不到 preset id → 400。
//   clone 路径调 charactersApi.create，由后端 CharacterService.create 把整条记录
//   （含 prompt / avatar / categories）真实 INSERT 到 characters 表，房间的外键关联生效。
//
// 用户体验角度：clone 后用户可以在「角色库」页编辑 prompt、加自定义描述，
// 跟"推荐角色"卡片走同一条路径，行为一致。
//
// 失败策略：单条 clone 失败 → 终止整个流程并 toast 具体原因，避免「角色集合为 0」
// 但 toast 又是笼统"创建失败"这种用户无法自救的提示。
async function enterRoom(roomId: string) {
  // 热门卡片 id 是 'hot-xxxx'；UUID 形态的房间 id 是「最近聊天」侧栏 / URL ?roomId= 进入的
  // 真实房间，跳到 selectRoom。
  const card = hotRooms.value.find(r => r.id === roomId)
  if (!card) {
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(roomId)) {
      selectRoom(roomId)
    } else {
      console.warn('[DEBUG] enterRoom: unknown roomId', roomId)
      toast.error('该聊天室已不可用，请重新创建')
    }
    return
  }
  // 点击期间 disable 整张卡片：避免用户连点同一卡片造成并发 clone 重复入库。
  enteringHotRoomId.value = roomId
  try {
    const result = await cloneParticipantsToMyLibrary(card)
    if (result.missing.length > 0) {
      toast.info(`已加入 ${result.ids.length} 位角色，${result.missing.join('、')} 在角色库中未匹配到`)
    }
    if (result.ids.length === 0) {
      toast.error('未能从该热门房间匹配到任何角色，请稍后重试')
      return
    }
    // store 内部有"同 owner + 同角色集合"去重，重复点同一张卡不会建新房间
    const room = await roomStore.createRoom(
      card.title,
      undefined,
      result.ids,
      'group'
    )
    if (!room?.id) {
      toast.error(roomStore.error || '创建聊天室失败')
      return
    }
    selectedRoomId.value = room.id
    router.replace({
      path: '/rooms',
      query: {
        ...route.query,
        tab: 'my-rooms',
        roomId: room.id
      }
    })
  } catch (e) {
    console.error('[DEBUG] enterRoom failed:', e)
    const msg = e instanceof Error ? e.message : '进入聊天室失败，请重试'
    toast.error(msg)
  } finally {
    enteringHotRoomId.value = null
  }
}

// 热门聊天室点击锁：标记当前正在进入的房间卡片 id，避免用户连续点击同一张卡片造成并发。
const enteringHotRoomId = ref<string | null>(null)

/**
 * 把热门卡片的 participants 中文名列表解析为"当前用户角色库"里的角色 id 列表。
 *
 * 两个数据源按顺序查：
 *   1) featuredCharacters（onMounted 时拉过推荐位 cache，热路径 0 网络）；
 *   2) characterStore.presets（兜底全量 preset）；
 *   命中后若 store.characters 已有当前用户的 clone（ownerId == me && name == preset.name），
 *   直接复用 clone；否则 clone 一份到我的角色库。
 *
 * 兜底 categories 不限制：通过 characterStore.fetchPresets() 把全量预加载进来即可。
 *
 * 返回：
 *   - ids: 可直接喂给 RoomService.create 的角色 id 列表（按 input 顺序）；
 *   - missing: 在 preset 列表里都查不到的名字，提示给用户。
 */
async function cloneParticipantsToMyLibrary(
  card: HotRoom
): Promise<{ ids: string[]; missing: string[] }> {
  const myUserId = authStore.user?.id
  if (!myUserId) {
    throw new Error('当前未登录，无法创建聊天室')
  }

  // 确保 presets 列表已就位（findFirstByOwnerIdAndNameAndIsPresetFalse 查重也依赖同名索引）
  let presetByName = new Map<string, { id: string; description?: string; prompt?: string; avatarUrl?: string }>()
  for (const c of featuredCharacters.value) {
    if (c?.id && c?.name) {
      presetByName.set(c.name, {
        id: c.id,
        description: c.description,
        prompt: c.prompt,
        avatarUrl: c.avatarUrl
      })
    }
  }
  if (presetByName.size === 0 && characterStore.presets.length === 0) {
    try {
      await characterStore.fetchPresets()
    } catch (e) {
      console.warn('[DEBUG] fetchPresets failed', e)
    }
  }
  for (const c of characterStore.presets) {
    if (c?.id && c?.name && !presetByName.has(c.name)) {
      presetByName.set(c.name, { id: c.id, description: c.description, prompt: c.prompt, avatarUrl: c.avatarUrl })
    }
  }

  const ids: string[] = []
  const missing: string[] = []

  for (const name of card.participants) {
    // 已经 clone 过：store.characters 里 ownerId == me && name 命中直接复用
    const existing = characterStore.characters.find(
      c => c.ownerId === myUserId && c.name === name
    )
    if (existing) {
      ids.push(existing.id)
      // 修复老库 clone 出来头像为空的问题：之前 featuredCharacters 没保留原字段，
      // 这里兜底补一次 avatarUrl，保持「角色库」页显示真实头像。
      if (!existing.avatarUrl) {
        const preset = presetByName.get(name)
        if (preset?.avatarUrl) {
          await characterStore.updateCharacter(existing.id, {
            name: existing.name,
            description: existing.description || '',
            avatarUrl: preset.avatarUrl,
            prompt: existing.prompt || preset.prompt || ''
          })
        }
      }
      continue
    }

    const preset = presetByName.get(name)
    if (!preset) {
      // preset 角色库都没有：用户知道"这位不可用"，但仍建群
      missing.push(name)
      continue
    }

    // 与 startChat 共用同一个并发锁，避免连点同一角色导致重复入库
    if (cloningCharacterNames.value.has(name)) {
      // 等待 in-flight 的同名 clone 完成（轮询 store 直到行可见）
      // 这里给个 30 次 200ms 的轮询上限，超时则 continue（返回去让用户重试）
      for (let i = 0; i < 30; i++) {
        await new Promise(r => setTimeout(r, 200))
        const justCreated = characterStore.characters.find(
          c => c.ownerId === myUserId && c.name === name
        )
        if (justCreated) {
          ids.push(justCreated.id)
          break
        }
      }
      continue
    }
    cloningCharacterNames.value.add(name)
    try {
      const cloned = await characterStore.createCharacter({
        name,
        description: preset.description || '',
        avatarUrl: preset.avatarUrl || '',
        prompt: preset.prompt || ''
      })
      if (!cloned) {
        throw new Error(`加入「${name}」失败：${characterStore.error || '未知错误'}`)
      }
      ids.push(cloned.id)
    } finally {
      cloningCharacterNames.value.delete(name)
    }
  }

  return { ids, missing }
}

// 根据触发按钮的当前位置实时计算菜单的 fixed 坐标。
// 菜单宽 150px：展开在按钮右侧（按钮右沿 + 6px），顶部与按钮顶部对齐。
// 若按钮距视口右边不足菜单宽，则改为「按钮下方居中」作为安全回退，避免菜单溢出视口右侧。
function updateDropdownPos() {
  if (!dropdownRef.value) return
  const rect = dropdownRef.value.getBoundingClientRect()
  const MENU_WIDTH = 150
  const GAP = 6
  // 优先：按钮右侧，顶部对齐
  let left = rect.right + GAP
  if (left + MENU_WIDTH > window.innerWidth) {
    // 回退：按钮下方居中
    left = rect.left + rect.width / 2 - MENU_WIDTH / 2
  }
  dropdownPos.value = { top: rect.top, left }
}

function toggleCreateDropdown(e: Event) {
  e.stopPropagation()
  if (!showCreateDropdown.value) {
    // 打开时立刻计算一次初值（避免首帧菜单短暂停留在旧坐标）
    updateDropdownPos()
  }
  showCreateDropdown.value = !showCreateDropdown.value
}

function closeCreateDropdown() {
  showCreateDropdown.value = false
}

function handleCreateCharacter() {
  closeCreateDropdown()
  showCreateCharacterModal.value = true
}

// 创建角色成功后的双轨同步：先重新拉列表兜底，再把本次返回的角色 unshift 到 store 头部，
// 解决后端在某些路径下不返回新资源导致的列表不刷新问题。
async function handleCharacterCreated(character: any) {
  showCreateCharacterModal.value = false
  // 先拉取一次以与服务端同步
  await characterStore.fetchCharacters()
  // Ensure the newly created character is in the store (in case API response doesn't include it yet)
  if (character && !characterStore.characters.find(c => c.id === character.id)) {
    characterStore.characters.unshift(character)
  }
  // If created from room context, add to room
  if (createCharacterRoomId.value && character) {
    await roomStore.addCharacterToRoom(createCharacterRoomId.value, character.id)
    await roomStore.fetchRoomById(createCharacterRoomId.value)
    createCharacterRoomId.value = null
  }
}

function handleCreateRoom() {
  closeCreateDropdown()
  showCreateModal.value = true
}

// 与 handleCharacterCreated 不同：这条路径用户是在房间里点"+ 添加角色"新建的，
// 无需再处理"列表里要不要插入"，重点是把这个新角色立即加入当前房间。
async function handleAddedToRoom(character: any) {
  showCreateCharacterModal.value = false
  // 通过 API 刷新角色列表
  await characterStore.fetchCharacters()
  if (selectedRoomId.value && character) {
    await roomStore.addCharacterToRoom(selectedRoomId.value, character.id)
    await roomStore.fetchRoomById(selectedRoomId.value)
  }
}

function openAddCharacterModal() {
  createCharacterRoomId.value = selectedRoomId.value
  showCreateCharacterModal.value = true
}

// 通过用户名/邮箱向当前房间发送邀请：成功后清空关键字并关闭弹窗；
// 失败时把错误留在弹窗内而不是用 toast：让用户在邀请上下文里直接看到失败原因。
async function handleInviteMember() {
  if (!inviteKeyword.value.trim() || !selectedRoomId.value) return
  inviteLoading.value = true
  inviteError.value = null
  try {
    await roomStore.inviteRoomMember(selectedRoomId.value, inviteKeyword.value.trim())
    showInviteModal.value = false
    inviteKeyword.value = ''
  } catch (e: any) {
    inviteError.value = e.message
  } finally {
    inviteLoading.value = false
  }
}
</script>

<template>
  <div
    class="page-layout"
    :class="{
      mounted,
      'global-collapsed': isGlobalSidebarCollapsed,
      'room-list-collapsed': isRoomListCollapsed,
      'role-panel-collapsed': isRolePanelCollapsed
    }"
    :style="{
      '--global-sidebar-width': isGlobalSidebarCollapsed ? '72px' : '220px',
      '--room-list-width': isRoomListCollapsed ? '0px' : '280px',
      '--role-panel-width': isRolePanelCollapsed ? '0px' : '280px'
    }"
  >
    <!-- 左侧边栏 -->
    <aside class="sidebar">
      <!-- 折叠按钮 -->
      <button
        class="sidebar-collapse-btn"
        @click="isGlobalSidebarCollapsed = !isGlobalSidebarCollapsed"
        :aria-label="isGlobalSidebarCollapsed ? '展开侧边栏' : '折叠侧边栏'"
      >
        <svg v-if="isGlobalSidebarCollapsed" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
        </svg>
        <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
        </svg>
      </button>

      <!-- 品牌 Logo -->
      <div class="sidebar-brand">
        <img :src="BRAND_LOGO" alt="logo" class="sidebar-brand-logo" />
        <span class="logo-text">Idea Party</span>
      </div>

      <!-- 创建按钮（下拉菜单） -->
      <div class="create-dropdown-wrapper">
        <button ref="dropdownRef" class="create-btn" @click.stop="toggleCreateDropdown">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          <span>创建</span>
        </button>

        <!-- 下拉菜单：Teleport 到 body 避免被 sidebar/page-layout 的 overflow:hidden 裁剪
             或被 stacking context 错位（之前菜单会"穿透"到主内容区遮住角色卡片）。 -->
        <Teleport to="body">
          <Transition name="dropdown">
            <div
              v-if="showCreateDropdown"
              class="create-dropdown-menu"
              :style="{ top: dropdownPos.top + 'px', left: dropdownPos.left + 'px' }"
              @click.stop
            >
              <button class="dropdown-item" @click.stop="handleCreateCharacter">
                <span class="dropdown-icon">👤</span>
                <span class="dropdown-label">创建角色</span>
              </button>
              <button class="dropdown-item" @click="handleCreateRoom">
                <span class="dropdown-icon">💬</span>
                <span class="dropdown-label">创建聊天室</span>
              </button>
            </div>
          </Transition>
        </Teleport>
      </div>

      <!-- 导航 -->
      <nav class="nav-menu">
        <a
          v-for="item in MINIMAL_NAV_ITEMS"
          :key="item.id"
          href="#"
          class="nav-item"
          :class="{ active: item.id === activeNavId }"
          @click.prevent="handleNavClick(item.id)"
        >
          <span v-if="item.icon" class="nav-emoji">
            <component :is="navIcon(item.icon)" class="w-5 h-5" :stroke-width="1.75" />
          </span>
          <span class="nav-label">{{ item.label }}</span>
        </a>
      </nav>

      <!-- 最近聊天 -->
      <div class="recent-chats">
        <div class="section-header">
          <span class="section-title">最近聊天</span>
        </div>
        <div class="chat-list">
          <a
            v-for="chat in recentChats"
            :key="chat.id"
            href="#"
            class="chat-item"
            @click.prevent="enterRoom(chat.id)"
          >
            <!-- 多角色房间:与「我的聊天」列表保持视觉一致,使用 3×3 网格拼接最多 9 张头像。
                 >1 张时进 is-grid 网格模式;0 张时首字母占位;1 张时单图占满。 -->
            <div
              v-if="displayedRoomAvatars(chat.room).length === 0"
              class="chat-avatar"
            >
              <span class="chat-avatar-placeholder">{{ chat.name.charAt(0) }}</span>
            </div>
            <div
              v-else
              class="chat-avatar"
              :class="{ 'is-grid': displayedRoomAvatars(chat.room).length > 1 }"
            >
              <div
                v-for="(c, i) in displayedRoomAvatars(chat.room).slice(0, 9)"
                :key="c.id || i"
                class="chat-avatar-cell"
              >
                <img
                  v-if="c.avatarUrl"
                  :src="c.avatarUrl"
                  :alt="c.name || chat.name"
                  @error="(e) => ((e.target as HTMLImageElement).style.display = 'none')"
                />
                <span v-else class="chat-avatar-fallback">{{ (c.name || '?').charAt(0) }}</span>
              </div>
            </div>
            <div class="chat-info">
              <span class="chat-name">{{ chat.name }}</span>
              <span class="chat-preview">{{ chat.lastMessage }}</span>
            </div>
          </a>
        </div>
      </div>

      <!-- 用户头像 -->
      <UserDropdown />
    </aside>

    <!-- 主内容 -->
    <main class="main-content">
      <!-- 场景视图 -->
      <template v-if="isScenariosView">
        <header class="content-header discover-header">
          <h1 class="page-title">场景</h1>
          <!-- 中间搜索 + 右侧自定义场景按钮：复用角色库的 .search-box 视觉，
               .header-actions 保持三者横向对齐 -->
          <div class="header-actions">
            <div class="search-box">
              <svg class="search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-4.35-4.35M11 19a8 8 0 100-16 8 8 0 000 16z" />
              </svg>
              <input
                v-model="scenarioSearchQuery"
                type="text"
                class="search-input"
                placeholder="搜索场景名或描述…"
                aria-label="搜索场景"
              />
              <button
                v-if="scenarioSearchQuery"
                type="button"
                class="search-clear"
                aria-label="清除搜索"
                @click="scenarioSearchQuery = ''"
              >
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            <button
              type="button"
              class="btn-create-scenario"
              @click="openCreateCustomScenario"
            >
              <span class="btn-create-icon">＋</span>
              自定义场景
            </button>
          </div>
        </header>

        <!-- 搜索无结果:与"列表本身为空"区分,避免误以为系统没数据 -->
        <div v-if="filteredScenarios.length === 0 && scenarioSearchQuery.trim()" class="empty-state scenarios-empty">
          <div class="empty-icon">🔍</div>
          <h2 class="empty-title">未找到匹配的场景</h2>
          <p class="empty-desc">没有匹配「{{ scenarioSearchQuery }}」的场景，试试别的关键词？</p>
          <button class="empty-btn" @click="scenarioSearchQuery = ''">清除搜索</button>
        </div>

        <div v-else class="scenarios-grid">
          <div
            v-for="s in filteredScenarios"
            :key="s.id"
            class="scenario-card-wrap"
            :class="{ 'is-user': !s.isPreset }"
          >
            <button
              type="button"
              class="scenario-card"
              @click="openScenario(s)"
            >
              <!-- 16:9 封面:有 cover 用图(配暗遮罩 + 标题压底),无 cover 用 emoji 渐变居中。
                   resolveAvatarUrl 加 cache-buster,与热门聊天室头像同款策略。 -->
              <div
                class="scenario-cover"
                :class="{ 'has-image': !!s.cover, 'is-emoji': !s.cover }"
                :style="!s.cover ? { background: scenarioCover(s.id) } : null"
              >
                <img
                  v-if="s.cover"
                  :src="s.cover"
                  :alt="s.title"
                  class="cover-img"
                />
                <!-- 暗遮罩:让标题压底时白字可读,只在有图时显示 -->
                <div v-if="s.cover" class="cover-overlay"></div>
                <!-- emoji 居中:仅在无图时显示 -->
                <span v-if="!s.cover" class="scenario-cover-emoji">{{ s.emoji }}</span>
                <!-- 标题压底:仅在有图时显示,叠在 cover 内部 -->
                <div v-if="s.cover" class="scenario-cover-title-wrap">
                  <h3 class="scenario-cover-title">{{ s.title }}</h3>
                </div>
              </div>

              <div class="scenario-body">
                <!-- 有图时:body 不再重复标题(已在 cover 上),只显示描述 + 示例片段 -->
                <template v-if="!s.cover">
                  <h3 class="scenario-title">{{ s.title }}</h3>
                </template>
                <p class="scenario-desc">{{ s.description }}</p>

                <!-- 示例片段:与热门聊天室"最新消息"区视觉一致,
                     用引号引出,营造"对话感"。 -->
                <div v-if="s.sampleQuote" class="scenario-quote">
                  <span class="scenario-quote-text">"{{ s.sampleQuote }}"</span>
                </div>
              </div>
            </button>
            <!-- 用户私有场景：右上角 hover 出现编辑/删除按钮 -->
            <div v-if="!s.isPreset" class="scenario-actions">
              <button
                type="button"
                class="scenario-action-btn"
                title="编辑"
                @click="openEditCustomScenario(s, $event)"
              >✏️</button>
              <button
                type="button"
                class="scenario-action-btn scenario-action-danger"
                title="删除"
                @click="openDeleteCustomScenario(s, $event)"
              >🗑️</button>
            </div>
          </div>
        </div>

        <!-- 内嵌模板预览弹窗（Teleport 到 body） -->
        <Teleport to="body">
          <Transition name="fade">
            <div v-if="activeScenario" class="scenario-modal-overlay" @click.self="closeScenario">
              <div class="scenario-modal">
                <header class="scenario-modal-header">
                  <div class="scenario-modal-headline">
                    <div class="scenario-modal-emoji">{{ activeScenario.emoji }}</div>
                    <h2 class="scenario-modal-title">{{ activeScenario.title }}</h2>
                  </div>
                  <p class="scenario-modal-desc">{{ activeScenario.description }}</p>
                </header>
                <div class="scenario-modal-body">
                  <!-- 第一步：填岗位 / JD -->
                  <template v-if="scenarioStep === 'input'">
                    <div v-if="activeScenario.requiresUserInput">
                      <label class="scenario-modal-label">
                        {{ activeScenario.userInputLabel || '输入' }} *
                      </label>
                      <textarea
                        v-model="userInput"
                        class="scenario-modal-input"
                        :placeholder="activeScenario.userInputPlaceholder"
                        rows="2"
                        :disabled="creatingScenario"
                      ></textarea>

                      <!-- 面试场景专用：JD 描述 -->
                      <template v-if="activeScenario.id === 'interview-coach'">
                        <label class="scenario-modal-label" style="margin-top: 14px;">
                          岗位描述（可选）
                        </label>
                        <textarea
                          v-model="jobDescription"
                          class="scenario-modal-input scenario-modal-input-tall"
                          placeholder="把招聘网站的 JD 粘过来，或把截图拖到下方 / Ctrl+V 粘贴"
                          rows="6"
                          :disabled="creatingScenario"
                          @paste="onJdImagePaste"
                        ></textarea>

                        <!-- JD 截图识别：拖拽 / 点击 / 粘贴 -->
                        <div
                          class="jd-image-dropzone"
                          :class="{ 'is-loading': jdImageUploading }"
                          @drop="onJdImageDrop"
                          @dragover="onJdImageDragOver"
                        >
                          <span v-if="jdImageUploading">🔍 正在识别图片中的文字...</span>
                          <template v-else>
                            <span>📷 拖拽 JD 截图到此处，或</span>
                            <label class="jd-image-btn">
                              点击上传
                              <input
                                type="file"
                                accept="image/png,image/jpeg,image/jpg,image/webp,image/gif"
                                @change="onJdImageFileChange"
                                style="display:none"
                              />
                            </label>
                            <span class="jd-image-hint">支持 Ctrl+V 粘贴 · PNG/JPG/WEBP · 最大 5MB</span>
                          </template>
                        </div>
                        <p v-if="jdImageError" class="scenario-modal-error-inline">{{ jdImageError }}</p>

                        <!-- 简历上传 -->
                        <label class="scenario-modal-label" style="margin-top: 14px;">
                          简历（可选）
                        </label>
                        <div
                          v-if="!resumeFilename"
                          class="resume-dropzone"
                          :class="{ 'is-dragging': isDragging, 'is-loading': resumeUploading }"
                          @drop="onResumeDrop"
                          @dragover="onResumeDragOver"
                          @dragleave="onResumeDragLeave"
                        >
                          <div v-if="resumeUploading" class="resume-dropzone-loading">
                            正在解析简历...
                          </div>
                          <template v-else>
                            <div class="resume-dropzone-icon">📄</div>
                            <div class="resume-dropzone-text">
                              拖拽 docx / pdf / txt 到这里
                            </div>
                            <div class="resume-dropzone-text">或</div>
                            <label class="resume-dropzone-btn">
                              点击选择文件
                              <input
                                type="file"
                                accept=".pdf,.docx,.doc,.txt"
                                @change="onResumeFileChange"
                                style="display:none"
                              />
                            </label>
                            <div class="resume-dropzone-meta">最大 5MB · 不会保存原文件</div>
                          </template>
                        </div>
                        <div v-else class="resume-uploaded">
                          <span class="resume-uploaded-icon">✅</span>
                          <span class="resume-uploaded-name">{{ resumeFilename }}</span>
                          <span class="resume-uploaded-meta">
                            {{ resumeText.length }} 字
                            <span v-if="resumeTruncated">（已截断）</span>
                          </span>
                          <button
                            type="button"
                            class="resume-clear-btn"
                            :disabled="resumeUploading"
                            @click="clearResume"
                          >移除</button>
                        </div>
                        <p v-if="resumeError" class="scenario-modal-error-inline">{{ resumeError }}</p>
                        <p class="scenario-modal-hint">
                          💡 上传简历后，AI 会根据你的真实项目经历出题，比泛问更专业
                        </p>
                      </template>

                      <label
                        v-if="!activeScenario.dynamicPrompt && activeScenario.promptTemplate"
                        class="scenario-modal-label"
                        style="margin-top: 14px;"
                      >提示词模板</label>
                      <textarea
                        v-if="!activeScenario.dynamicPrompt && activeScenario.promptTemplate"
                        v-model="editablePromptTemplate"
                        class="scenario-modal-input scenario-modal-prompt-editor"
                        rows="14"
                        :disabled="creatingScenario"
                      ></textarea>
                      <p
                        v-if="!activeScenario.dynamicPrompt && activeScenario.promptTemplate && editablePromptTemplate !== null"
                        class="scenario-modal-hint"
                      >
                        💡 编辑后的 prompt 会直接用作 Character.prompt，跳过 AI 自动生成
                      </p>
                    </div>
                  </template>

                  <!-- 第二步（动态生成场景）：预览/编辑 prompt -->
                  <template v-else-if="scenarioStep === 'preview' && activeScenario.dynamicPrompt">
                    <label class="scenario-modal-label">
                      AI 生成的面试官 · 角色名
                    </label>
                    <div class="scenario-modal-char-name">{{ generatedCharacterName }}</div>

                    <label class="scenario-modal-label" style="margin-top: 14px;">
                      面试官系统提示词（可自由编辑）
                    </label>
                    <textarea
                      v-model="generatedPrompt"
                      class="scenario-modal-input scenario-modal-input-tall"
                      rows="14"
                      :disabled="creatingScenario"
                    ></textarea>
                    <p class="scenario-modal-hint">
                      ✏️ 你可以微调这份 prompt，让面试官更贴近你想要的风格
                    </p>
                  </template>
                </div>
                <footer class="scenario-modal-footer">
                  <button
                    v-if="scenarioStep === 'preview'"
                    type="button"
                    class="btn btn-secondary"
                    :disabled="creatingScenario"
                    @click="scenarioStep = 'input'"
                  >← 上一步</button>
                  <button
                    v-else
                    type="button"
                    class="btn btn-secondary"
                    :disabled="creatingScenario"
                    @click="closeScenario"
                  >取消</button>
                  <button
                    v-if="scenarioStep === 'input'"
                    type="button"
                    class="btn btn-primary"
                    :disabled="creatingScenario || (activeScenario.requiresUserInput && !userInput.trim())"
                    @click="nextStep"
                  >
                    {{ creatingScenario ? '生成中…' : (activeScenario.dynamicPrompt ? '生成面试官 →' : '开始对话') }}
                  </button>
                  <button
                    v-else
                    type="button"
                    class="btn btn-primary"
                    :disabled="creatingScenario"
                    @click="finalizeScenario"
                  >
                    {{ creatingScenario ? '创建中…' : '开始对话' }}
                  </button>
                </footer>
                <p v-if="createError" class="scenario-modal-error">{{ createError }}</p>
              </div>
            </div>
          </Transition>
        </Teleport>
      </template>

      <!-- 角色库视图 -->
      <template v-else-if="isCharactersView">
        <header class="content-header discover-header">
          <h1 class="page-title">角色库</h1>
          <div class="header-actions">
            <div class="search-box">
              <svg class="search-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-4.35-4.35M11 19a8 8 0 100-16 8 8 0 000 16z" />
              </svg>
              <input
                v-model="characterSearchQuery"
                type="text"
                class="search-input"
                placeholder="搜索角色名或描述…"
                aria-label="搜索角色"
              />
              <button
                v-if="characterSearchQuery"
                class="search-clear"
                type="button"
                aria-label="清除搜索"
                @click="characterSearchQuery = ''"
              >
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            <button class="create-btn-large" @click="showCreateCharacterModal = true">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
              </svg>
              创建角色
            </button>
          </div>
        </header>

        <!-- 空状态：分两种,避免「明明有角色却显示『还没创建』」的误导 -->
        <div v-if="myCharacters.length === 0" class="empty-state">
          <template v-if="characterSearchQuery.trim()">
            <div class="empty-icon">🔍</div>
            <h2 class="empty-title">未找到匹配的角色</h2>
            <p class="empty-desc">没有匹配「{{ characterSearchQuery }}」的角色，试试别的关键词？</p>
            <button class="empty-btn" @click="characterSearchQuery = ''">清除搜索</button>
          </template>
          <template v-else>
            <div class="empty-icon">📚</div>
            <h2 class="empty-title">还没有创建角色</h2>
            <p class="empty-desc">创建你的第一个 AI 角色，开始对话吧！</p>
            <button class="empty-btn" @click="showCreateCharacterModal = true">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
              </svg>
              创建角色
            </button>
          </template>
        </div>

        <!-- 角色卡片网格 -->
        <div v-else class="character-grid">
          <div
            v-for="character in myCharacters"
            :key="character.id"
            class="character-card-item"
            role="button"
            tabindex="0"
            @click="openEditCharacterModal(character)"
            @keydown.enter="openEditCharacterModal(character)"
            @keydown.space.prevent="openEditCharacterModal(character)"
          >
            <div class="character-avatar">
              <img
                v-if="character.avatarUrl"
                :src="character.avatarUrl"
                :alt="character.name"
              />
              <span v-else class="avatar-placeholder">{{ character.name.charAt(0) }}</span>
            </div>
            <div class="character-info">
              <h3 class="character-name">{{ character.name }}</h3>
              <p class="character-tagline">{{ character.description || '暂无描述' }}</p>
            </div>
          </div>
        </div>
      </template>

      <!-- 我的聊天视图——三栏布局 -->
      <template v-else-if="isMyRoomsView">
        <div class="rooms-chat-shell">
          <!-- 左侧：聊天室列表面板 -->
          <aside class="rooms-list-panel">
            <!-- 聊天室列表已收起且已选中某个聊天室时的展开按钮 -->
            <button
              v-if="isRoomListCollapsed && selectedRoomId"
              class="room-list-toggle-btn"
              @click="isRoomListCollapsed = false"
              aria-label="展开聊天室列表"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
              </svg>
            </button>

            <div class="rooms-list-header">
              <div>
                <h2>我的聊天</h2>
              </div>
              <div class="flex items-center gap-2">
                <button class="icon-create-room-button" @click="showCreateModal = true" aria-label="创建聊天室">
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 4v16m8-8H4" />
                  </svg>
                </button>
                <button
                  v-if="!isRoomListCollapsed && selectedRoomId"
                  class="icon-close-room-list-button"
                  @click="isRoomListCollapsed = true"
                  aria-label="收起聊天室列表"
                >
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
                  </svg>
                </button>
              </div>
            </div>

            <!-- 搜索 -->
            <div class="rooms-search">
              <input v-model="searchQuery" type="text" placeholder="搜索聊天室..." />
            </div>

            <!-- 加载中 -->
            <div v-if="roomStore.myRoomsLoading" class="rooms-loading">
              <div class="loading-spinner"></div>
              <span>加载中...</span>
            </div>

            <!-- 空状态 -->
            <div v-else-if="roomStore.sortedMyRooms.length === 0" class="rooms-empty">
              <div class="empty-icon">💬</div>
              <h3>还没有聊天室</h3>
              <p>创建一个聊天室，邀请多个 AI 角色一起讨论。</p>
              <button class="empty-btn" @click="showCreateModal = true">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
                </svg>
                创建聊天室
              </button>
            </div>

            <!-- 搜索无匹配 -->
            <div v-else-if="filteredMyRooms.length === 0" class="rooms-empty">
              <div class="empty-icon">🔍</div>
              <h3>没有匹配「{{ searchQuery }}」的聊天室</h3>
              <p>试试按房间名、主题或角色名搜索。</p>
            </div>

            <!-- 聊天室列表 -->
            <div v-else class="rooms-list-scroll">
              <div
                v-for="room in filteredMyRooms"
                :key="room.id"
                class="room-list-item"
                :class="{ active: selectedRoomId === room.id }"
                @click="selectRoom(room.id)"
              >
                <!-- 房间头像：仿微信群聊风格的多头像堆叠（最多 3×3=9 个，>9 显示 +N）。
                     与"最近聊天"侧栏的 1 张头像区分开：列表项密度更高，单头像难以区分多个群。 -->
                <div
                  class="room-list-icon"
                  :class="{ 'is-grid': displayedRoomAvatars(room).length > 1 }"
                >
                  <template v-if="displayedRoomAvatars(room).length === 0">
                    <span>{{ room.name?.charAt(0) || '💬' }}</span>
                  </template>
                  <template v-else>
                    <!-- 每个头像一张小图。+N 时把第 9 格换成角标 -->
                    <div
                      v-for="(c, i) in displayedRoomAvatars(room).slice(0, 9)"
                      :key="c.id || i"
                      class="room-list-avatar-cell"
                    >
                      <img
                        v-if="c.avatarUrl"
                        :src="c.avatarUrl"
                        :alt="c.name || room.name"
                        @error="(e) => ((e.target as HTMLImageElement).style.display = 'none')"
                      />
                      <span v-else class="room-list-avatar-fallback">{{ (c.name || '?').charAt(0) }}</span>
                      <!-- 仅显示在最后一格：溢出指示，让用户知道该房间还有更多角色 -->
                      <span
                        v-if="i === 8 && (room.characters?.length || 0) > 9"
                        class="room-list-avatar-more"
                      >+{{ (room.characters?.length || 0) - 9 }}</span>
                    </div>
                  </template>
                </div>
                <div class="room-list-content">
                  <div class="room-list-title-row">
                    <strong>{{ room.name }}</strong>
                  </div>
                  <p>{{ room.topic || (room.characters?.[0]?.description) || '暂无主题' }}</p>
                  <small>{{ room.characterCount }} 个角色</small>
                </div>
                <div class="room-list-actions">
                  <span class="room-list-time">{{ formatDate(room.updatedAt) }}</span>
                  <button
                    :ref="(el) => (moreBtnRefs[room.id] = el as HTMLElement)"
                    class="room-list-more-btn"
                    aria-label="更多操作"
                    :aria-expanded="openMenuRoomId === room.id"
                    aria-haspopup="menu"
                    @click.stop="toggleRoomMenu(room.id, $event)"
                  >
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <circle cx="5"  cy="12" r="1.8" fill="currentColor" />
                      <circle cx="12" cy="12" r="1.8" fill="currentColor" />
                      <circle cx="19" cy="12" r="1.8" fill="currentColor" />
                  </svg>
                </button>
                </div>
                <ul
                  v-if="openMenuRoomId === room.id"
                  :ref="(el) => (menuRefs[room.id] = el as HTMLElement)"
                  class="room-list-dropdown"
                  role="menu"
                  @click.stop
                >
                  <li>
                    <button class="room-list-menu-item danger" role="menuitem" @click="handleDeleteRoom(room.id, $event)">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"
                              stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                      <span>删除</span>
                    </button>
                  </li>
                </ul>
              </div>
            </div>
          </aside>

          <!-- 中间：聊天面板 -->
          <main class="chat-main-panel">
            <template v-if="selectedRoomId">
              <ChatRoomPanel
                :room-id="selectedRoomId"
                :key="selectedRoomId"
                :embedded="true"
                :show-room-list-toggle="isRoomListCollapsed"
                :show-role-panel-toggle="true"
                :on-toggle-room-list="() => isRoomListCollapsed = !isRoomListCollapsed"
                :on-toggle-role-panel="() => isRolePanelCollapsed = !isRolePanelCollapsed"
              />
            </template>

            <div v-else class="chat-empty-state">
              <div class="chat-empty-icon">💬</div>
              <h2>选择一个聊天室</h2>
              <p>从左侧列表选择聊天室，开始对话。</p>
            </div>
          </main>

          <!-- 右侧：角色面板 -->
          <aside class="room-characters-panel">
            <!-- 单聊模式：只展示角色信息，不显示 tab -->
            <template v-if="currentRoomMode === 'single'">
              <div class="panel-tabs-wrapper">
                <button
                  class="icon-close-role-panel-button"
                  @click="isRolePanelCollapsed = true"
                  aria-label="收起角色面板"
                >
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </button>
              </div>
              <div class="characters-panel-header">
                <h3>对话角色</h3>
              </div>
              <div class="characters-list">
                <div class="character-chip-card">
                  <img v-if="currentRoomCharacters[0].avatarUrl" :src="currentRoomCharacters[0].avatarUrl" :alt="currentRoomCharacters[0].name" />
                  <div v-else class="char-avatar-placeholder">{{ currentRoomCharacters[0].name?.charAt(0) }}</div>
                  <div class="character-info-row">
                    <div class="character-info">
                      <strong>{{ currentRoomCharacters[0].name }}</strong>
                      <span>{{ currentRoomCharacters[0].description || '暂无描述' }}</span>
                    </div>
                    <button @click="openEditCharacterModal(currentRoomCharacters[0])" class="edit-char-btn">
                      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                      </svg>
                      编辑
                    </button>
                  </div>
                </div>
              </div>
            </template>

            <!-- 群聊模式：显示角色与成员两个 tab -->
            <template v-else>
              <!-- 带折叠按钮的 tab 切换器 -->
              <div class="panel-tabs-wrapper">
                <button
                  class="icon-close-role-panel-button"
                  @click="isRolePanelCollapsed = true"
                  aria-label="收起角色面板"
                >
                  <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </button>
                <div class="panel-tabs">
                  <button
                    class="panel-tab"
                    :class="{ active: !showMembersTab }"
                    @click="showMembersTab = false"
                  >
                    聊天室角色
                  </button>
                  <button
                    class="panel-tab"
                    :class="{ active: showMembersTab }"
                    @click="showMembersTab = true; roomStore.fetchRoomMembers(selectedRoomId!)"
                  >
                    聊天室成员
                  </button>
                </div>
              </div>

              <!-- 角色 tab -->
              <template v-if="!showMembersTab">
                <div class="characters-panel-header">
                  <div>
                    <h3>聊天室角色</h3>
                    <p>{{ currentRoomCharacters.length }} 个角色参与讨论</p>
                  </div>
                  <button @click="openAddCharacterModal" class="add-char-btn">+ 邀请</button>
                </div>

              <div v-if="currentRoomCharacters.length === 0" class="characters-empty">
                  <div class="empty-role-icon">👥</div>
                  <h4>还没有角色</h4>
                  <p>添加角色后，就可以开始多角色对话。</p>
                  <button @click="openAddCharacterModal">添加角色</button>
                </div>

                <div v-else class="characters-list">
                  <div v-for="char in currentRoomCharacters" :key="char.id" class="character-chip-card">
                    <img v-if="char.avatarUrl" :src="char.avatarUrl" :alt="char.name" />
                    <div v-else class="char-avatar-placeholder">{{ char.name?.charAt(0) }}</div>
                    <div class="character-info-row">
                      <strong>{{ char.name }}</strong>
                      <span class="character-desc">{{ char.description || '暂无描述' }}</span>
                    </div>
                    <!-- 只对"我自己创建的"角色显示编辑按钮（preset 归系统所有不能改，
                         否则后端 update 会因 ownerId 不匹配返回 403）-->
                    <button
                      v-if="authStore.user?.id && char.ownerId === authStore.user.id"
                      class="edit-char-btn"
                      :title="`编辑「${char.name}」的提示词`"
                      @click="openEditCharacterModal(char)"
                    >
                      <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                      </svg>
                    </button>
                  </div>
                </div>
              </template>

            <!-- 成员 tab -->
            <template v-if="showMembersTab">
              <div class="members-panel-content">
                <div class="members-panel-header">
                  <div>
                    <h3>聊天室成员</h3>
                    <p>{{ roomStore.roomMembers.length }} 人</p>
                  </div>
                  <button @click="showInviteModal = true" class="add-char-btn">+ 邀请</button>
                </div>
                <div class="members-list">
                  <div v-if="roomStore.roomMembersLoading" class="members-loading">
                    加载中...
                  </div>
                  <div v-else-if="roomStore.roomMembers.length === 0" class="members-empty">
                    暂无成员
                  </div>
                  <div v-else v-for="member in roomStore.roomMembers" :key="member.userId" class="member-item">
                    <div class="member-avatar-wrapper">
                      <img
                        v-if="member.avatarUrl"
                        :src="member.avatarUrl"
                        :alt="member.displayName"
                        class="member-avatar"
                      />
                      <img
                        v-else
                        :src="BRAND_LOGO"
                        :alt="member.displayName"
                        class="member-avatar"
                      />
                    </div>
                    <div class="member-info">
                      <strong>{{ member.displayName }}</strong>
                      <span class="member-role">{{ member.role === 'owner' ? '创建者' : member.role === 'admin' ? '管理员' : '成员' }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <div class="conversation-mode-card">
              <div class="mode-switch-header">
                <span class="mode-switch-title">对话模式</span>
              </div>
              <div class="mode-switch-container" @click="switchMode(currentChatMode === 'dialogue' ? 'discussion' : 'dialogue')">
                <div class="mode-switch-track">
                  <div
                    class="mode-switch-thumb"
                    :class="currentChatMode === 'discussion' ? 'thumb-right' : 'thumb-left'"
                  ></div>
                  <span class="mode-label left" :class="{ active: currentChatMode === 'dialogue' }">对话模式</span>
                  <span class="mode-label right" :class="{ active: currentChatMode === 'discussion' }">讨论模式</span>
                </div>
              </div>
              <p class="mode-desc">{{ currentChatMode === 'dialogue' ? '多角色同时响应 (1~N)' : '多角色轮流讨论' }}</p>
            </div>
            </template>
          </aside>
        </div>
      </template>

      <!-- 发现视图 -->
      <template v-else>
        <!-- 头部：flex-start + gap:1rem 让搜索框紧贴"发现"右边 -->
        <header class="content-header discover-header">
          <h1 class="page-title">发现</h1>
          <div class="search-bar search-bar-compact">
            <input
              v-model="searchQuery"
              type="text"
              class="search-input"
              placeholder="搜索角色、讨论、场景..."
            />
          </div>
        </header>

        <!-- 推荐角色 -->
        <section class="featured-section">
          <div class="section-header">
            <h2 class="section-title">
              推荐角色
              <!-- 默认角色总数：来自 store.presets 的实际数量 + 「+」给后续扩展留口子。
                   presets 未加载完时数字为 0，会瞬间被响应替换；首次访问的 50ms 内数字可能短暂为 0，体感可接受。 -->
              <span v-if="characterStore.presets.length > 0" class="preset-total-hint">
                ({{ characterStore.presets.length }}+ 个默认角色)
              </span>
            </h2>
          </div>
          <!-- 分类标签条：用 .category-tabs-row 做 flex 容器，chips 自然溢出滚动。
               「换一批 / 显示全部」按钮已挪到推荐角色网格右下角 + 热门聊天室左上角。 -->
          <div class="category-tabs-row">
            <div class="category-tabs">
              <button
                v-for="cat in categories"
                :key="cat.id"
                class="category-chip"
                :class="{ active: selectedCategory === cat.id }"
                @click="selectedCategory = cat.id"
                :style="selectedCategory === cat.id && cat.color ? { backgroundColor: cat.color + '20', borderColor: cat.color, color: cat.color } : {}"
              >
                <span class="chip-label">{{ cat.label }}</span>
              </button>
            </div>
          </div>
          <div v-if="featuredCharactersLoading" class="featured-loading">
            <div class="loading-spinner"></div>
          </div>
          <div v-else-if="featuredCharacters.length === 0" class="featured-empty">
            暂无推荐角色
          </div>
          <div v-else-if="displayedFeatured.length === 0 && searchQuery.trim()" class="featured-empty">
            没有匹配「{{ searchQuery }}」的角色
          </div>
          <div v-else class="featured-grid">
            <div
              v-for="char in displayedFeatured"
              :key="char.id"
              class="character-card"
              :class="{ 'is-loading': isStartingChat }"
              role="button"
              tabindex="0"
              @click="startChat(char)"
              @keyup.enter="startChat(char)"
            >
              <div class="character-avatar-wrap">
                <!-- 始终渲染占位符（首字母），图片加载完才覆盖在上层。
                     loading="lazy" 让浏览器只在接近视口时才请求图片，
                     配合 decoding="async" 解码不阻塞主线程；
                     一个分类下几十个推荐角色 + 浏览器并发限制 6/域名的场景下，
                     585 张同时请求 nginx 不再是问题——视口外根本不发请求。 -->
                <div class="character-avatar character-avatar-fallback" :data-name="char.name">
                  {{ char.name.charAt(0) }}
                </div>
                <img
                  v-if="!avatarLoadFailed[char.id]"
                  :src="char.avatar"
                  :alt="char.name"
                  class="character-avatar character-avatar-img"
                  loading="lazy"
                  decoding="async"
                  @error="(e) => { console.error('[DEBUG] avatar error', char.name, char.avatar, e); avatarLoadFailed[char.id] = true }"
                  @load="console.log('[DEBUG] avatar ok', char.name, char.avatar)"
                />
                <span v-if="char.online" class="online-indicator"></span>
              </div>
              <!-- DEBUG: 显示真实 avatar URL，便于排查 -->
              <span style="display:none" :data-debug-avatar="char.avatar"></span>
              <div class="character-info">
                <span class="character-name">{{ char.name }}</span>
                <span class="character-role">{{ char.role }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 「换一批 / 显示全部」按钮组：所有分类都显示（只要批次>1），位置在推荐角色区与热门聊天室区之间，靠右
             用 .featured-actions-row 包装一层：内层 flex + justify-content:flex-end 把按钮推到右端 -->
        <div v-if="featuredTotalBatches > 1" class="featured-actions-row">
          <div class="featured-actions featured-actions-between">
            <button
              type="button"
              class="shuffle-batch-btn"
              :disabled="featuredTotalBatches <= 1 || showAllFeatured"
              @click="shuffleFeaturedBatch"
              :title="featuredTotalBatches <= 1 ? '当前只有 1 批' : '换一批'"
            >
              <span class="shuffle-batch-label">换一批</span>
              <span class="shuffle-batch-count">{{ currentFeaturedBatch + 1 }}/{{ featuredTotalBatches }}</span>
            </button>
            <button
              type="button"
              class="show-all-btn"
              @click="toggleShowAllFeatured"
            >{{ showAllFeatured ? '收起' : '显示全部' }}</button>
          </div>
        </div>

        <!-- 热门聊天室 -->
        <section class="rooms-section">
          <div class="section-header">
            <h2 class="section-title">
              热门聊天室
              <span v-if="hotRooms.length > 0" class="preset-total-hint">（{{ hotRooms.length }} 个热门聊天室）</span>
            </h2>
          </div>

          <div v-if="hotRoomsLoading && hotRooms.length === 0" class="featured-loading">
            <div class="loading-spinner"></div>
          </div>
          <div v-else-if="hotRooms.length === 0" class="featured-empty">
            暂无热门聊天室
          </div>

          <!-- 聊天室卡片网格 -->
          <div v-else class="room-grid">
            <div
              v-for="room in hotRooms"
              :key="room.id"
              class="room-card"
              :class="{ 'is-entering': enteringHotRoomId === room.id }"
              @click="enterRoom(room.id)"
            >
              <!-- 封面图：本地路径加 cache-buster，避免 WebConfig 1 小时缓存住旧图 -->
              <div class="room-cover">
                <img :src="room.cover" :alt="room.title" class="cover-img" />
                <div class="cover-overlay"></div>
              </div>

              <!-- 聊天室信息 -->
              <div class="room-body">
                <h3 class="room-title">{{ room.title }}</h3>

                <!-- 参与者头像栈：按 room.participants[i] 名字在 presetAvatarMap 里查真实头像。
                     查不到时降级到 DiceBear 占位（仅当 hotRooms.json 写了不在 presets 里的虚构名时才会走）。 -->
                <div class="room-participants">
                  <div class="avatar-stack">
                    <img
                      v-for="(name, i) in room.participants.slice(0, 3)"
                      :key="i"
                      :src="presetAvatarMap.get(name) || `https://api.dicebear.com/7.x/personas/svg?seed=${encodeURIComponent(name)}&backgroundColor=c0aede`"
                      :alt="name"
                      class="participant-avatar"
                      :style="{ zIndex: 3 - i }"
                    />
                  </div>
                  <span class="participant-names">{{ room.participants.slice(0, 3).join('、') }}</span>
                </div>

                <!-- 最新消息 -->
                <div class="latest-message">
                  <span class="message-sender">{{ room.latestMessage.sender }}:</span>
                  <span class="message-text">{{ room.latestMessage.text }}</span>
                </div>
              </div>

              <!-- 进入中遮罩：避免用户连点时多次触发并发 clone -->
              <div v-if="enteringHotRoomId === room.id" class="room-card-loading">
                <span class="loading-spinner"></span>
                <span>正在创建聊天室...</span>
              </div>
            </div>
          </div>
        </section>
      </template>
    </main>


    <!-- 创建聊天室弹窗 -->
    <CreateRoomModal
      :show="showCreateModal"
      @close="showCreateModal = false"
      @created="handleRoomCreated"
    />

    <!-- 创建角色弹窗 -->
    <CreateCharacterModal
      :show="showCreateCharacterModal"
      :context="createCharacterRoomId ? 'room' : 'character-library'"
      :room-id="createCharacterRoomId"
      @close="showCreateCharacterModal = false"
      @created="handleCharacterCreated"
      @added-to-room="handleAddedToRoom"
    />

    <!-- 编辑角色弹窗：房间内编辑已加入房间的成员角色。
         本 PR 仅迁移「角色库」路径下的编辑到独立路由 /characters/edit/:id，
         房间上下文内的编辑仍走弹窗模式（保持与房间内角色不可分离的交互上下文）。 -->
    <CreateCharacterModal
      v-if="showEditCharacterModal"
      :show="showEditCharacterModal"
      mode="edit"
      :character="editingCharacter"
      @close="closeEditCharacterModal"
      @updated="handleCharacterUpdated"
    />

    <!-- 邀请成员弹窗 -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showInviteModal" class="invite-modal-overlay" @click.self="showInviteModal = false">
          <div class="invite-modal">
            <header class="invite-modal-header">
              <h2>邀请成员</h2>
              <button class="modal-close-btn" @click="showInviteModal = false">
                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </header>
            <div class="invite-modal-body">
              <p class="invite-hint">输入用户名或邮箱邀请用户加入当前聊天室</p>
              <input
                v-model="inviteKeyword"
                type="text"
                placeholder="用户名或邮箱"
                class="invite-input"
                @keyup.enter="handleInviteMember"
              />
              <p v-if="inviteError" class="invite-error">{{ inviteError }}</p>
            </div>
            <footer class="invite-modal-footer">
              <button class="invite-cancel-btn" @click="showInviteModal = false">取消</button>
              <button class="invite-submit-btn" @click="handleInviteMember" :disabled="inviteLoading || !inviteKeyword.trim()">
                {{ inviteLoading ? '邀请中...' : '邀请' }}
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 删除聊天室确认 -->
    <ConfirmDialog
      :show="showDeleteRoomConfirm"
      title="删除聊天室"
      :message="`确定要删除聊天室「${deletingRoomName}」吗？此操作不可恢复，房间内所有消息也会被一起删除。`"
      confirm-text="删除"
      cancel-text="取消"
      :loading="deletingRoomLoading"
      @confirm="confirmDeleteRoom"
      @cancel="showDeleteRoomConfirm = false"
    />

    <!-- 自定义场景弹窗（创建/编辑） -->
    <CustomScenarioModal
      :show="showCustomScenarioModal"
      :scenario="editingScenario"
      @close="showCustomScenarioModal = false"
      @saved="onCustomScenarioSaved"
    />

    <!-- 删除用户私有场景确认 -->
    <ConfirmDialog
      :show="!!deletingScenarioId"
      title="删除场景"
      :message="`确定删除自定义场景「${deletingScenarioTitle}」？历史房间不受影响。`"
      confirm-text="删除"
      cancel-text="取消"
      :loading="deletingScenarioLoading"
      danger
      @confirm="confirmDeleteCustomScenario"
      @cancel="deletingScenarioId = null"
    />
  </div>
</template>

<style scoped>
/* ===== Page Layout ===== */
.page-layout {
  display: grid;
  grid-template-columns: var(--global-sidebar-width) 1fr;
  height: 100vh;             /* 关键：固定高度，让子元素 flex 布局能算出来 */
  background: var(--app-bg);
  opacity: 0;
  overflow: hidden;
  transition: opacity 0.4s ease, background-color 0.25s ease, grid-template-columns 0.22s ease;
}

.page-layout.mounted {
  opacity: 1;
}

/* ===== Left Sidebar ===== */
.sidebar {
  background: var(--sidebar-bg);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  /* 缩 padding 到 0.75rem：原 1rem + 侧边栏缩到 220px 时，「+ 创建」按钮（160px）
     + 左右 padding 共 192px，加边距仍能 fit；原 1rem 会让内容区只有 188px 太挤 */
  padding: 0.75rem;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: visible;
  transition: background-color 0.25s ease, border-color 0.25s ease;
  width: var(--global-sidebar-width);
  min-width: var(--global-sidebar-width);
}

.sidebar-collapse-btn {
  position: absolute;
  top: 1rem;
  right: -14px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid var(--border-color);
  background: var(--card-bg);
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.sidebar-collapse-btn:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
  transform: scale(1.1);
}

.sidebar .logo-text,
.sidebar .nav-label,
.sidebar .recent-chat-text,
.sidebar .user-info-text {
  transition: opacity 0.2s ease;
}

.page-layout.global-collapsed .sidebar .logo-text,
.page-layout.global-collapsed .sidebar .nav-label,
.page-layout.global-collapsed .sidebar .recent-chat-text,
.page-layout.global-collapsed .sidebar .user-info-text {
  opacity: 0;
  width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.page-layout.global-collapsed .sidebar {
  padding: 0.75rem 0.5rem;
}

.page-layout.global-collapsed .sidebar-brand {
  justify-content: center;
  padding: 0.25rem;
}

.page-layout.global-collapsed .create-dropdown-wrapper {
  width: 100%;
}

.page-layout.global-collapsed .create-btn {
  width: 48px;
  height: 48px;
  padding: 0;
  justify-content: center;
  margin: 0 auto 0.75rem;
}

.page-layout.global-collapsed .create-btn span {
  display: none;
}

.page-layout.global-collapsed .nav-item {
  justify-content: center;
  align-items: center;
  padding: 0.6rem;
}

/* 折叠态：彻底从布局中移除 label（默认规则是 opacity:0 + width:0，但 height 还在，
   让 flex 子项仍占一行的 vertical space，把图标挤到顶部） */
.page-layout.global-collapsed .nav-item .nav-label {
  display: none;
}

.page-layout.global-collapsed .nav-emoji {
  margin: 0;
}

/* 折叠态下的选中态：缩成 40x40 深色方块，只露图标。
   默认 .nav-item.active 是 160px 宽 + padding 1rem，会撑爆 72px 的折叠侧栏；
   这里覆盖成 40x40 与「+ 创建」按钮（48x48）保持视觉节奏一致。 */
.page-layout.global-collapsed .nav-item.active {
  width: 40px;
  height: 40px;
  padding: 0;
  margin: 0 auto;
  border-radius: 8px;
}

.page-layout.global-collapsed .recent-chats {
  display: none;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0.25rem;
  margin-bottom: 0.875rem;
}

.sidebar-brand-logo {
  width: 32px;
  height: 32px;
  object-fit: contain;
  border-radius: 8px;
  flex-shrink: 0;
}

.logo-text {
  font-size: 22px;
  font-weight: 800;
  font-family: Inter, SF Pro Display, PingFang SC, sans-serif;
  line-height: 1;
  color: var(--text-primary);
  letter-spacing: -0.5px;
  transition: color 0.25s ease;
}

/* Create Button - Compact */
.create-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.4rem;
  width: 160px;
  height: 42px;
  background: var(--button-bg);
  border: none;
  border-radius: 14px;
  color: var(--button-text);
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-bottom: 1rem;
}

.create-btn:hover {
  opacity: 0.85;
}

.create-btn svg {
  opacity: 0.9;
}

/* Create Dropdown Wrapper —— Teleport 后这个容器本身没有 absolute 子节点，
   所以 position: relative 已无意义，保留 wrapper 仅为模板结构清晰。 */
.create-dropdown-wrapper {
  display: inline-block;
  width: fit-content;
  margin-bottom: 1rem;
}

.create-dropdown-menu {
  position: fixed;
  width: 150px;
  background: #1f1f1f;
  border-radius: 16px;
  padding: 6px;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.16);
  z-index: 9999;
}

.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 42px;
  padding: 0 12px;
  background: transparent;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
}

.dropdown-item:hover {
  background: rgba(255, 255, 255, 0.06);
}

.dropdown-icon {
  font-size: 16px;
  width: 20px;
  text-align: center;
}

.dropdown-label {
  font-size: 0.85rem;
  font-weight: 500;
  color: #fafafa;
}

/* Dropdown animation */
.dropdown-enter-active,
.dropdown-leave-active {
  transition: all 0.15s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateX(-4px);
}

.dropdown-enter-to,
.dropdown-leave {
  opacity: 1;
  transform: translateX(0);
}

/* Navigation - Minimal & Light */
.nav-menu {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  margin-bottom: 1rem;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.65rem;
  border-radius: 8px;
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 0.875rem;
  font-weight: 400;
  /* 不加 transition：激活态切换时 background/color 渐变会让"原激活项先变色再切到新项"
     出现一帧瞬时可见的中间态，看起来像"变大闪一下"。瞬变更干脆。 */
}

.nav-item:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

/* 选中态：纯深色实心块 + 白字 + 加粗。
   width 锁 160px，跟顶部「+ 创建」按钮完全对齐，避免短文字（发现）显得比按钮窄一截。 */
.nav-item.active {
  background: var(--color-space, #111827);
  color: #ffffff;
  font-weight: 500;
  width: 160px;
  padding: 0.5rem 1rem;
}

.nav-item.active:hover {
  /* 选中态再 hover 仍保持深色块，只在颜色上轻微提亮，
     避免「选中态被 hover 覆盖成浅色」导致视觉跳动 */
  background: #1f2937;
  color: #ffffff;
}

.nav-emoji {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* Recent Chats - Minimal List */
.recent-chats {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 0.75rem;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.25rem;
  margin-bottom: 0.5rem;
}

.section-title {
  font-size: 0.7rem;
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--text-muted);
  text-transform: uppercase;
  transition: color 0.25s ease;
}

.chat-list {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.chat-item {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.5rem 0.5rem;
  border-radius: 8px;
  text-decoration: none;
  transition: all 0.15s ease;
}

.chat-item:hover {
  background: var(--bg-primary);
}

.chat-avatar {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  flex-shrink: 0;
  overflow: hidden;
  /* 显式定位上下文:网格模式内部 cells 用 absolute */
  position: relative;
}

/* 多角色房间:与「我的聊天」列表一致的 3×3 网格,最多显示 9 个角色头像 */
.chat-avatar.is-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  grid-template-rows: repeat(3, 1fr);
  gap: 1px;
  padding: 1px;
  background: var(--bg-primary);
}

.chat-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.chat-avatar-cell {
  position: relative;
  border-radius: 2px;
  overflow: hidden;
  background: var(--bg-secondary, #e2e8f0);
  display: flex;
  align-items: center;
  justify-content: center;
}
.chat-avatar-cell img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.chat-avatar-fallback {
  font-size: 0.55rem;
  font-weight: 700;
  color: var(--text-muted, #64748b);
}

.chat-avatar-placeholder {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-muted);
}

.chat-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.05rem;
}

.chat-name {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.chat-preview {
  font-size: 0.7rem;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

/* ===== Main Content ===== */
.main-content {
  padding: 1.5rem 2rem;
  /* 发现/角色库/场景 tab 在这里自己滚动；
     my-rooms tab 由下方 :has 分支接管，让 .rooms-list-scroll 内部滚 */
  overflow-y: auto;
  min-height: 0;
}

.main-content:has(.rooms-chat-shell) {
  padding: 0;
  overflow: hidden;          /* 关键：禁止外层滚动 */
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* Header */
.content-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2rem;
}

/* 发现页 header：让搜索框紧贴标题右边，并压缩下方的留白（原 .content-header 的 2rem 太大） */
.content-header.discover-header {
  justify-content: flex-start;
  gap: 1rem;
  margin-bottom: 1rem; /* 从 2rem 压缩到 1rem，缩短搜索框到"推荐角色"标题之间的空白 */
}

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  padding: 0.75rem 1.25rem;
  background: #f1f2f4; /* 浅灰胶囊背景，参考用户提供的截图样式 */
  border: none; /* 去掉黑色边框，改用纯背景色 */
  border-radius: 999px;
  width: 360px;
  transition: all 0.25s ease;
  box-shadow: none;
}

.search-bar-compact {
  width: 300px;
  padding: 0.55rem 1rem;
  gap: 0.5rem;
}

.search-bar:focus-within {
  background: #e8eaed; /* focus 时背景略深一点，给点反馈 */
  box-shadow: none; /* 去掉黑色 outline */
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 0.95rem;
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: #9ca3af; /* 浅灰 placeholder，匹配截图风格 */
  font-weight: 400;
}

/* Featured Section */
.featured-section {
  margin-bottom: 1rem; /* 从 2rem 压到 1rem：缩短"换一批"按钮到热门聊天室标题之间的空白 */
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.see-all {
  font-size: 0.85rem;
  font-weight: 500;
  color: #18181b;
  text-decoration: none;
}

.see-all:hover {
  text-decoration: underline;
}

/* 当前批次范围提示（如「亚里士多德 → 海森堡」）：标题与右侧操作按钮之间，
   让"换一批"的视觉变化一眼可辨——以前两批都是 18 个相似历史人物，肉眼很难区分。 */
.batch-range {
  margin-left: auto;
  margin-right: 0.75rem;
  font-size: 0.8rem;
  color: rgba(24, 24, 27, 0.55);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 推荐角色 section 标题右侧操作区：把"查看全部"和"换一批"统一放在同一槽位，
   互斥显示——折叠态展示"查看全部"入口，展开态展示"换一批"按钮。
   .section-actions 是右对齐的容器，避免修改 .section-header 的 flex 布局。 */
.section-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

/* 「显示全部 / 收起」按钮：与 .shuffle-batch-btn 同款视觉（一对成对的次要按钮），
   让用户清楚这是与"换一批"并列的另一种视图切换，不是主操作。 */
.show-all-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0.75rem;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary, #18181b);
  background: transparent;
  border: 1px solid var(--border-color, rgba(24, 24, 27, 0.08));
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.show-all-btn:hover {
  background: var(--input-bg, rgba(24, 24, 27, 0.04));
  border-color: var(--text-secondary, rgba(24, 24, 27, 0.16));
}

/* 「换一批」按钮：与 .see-all 视觉重量相近（链接感），但带圆角和轻微背景便于识别为可点击操作。
   右上角的 1/3 是当前/总批数提示，让用户清楚知道这是"多批之一"，避免以为内容是无限滚动。 */
.shuffle-batch-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0.75rem;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-primary, #18181b);
  background: var(--input-bg, rgba(24, 24, 27, 0.04));
  border: 1px solid var(--border-color, rgba(24, 24, 27, 0.08));
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.shuffle-batch-btn:hover:not(:disabled) {
  background: var(--card-bg, rgba(24, 24, 27, 0.08));
  border-color: var(--text-secondary, rgba(24, 24, 27, 0.16));
}
.shuffle-batch-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.shuffle-batch-count {
  font-size: 0.75rem;
  color: var(--text-secondary, rgba(24, 24, 27, 0.55));
  font-variant-numeric: tabular-nums;
}

/* Featured Grid — 3 rows × 6 columns of preset characters (18 per batch) */
.featured-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 1rem;
  padding: 0.25rem 0 0; /* 上下 padding 压缩：原本 0.5rem 0 让按钮离网格太远 */
}
@media (max-width: 1100px) {
  .featured-grid { grid-template-columns: repeat(4, 1fr); }
}
@media (max-width: 700px) {
  .featured-grid { grid-template-columns: repeat(3, 1fr); }
}

.featured-loading {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 2rem;
}

.featured-empty {
  text-align: center;
  padding: 2rem;
  color: var(--text-muted);
}

.character-card {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: var(--card-bg);
  border-radius: 12px;
  border: 1px solid var(--border-color);
  cursor: pointer;
  transition: all 0.25s ease;
  min-width: 0;
}

.character-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  border-color: #27272a;
}

/* startChat 进行中：所有推荐卡片置灰，提示用户等待；防止用户重复点击造成请求堆积 */
.character-card.is-loading {
  opacity: 0.55;
  cursor: progress;
  pointer-events: none;
  transform: none;
  box-shadow: none;
}

.character-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.character-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--border-color);
  transition: border-color 0.25s ease;
}

/* 推荐角色头像兜底：维基图片 404 时显示首字母渐变圆，与普通头像同形 */
.character-avatar-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--color-navy) 0%, var(--color-navy-light) 100%);
  color: var(--color-gold-light);
  font-family: 'Playfair Display', serif;
  font-weight: 600;
  font-size: 1.1rem;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
}

/* 真实图片盖在占位符上：绝对定位 + 同尺寸,
   这样图片 lazy load 之前用户看到首字母渐变,加载完无缝替换,布局零抖动 */
.character-avatar-img {
  position: absolute;
  top: 0;
  left: 0;
}

.online-indicator {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #10B981;
  border: 2px solid white;
}

.character-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.character-name {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-primary);
  transition: color 0.25s ease;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.character-role {
  font-size: 0.7rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

/* Category Tabs Row —— 分类标签 + 右侧操作按钮同一行布局。
   .category-tabs 靠 flex:1 占据剩余宽度并允许横向滚动，
   .featured-actions 收缩到右侧(只占内容宽度)，chips 滚动时操作按钮始终固定。
   整体保留 0.75rem 下方间距(原 .category-tabs 的 margin-bottom 提到这里),
   保证 chips 网格之间有视觉缓冲。 */
.category-tabs-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

/* Category Tabs */
.category-tabs {
  display: flex;
  gap: 0.5rem;
  flex: 1 1 auto;
  min-width: 0; /* 配合 flex:1 让 overflow-x:auto 生效,防止被内容撑爆 */
  overflow-x: auto;
  padding: 0.25rem 0;
  scrollbar-width: none;
}

/* 「换一批 / 显示全部」按钮组：放在推荐角色区与热门聊天室区之间，靠右显示。
   .main-content 是 block 容器，margin-left:auto 不会生效，所以用一层 .featured-actions-row
   做 flex 容器，再让内部 .featured-actions 靠 justify-content:flex-end 推到右端。 */
.featured-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 0 0 auto; /* 不让按钮组被压缩,始终占内容宽度 */
  margin-top: -0.25rem; /* 紧贴推荐角色网格下方（抵消 .featured-grid 新的 padding-top:0.25rem） */
  margin-bottom: 0.25rem; /* 与下方热门聊天室 section-header 拉近，从 0.75rem 压到 0.25rem */
  padding-right: 0.25rem;
}
.featured-actions-row {
  display: flex;
  justify-content: flex-end; /* 把内部按钮组推到行右 */
}

.category-tabs::-webkit-scrollbar {
  display: none;
}

.category-chip {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.5rem 0.9rem;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
}

.category-chip:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.category-chip.active {
  background: #EEF2FF;
  border-color: #27272a;
  color: #18181b;
}

.chip-emoji {
  font-size: 0.95rem;
}

/* Rooms Section */
.rooms-section {
  margin-bottom: 2rem;
}

.hot-badge {
  font-size: 1.2rem;
}

/* 推荐角色标题旁的默认角色数提示：
   用绿色 (#10B981) 与 ATHLETE 分类 chip 同色——是项目里既有的"积极/数量充足"语义色。
   字号比主标题小一档，避免抢走"推荐角色"主标题的视觉重量。 */
.preset-total-hint {
  font-size: 0.85rem;
  font-weight: 500;
  color: #10B981;
  margin-left: 0.5rem;
  vertical-align: middle;
}

/* Room Grid */
.room-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.25rem;
}

@media (max-width: 1400px) {
  .room-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .room-grid {
    grid-template-columns: 1fr;
  }
}

/* Room Card */
.room-card {
  position: relative;
  background: var(--card-bg);
  border-radius: 16px;
  border: 1px solid var(--border-color);
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s ease;
}

.room-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  border-color: #27272a;
}

.room-cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
}

.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.room-card:hover .cover-img {
  transform: scale(1.05);
}

.cover-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.5) 100%);
}

.room-body {
  padding: 1rem;
}

.room-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.75rem;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  transition: color 0.25s ease;
}

.room-participants {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin-bottom: 0.75rem;
}

.avatar-stack {
  display: flex;
}

.participant-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid white;
  margin-left: -8px;
  object-fit: cover;
}

.participant-avatar:first-child {
  margin-left: 0;
}

.participant-names {
  font-size: 0.8rem;
  color: var(--text-secondary);
  transition: color 0.25s ease;
}

.latest-message {
  display: flex;
  gap: 0.4rem;
  padding: 0.6rem 0.75rem;
  background: var(--panel-bg);
  border-radius: 8px;
  margin-bottom: 0.75rem;
  font-size: 0.8rem;
  transition: background-color 0.25s ease;
}

.message-sender {
  font-weight: 600;
  color: #18181b;
}

.message-text {
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 暗色模式覆盖：.message-sender / .participant-names / .stat 默认近黑，
   与卡片深背景几乎融合导致不可见。统一提亮成 var(--text-secondary) 这一档，
   既保证对比度又不抢走标题的视觉重量。 */
.dark .message-sender {
  color: #f1f5f9;
}
.dark .participant-names {
  color: #cbd5e1;
}
.dark .stat {
  color: #cbd5e1;
}

.room-stats {
  display: flex;
  gap: 1rem;
}

/* 热门聊天室卡片进入中的遮罩：避免用户连点时多次触发并发 clone。 */
.room-card.is-entering {
  cursor: wait;
  pointer-events: none;
  opacity: 0.7;
}

.room-card-loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 0.85rem;
  border-radius: inherit;
  z-index: 2;
}

.loading-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.stat {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 0.8rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
}

/* ===== Responsive ===== */
@media (max-width: 768px) {
  .page-layout {
    grid-template-columns: 1fr;
  }

  .sidebar {
    display: none;
  }

  .main-content {
    padding: 1rem;
  }

  .search-bar {
    width: 100%;
  }

  .content-header {
    flex-direction: column;
    gap: 1rem;
    align-items: stretch;
  }

  /* 角色库搜索框:窄屏下与创建按钮竖排,搜索框占满 */
  .header-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .search-input {
    width: 100%;
  }
}

/* ===== Character Library Styles ===== */
/* header-actions: 搜索框 + 创建按钮的容器,保持与原 header 一致的右对齐布局 */
.header-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

/* 搜索框:圆角 + 浅灰背景 + 聚焦时强调边框,跟现代 SaaS 列表页的视觉惯例一致 */
.search-box {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 0.75rem;
  width: 1rem;
  height: 1rem;
  color: var(--text-muted);
  pointer-events: none;
}

.search-input {
  width: 240px;
  padding: 0.625rem 2.25rem 0.625rem 2.25rem;
  font-size: 0.9rem;
  color: var(--text-primary);
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.search-input::placeholder {
  color: var(--text-muted);
}

.search-input:focus {
  border-color: #18181b;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.12);
}

/* 清除按钮:绝对定位在输入框右侧,有内容时才渲染 */
.search-clear {
  position: absolute;
  right: 0.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--text-muted);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.search-clear svg {
  width: 0.875rem;
  height: 0.875rem;
}

.search-clear:hover {
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-primary);
}

.create-btn-large {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1.25rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  border: none;
  border-radius: 10px;
  color: white;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.create-btn-large:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.3);
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  text-align: center;
}

.empty-icon {
  font-size: 4rem;
  margin-bottom: 1.5rem;
}

.empty-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.5rem;
}

.empty-desc {
  font-size: 1rem;
  color: var(--text-muted);
  margin-bottom: 2rem;
}

.empty-btn {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.875rem 1.5rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  border: none;
  border-radius: 12px;
  color: white;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.empty-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(24, 24, 27, 0.35);
}

/* ===== Scenarios grid (when isScenariosView) ===== */
.page-subtitle {
  font-size: 0.875rem;
  color: var(--text-secondary, #6b7280);
  margin: 0;
}

/* 场景分区样式（保留兼容钩子，目前未使用） */
.scenarios-section {
  margin-bottom: 1.5rem;
}

.scenarios-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.25rem;
}

@media (max-width: 1400px) {
  .scenarios-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .scenarios-grid {
    grid-template-columns: 1fr;
  }
}

/* 卡片外层 wrap：用于承载右上角 actions（编辑/删除按钮） */
.scenario-card-wrap {
  position: relative;
}

/* 场景卡片：与 .room-card 同款（16:9 渐变封面 + body + hover 抬起） */
.scenario-card {
  position: relative;
  background: var(--card-bg);
  border-radius: 16px;
  border: 1px solid var(--border-color);
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s ease;
  text-align: left;
  font-family: inherit;
  width: 100%;
  padding: 0;
  color: inherit;
}
.scenario-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
  border-color: #27272a;
}

/* 16:9 封面:有 cover 用图 + 暗遮罩 + 标题压底,无 cover 用渐变 + emoji 居中 */
.scenario-cover {
  position: relative;
  aspect-ratio: 16 / 9;
  overflow: hidden;
}
.scenario-cover.has-image {
  display: block;
}
.scenario-cover.is-emoji {
  display: flex;
  align-items: center;
  justify-content: center;
}
.cover-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}
.scenario-card:hover .cover-img {
  transform: scale(1.05);
}
/* 暗遮罩:仅在有图时显示,让底部白字标题可读 */
.cover-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, rgba(0,0,0,0) 40%, rgba(0,0,0,0.65) 100%);
}
/* 标题压底:仅在有图时显示,叠在 cover 内左下 */
.scenario-cover-title-wrap {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 0.75rem 1rem;
  z-index: 1;
}
.scenario-cover-title {
  font-size: 1rem;
  font-weight: 600;
  color: #ffffff;
  margin: 0;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.5);
}
/* emoji 居中:仅在无图时显示 */
.scenario-cover-emoji {
  font-size: 64px;
  line-height: 1;
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.12));
  transition: transform 0.3s ease;
}
.scenario-card:hover .scenario-cover-emoji {
  transform: scale(1.08);
}

/* 卡片正文：与 .room-body 间距一致 */
.scenario-body {
  padding: 1rem;
}
.scenario-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 0.4rem;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  transition: color 0.25s ease;
}
.scenario-desc {
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin: 0 0 0.6rem;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 示例片段区：与 .latest-message 同款（panel 背景 + padding + 单行 ellipsis） */
.scenario-quote {
  display: flex;
  gap: 0.4rem;
  padding: 0.6rem 0.75rem;
  background: var(--panel-bg);
  border-radius: 8px;
  font-size: 0.8rem;
  line-height: 1.4;
  transition: background-color 0.25s ease;
}
.scenario-quote-text {
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 暗色模式覆盖：与 .latest-message / .message-sender 暗色处理同档 */
.dark .scenario-quote-text {
  color: #cbd5e1;
}

.scenario-actions {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.15s ease;
  z-index: 1;
}
.scenario-card-wrap.is-user:hover .scenario-actions {
  opacity: 1;
}
.scenario-action-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--card-bg, #ffffff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: inherit;
  padding: 0;
}
.scenario-action-btn:hover {
  background: #f3f4f6;
  border-color: #0f172a;
  transform: scale(1.05);
}
.scenario-action-danger:hover {
  background: rgba(239, 68, 68, 0.1);
  border-color: #ef4444;
}

/* 「+ 自定义场景」按钮：与「场景」标题同行右侧贴近（不再悬浮右上角） */
.content-header-text {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 1rem;
}

/* 按钮尺寸调小：之前是 page-title 高度的 2 倍，放在标题旁边会喧宾夺主 */
.btn-create-scenario {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.5rem 1rem;
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 0.875rem;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
  flex-shrink: 0;
}
.btn-create-scenario:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.25);
}
.btn-create-icon {
  font-size: 1.1rem;
  line-height: 1;
  font-weight: 600;
}

/* Scenario template-preview modal */
.scenario-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.scenario-modal {
  width: min(720px, 100%);
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid rgba(226, 232, 240, 0.95);
  border-radius: 20px;
  box-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  overflow: hidden;
  animation: pop 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}
.scenario-modal-header {
  padding: 20px 28px 16px;
  text-align: center;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}
.scenario-modal-headline {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.scenario-modal-emoji { font-size: 32px; line-height: 1; }
.scenario-modal-title {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 4px;
}
.scenario-modal-desc {
  font-size: 13px;
  color: #64748b;
  margin: 0;
  line-height: 1.5;
}
.scenario-modal-body {
  padding: 20px 32px;
  overflow-y: auto;
  flex: 1;
}
.scenario-modal-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 8px;
}
.scenario-modal-template {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #334155;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 14px 16px;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  max-height: 320px;
  overflow-y: auto;
}

/* 提示词模板的编辑模式（textarea） */
.scenario-modal-prompt-editor {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace !important;
  font-size: 12px !important;
  line-height: 1.6;
  resize: vertical;
  min-height: 280px;
  white-space: pre-wrap;
}

.scenario-modal-input {
  width: 100%;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
  color: #0f172a;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 12px 14px;
  margin: 0 0 16px;
  resize: vertical;
  min-height: 72px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.scenario-modal-input:focus {
  border-color: #0f172a;
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
}
.scenario-modal-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.scenario-modal-input-tall {
  resize: vertical;
  min-height: 100px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
}
.scenario-modal-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #64748b;
}
.scenario-modal-char-name {
  padding: 10px 14px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

/* 简历上传区 */
.resume-dropzone {
  border: 2px dashed #cbd5e1;
  border-radius: 12px;
  padding: 22px 16px;
  text-align: center;
  background: #f8fafc;
  transition: all 0.15s ease;
  cursor: pointer;
}
.resume-dropzone:hover { border-color: #64748b; background: #f1f5f9; }
.resume-dropzone.is-dragging { border-color: #0f172a; background: #e2e8f0; }
.resume-dropzone.is-loading { opacity: 0.6; pointer-events: none; }
.resume-dropzone-icon { font-size: 28px; margin-bottom: 6px; }
.resume-dropzone-text { font-size: 13px; color: #475569; margin: 2px 0; }
.resume-dropzone-btn {
  display: inline-block;
  margin: 8px 0 6px;
  padding: 6px 14px;
  background: #0f172a;
  color: #fff;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.resume-dropzone-btn:hover { background: #1e293b; }
.resume-dropzone-meta { font-size: 11px; color: #94a3b8; margin-top: 4px; }
.resume-dropzone-loading { font-size: 13px; color: #475569; padding: 12px; }

.resume-uploaded {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 10px;
  font-size: 13px;
}
.resume-uploaded-icon { font-size: 16px; }
.resume-uploaded-name { font-weight: 600; color: #0f172a; flex: 1; word-break: break-all; }
.resume-uploaded-meta { color: #64748b; font-size: 12px; }
.resume-clear-btn {
  background: transparent;
  border: 1px solid #e2e8f0;
  color: #64748b;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.resume-clear-btn:hover:not(:disabled) { background: #fee2e2; color: #b91c1c; border-color: #fecaca; }
.scenario-modal-error-inline { color: #dc2626; font-size: 12px; margin: 6px 0 0; }

/* JD 截图识别区 */
.jd-image-dropzone {
  margin-top: 6px;
  padding: 12px 14px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  text-align: center;
  font-size: 12px;
  color: #475569;
  background: #f8fafc;
  transition: all 0.15s ease;
  cursor: pointer;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 6px;
}
.jd-image-dropzone:hover { border-color: #64748b; background: #f1f5f9; }
.jd-image-dropzone.is-loading { opacity: 0.6; pointer-events: none; }
.jd-image-btn {
  display: inline-block;
  padding: 3px 10px;
  background: #0f172a;
  color: #fff;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.jd-image-btn:hover { background: #1e293b; }
.jd-image-hint { color: #94a3b8; font-size: 11px; flex-basis: 100%; }
.scenario-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 32px 20px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
}
.scenario-modal-error {
  margin: 0;
  padding: 0 32px 20px;
  color: #dc2626;
  font-size: 13px;
}
.btn { height: 42px; padding: 0 18px; border-radius: 14px; font-size: 14px; font-weight: 600; border: 1px solid transparent; cursor: pointer; transition: all 0.15s ease; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-secondary { background: #f1f5f9; border-color: #e2e8f0; color: #64748b; }
.btn-secondary:hover:not(:disabled) { background: #e2e8f0; color: #1e293b; }
.btn-primary { background: #0f172a; color: #fff; font-weight: 700; box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18); }
.btn-primary:hover:not(:disabled) { opacity: 0.92; }
@keyframes pop { from { opacity: 0; transform: scale(0.96) translateY(4px); } to { opacity: 1; transform: scale(1) translateY(0); } }
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.character-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1.25rem;
}

/* 角色卡片用 2 列 × 1 行 Grid:
     - 左列固定 56px 头像
     - 右列占剩余空间放角色名 + 描述
   用 Grid 而非 Flex row 的原因:
     - 列宽约束更直观(第一列 = 56px,第二列 = 1fr),不用 flex 容器 + flex-shrink
     - 日后想加新元素(例如"创建于"再回来)直接扩 grid-template-areas,不用改结构
     - column-gap 表达"两列之间间距",比 margin/gap 在子项上更符合布局语义 */
.character-card-item {
  display: grid;
  grid-template-columns: 56px 1fr;
  grid-template-areas: "avatar info";
  column-gap: 1rem;
  align-items: start;
  padding: 1.25rem;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 16px;
  transition: all 0.25s ease;
}

.character-card-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  border-color: #27272a;
}

.character-card-item .character-avatar {
  grid-area: avatar;
  width: 56px;
  height: 56px;
  border-radius: 12px;
  overflow: hidden;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.character-card-item .character-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.character-card-item .avatar-placeholder {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-muted);
}

.character-card-item .character-info {
  grid-area: info;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}

.character-card-item .character-name {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
}

.character-card-item .character-tagline {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ===== My Rooms Styles ===== */
.page-subtitle {
  font-size: 0.9rem;
  color: var(--text-muted);
  margin-top: 0.25rem;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 4rem 2rem;
  gap: 1rem;
  color: var(--text-muted);
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--border-color);
  border-top-color: var(--text-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.my-room-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.my-room-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 18px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.my-room-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.1);
  border-color: #27272a;
}

.my-room-item:hover .room-enter-hint {
  opacity: 1;
  transform: translateX(0);
}

.room-avatar-group {
  display: flex;
  align-items: center;
}

.room-char-avatar {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  overflow: hidden;
  background: var(--bg-primary);
  border: 2px solid var(--card-bg);
  margin-left: -10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.room-char-avatar:first-child {
  margin-left: 0;
}

.room-char-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.room-char-avatar .avatar-placeholder {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-muted);
}

.room-char-avatar.empty {
  background: linear-gradient(135deg, #18181b 0%, #3f3f46 100%);
}

.room-char-avatar.empty span {
  font-size: 1.2rem;
}

.room-main {
  flex: 1;
  min-width: 0;
}

.room-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.room-name {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.room-time {
  font-size: 0.75rem;
  color: var(--text-muted);
  white-space: nowrap;
}

.room-topic {
  font-size: 0.85rem;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.room-meta {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.room-enter-hint {
  opacity: 0;
  transform: translateX(-8px);
  transition: all 0.2s ease;
  color: var(--text-muted);
  flex-shrink: 0;
}

/* Dark mode for my rooms */
.dark .my-room-item:hover {
  border-color: rgba(71, 85, 105, 0.85);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.3);
}

/* ===== My Rooms Three-Column Layout ===== */
.rooms-chat-shell {
  height: 100%;
  flex: 1;                    /* 占满 main-content 高度 */
  min-height: 0;
  display: grid;
  grid-template-columns: var(--room-list-width) minmax(0, 1fr) var(--role-panel-width);
  background: #f6f7fb;
  overflow: hidden;
  transition: grid-template-columns 0.22s ease;
}

.dark .rooms-chat-shell {
  background: #020617;
}

/* Left: Room List Panel */
.rooms-list-panel {
  min-width: 0;
  background: #ffffff;
  border-right: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.dark .rooms-list-panel {
  background: #0f172a;
  border-right-color: rgba(71, 85, 105, 0.85);
}

.page-layout.room-list-collapsed .rooms-list-panel {
  border-right: none;
}

.room-list-toggle-btn {
  position: absolute;
  top: 50%;
  right: -14px;
  transform: translateY(-50%);
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #0f172a;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.room-list-toggle-btn:hover {
  background: #f1f5f9;
  transform: translateY(-50%) scale(1.1);
}

.dark .room-list-toggle-btn {
  background: #1e293b;
  color: #e2e8f0;
  border-color: rgba(71, 85, 105, 0.85);
}

.icon-close-room-list-button {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.icon-close-room-list-button:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.dark .icon-close-room-list-button:hover {
  background: #1e293b;
  color: #f1f5f9;
}

.rooms-list-header {
  height: 88px;
  padding: 20px 14px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .rooms-list-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.rooms-list-header h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #0f172a;
}

.dark .rooms-list-header h2 {
  color: #f1f5f9;
}

.rooms-list-header p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #94a3b8;
}

.icon-create-room-button {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 24px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.icon-create-room-button:hover {
  background: #1e293b;
  transform: translateY(-1px);
}

.dark .icon-create-room-button {
  background: #f8fafc;
  color: #0f172a;
}

.dark .icon-create-room-button:hover {
  background: #e2e8f0;
}

.rooms-search {
  padding: 0 12px 12px;
  flex-shrink: 0;          /* 保持搜索框完整高度，不被压缩 */
}

.rooms-search input {
  width: 100%;
  height: 42px;
  border-radius: 14px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  padding: 0 14px;
  outline: none;
  font-size: 14px;
  color: #0f172a;
}

.dark .rooms-search input {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
  color: #f1f5f9;
}

.rooms-search input::placeholder {
  color: #94a3b8;
}

.rooms-search input:focus {
  border-color: #d6a84f;
}

.rooms-loading {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  color: #94a3b8;
}

.rooms-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 2rem;
  text-align: center;
}

.rooms-empty .empty-icon {
  font-size: 3rem;
  margin-bottom: 1rem;
}

.rooms-empty h3 {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 600;
  color: #0f172a;
}

.dark .rooms-empty h3 {
  color: #f1f5f9;
}

.rooms-empty p {
  margin: 8px 0 1.5rem;
  font-size: 14px;
  color: #94a3b8;
}

.rooms-list-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 8px 8px 16px;
}

.room-list-item {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
  border: none;
  border-radius: 16px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}

.room-list-item:hover {
  background: #f1f5f9;
}

.dark .room-list-item:hover {
  background: #1e293b;
}

.room-list-item.active {
  background: #0f172a;
  color: #ffffff;
}

.dark .room-list-item.active {
  background: #f8fafc;
  color: #0f172a;
}

.dark .room-list-item.active p,
.dark .room-list-item.active small,
.dark .room-list-item.active span {
  color: inherit;
  opacity: 0.7;
}

.room-list-icon {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  border-radius: 14px;
  background: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  overflow: hidden;
}
/* 多角色房间：3×3 网格堆叠最多 9 个头像，对齐方式模仿微信群聊头像区 */
.room-list-icon.is-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  grid-template-rows: repeat(3, 1fr);
  gap: 1px;
  padding: 1px;
  background: #f1f5f9;
}
/* 单头像态：图片保持 object-fit cover，圆角保持父容器 */
.room-list-icon img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.room-list-avatar-cell {
  position: relative;
  border-radius: 4px;
  overflow: hidden;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.room-list-avatar-cell img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.room-list-avatar-fallback {
  font-size: 8px;
  font-weight: 700;
  color: #64748b;
}
/* +N 角标：仅在最后一格显示；半透蒙层让数字在任意底色上都清晰 */
.room-list-avatar-more {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.6);
  color: #fff;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.dark .room-list-avatar-cell {
  background: #1e293b;
}
.dark .room-list-avatar-fallback {
  color: #94a3b8;
}

.dark .room-list-icon {
  background: #1e293b;
}

.room-list-item.active .room-list-icon {
  background: rgba(255, 255, 255, 0.14);
}

.dark .room-list-item.active .room-list-icon {
  background: #0f172a;
}

.room-list-content {
  min-width: 0;
  flex: 1;
}

.room-list-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 右侧操作区：时间在上，三个点按钮在下，垂直对齐 */
.room-list-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}

.room-list-title-row strong {
  font-size: 14px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: inherit;
}

.room-list-title-row span {
  font-size: 12px;
  color: #94a3b8;
  flex-shrink: 0;
}

.room-list-content p {
  display: block;
  margin-top: 4px;
  font-size: 13px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-list-content small {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #94a3b8;
}

/* Per-row more (three-dot) button + dropdown */
.room-list-time {
  font-size: 12px;
  color: #94a3b8;
  flex-shrink: 0;
}

.room-list-more-btn {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: transparent;
  border-radius: 8px;
  color: #94a3b8;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s, background 0.15s, color 0.15s;
}
.room-list-item:hover .room-list-more-btn,
.room-list-more-btn:focus-visible,
.room-list-more-btn[aria-expanded="true"] {
  opacity: 1;
}
.room-list-more-btn:hover {
  background: rgba(15, 23, 42, 0.08);
  color: #0f172a;
}
.room-list-more-btn:focus-visible {
  outline: 2px solid rgba(15, 23, 42, 0.3);
  outline-offset: 1px;
}
.dark .room-list-more-btn {
  color: #94a3b8;
}
.dark .room-list-more-btn:hover {
  background: rgba(248, 250, 252, 0.08);
  color: #f1f5f9;
}
.room-list-item.active .room-list-more-btn {
  color: #ffffff;
}
.room-list-item.active .room-list-more-btn:hover {
  background: rgba(255, 255, 255, 0.14);
  color: #ffffff;
}
.dark .room-list-item.active .room-list-more-btn {
  color: #0f172a;
}
.dark .room-list-item.active .room-list-more-btn:hover {
  background: rgba(15, 23, 42, 0.08);
  color: #0f172a;
}

.room-list-dropdown {
  position: absolute;
  top: calc(100% - 4px);
  right: 8px;
  z-index: 50;
  min-width: 120px;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: #ffffff;
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 12px;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);
}
.dark .room-list-dropdown {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.4);
}

.room-list-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  font-size: 13px;
  color: inherit;
  background: transparent;
  border: none;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s;
}
.room-list-menu-item:hover,
.room-list-menu-item:focus {
  background: rgba(15, 23, 42, 0.06);
  outline: none;
}
.dark .room-list-menu-item:hover,
.dark .room-list-menu-item:focus {
  background: rgba(248, 250, 252, 0.08);
}
.room-list-menu-item.danger {
  color: #e53935;
}
.room-list-menu-item.danger:hover,
.room-list-menu-item.danger:focus {
  background: rgba(229, 57, 53, 0.08);
}

/* Center: Chat Main Panel */
.chat-main-panel {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: radial-gradient(circle at top, rgba(214, 168, 79, 0.08), transparent 280px), #f8fafc;
  position: relative;
  z-index: 1;
}

.dark .chat-main-panel {
  background: radial-gradient(circle at top, rgba(214, 168, 79, 0.05), transparent 280px), #020617;
}

.chat-empty-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #64748b;
}

.chat-empty-icon {
  width: 72px;
  height: 72px;
  border-radius: 24px;
  border: 1px solid rgba(214, 168, 79, 0.45);
  color: #d6a84f;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  margin-bottom: 18px;
}

.chat-empty-state h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
}

.dark .chat-empty-state h2 {
  color: #f1f5f9;
}

.chat-empty-state p {
  margin-top: 10px;
  font-size: 15px;
  color: #64748b;
  max-width: 320px;
}

/* Right: Characters Panel */
.room-characters-panel {
  min-width: 0;
  background: #ffffff;
  border-left: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  flex-direction: column;
  transition: transform 0.3s ease;
  overflow: hidden;
  position: relative;
  z-index: 10;
}

.dark .room-characters-panel {
  background: #0f172a;
  border-left-color: rgba(71, 85, 105, 0.85);
}

.page-layout.role-panel-collapsed .room-characters-panel {
  border-left: none;
}

.panel-tabs-wrapper {
  display: flex;
  align-items: center;
  padding: 8px 8px 0;
  gap: 8px;
}

/* Panel Tabs */
.panel-tabs {
  display: flex;
  flex: 1;
  min-width: 0;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .panel-tabs {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.panel-tab {
  flex: 1;
  padding: 14px;
  font-size: 13px;
  font-weight: 500;
  color: #94a3b8;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.panel-tab:hover {
  color: #0f172a;
}

.panel-tab.active {
  color: #0f172a;
  border-bottom-color: #0f172a;
}

.dark .panel-tab:hover,
.dark .panel-tab.active {
  color: #f1f5f9;
}

.dark .panel-tab.active {
  border-bottom-color: #f1f5f9;
}

.tab-badge {
  background: #e2e8f0;
  color: #64748b;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 10px;
}

.dark .tab-badge {
  background: #1e293b;
  color: #94a3b8;
}

/* Members Panel */
.members-panel-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.members-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.members-loading,
.members-empty {
  text-align: center;
  padding: 24px;
  color: #94a3b8;
  font-size: 14px;
}

.member-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border-radius: 12px;
  margin-bottom: 8px;
}

.member-item:hover {
  background: #f8fafc;
}

.dark .member-item:hover {
  background: #1e293b;
}

.member-avatar-wrapper {
  position: relative;
  width: 40px;
  height: 40px;
  /* 双保险:父级 flex 拉伸时也保持正方形,避免内部头像被压成椭圆 */
  aspect-ratio: 1 / 1;
  flex-shrink: 0;
}

.member-avatar-wrapper .member-avatar {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  /* 强制覆盖 Tailwind preflight 的 `img { height: auto }`,
     否则原图为横版矩形时,img 会按原图比例算出 < 40px 的高度,出现横向椭圆 */
  height: 100% !important;
  max-width: none;
  aspect-ratio: 1 / 1;
  border-radius: 50%;
  object-fit: cover;
}

.member-avatar-wrapper .member-avatar-placeholder {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 50%;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #64748b;
}

.dark .member-avatar-wrapper .member-avatar-placeholder {
  background: #1e293b;
  color: #94a3b8;
}

.member-avatar {
  width: 40px;
  /* 强制覆盖 Tailwind preflight 的 `img { height: auto }`,
     否则原图为横版矩形时,img 会按原图比例算出 < 40px 的高度,出现横向椭圆 */
  height: 40px !important;
  max-width: none;
  aspect-ratio: 1 / 1;
  border-radius: 50%;
  object-fit: cover;
}

.member-avatar-placeholder {
  width: 40px;
  height: 40px;
  aspect-ratio: 1 / 1;
  border-radius: 50%;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #64748b;
}

.dark .member-avatar-placeholder {
  background: #1e293b;
  color: #94a3b8;
}

.member-info {
  flex: 1;
}

.member-info strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.dark .member-info strong {
  color: #f1f5f9;
}

.member-role {
  font-size: 12px;
  color: #94a3b8;
}

.members-footer {
  padding: 12px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .members-footer {
  border-top-color: rgba(71, 85, 105, 0.85);
}

.invite-member-btn {
  width: 100%;
  padding: 12px;
  border-radius: 12px;
  border: 1px dashed #cbd5e1;
  background: transparent;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.15s;
}

.invite-member-btn:hover {
  border-color: #0f172a;
  color: #0f172a;
  background: #f8fafc;
}

.dark .invite-member-btn:hover {
  border-color: #f1f5f9;
  color: #f1f5f9;
  background: #1e293b;
}

/* Invite Modal */
.invite-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.06);
  backdrop-filter: blur(4px);
}

.dark .invite-modal-overlay {
  background: transparent;
  backdrop-filter: none;
}

.invite-modal {
  width: 420px;
  max-width: 100%;
  background: #ffffff;
  border-radius: 20px;
  box-shadow: 0 28px 60px rgba(15, 23, 42, 0.2);
  overflow: hidden;
}

.dark .invite-modal {
  background: #0f172a;
}

.invite-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .invite-modal-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.invite-modal-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.dark .invite-modal-header h2 {
  color: #f1f5f9;
}

.modal-close-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-close-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.dark .modal-close-btn:hover {
  background: #1e293b;
  color: #f1f5f9;
}

.invite-modal-body {
  padding: 24px;
}

.invite-hint {
  margin: 0 0 16px;
  font-size: 14px;
  color: #64748b;
}

.invite-input {
  width: 100%;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  font-size: 14px;
  color: #0f172a;
  outline: none;
  transition: all 0.15s;
}

.invite-input:focus {
  border-color: #0f172a;
  box-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
}

.dark .invite-input {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
  color: #f1f5f9;
}

.dark .invite-input:focus {
  border-color: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16);
}

.invite-error {
  margin: 12px 0 0;
  font-size: 13px;
  color: #dc2626;
}

.invite-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid rgba(226, 232, 240, 0.9);
}

.dark .invite-modal-footer {
  border-top-color: rgba(71, 85, 105, 0.85);
}

.invite-cancel-btn {
  padding: 10px 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  background: transparent;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.invite-cancel-btn:hover {
  background: #f8fafc;
}

.dark .invite-cancel-btn {
  border-color: rgba(71, 85, 105, 0.85);
  color: #94a3b8;
}

.dark .invite-cancel-btn:hover {
  background: #1e293b;
}

.invite-submit-btn {
  padding: 10px 20px;
  border-radius: 12px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.invite-submit-btn:hover:not(:disabled) {
  background: #1e293b;
}

.invite-submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.dark .invite-submit-btn {
  background: #f8fafc;
  color: #0f172a;
}

.dark .invite-submit-btn:hover:not(:disabled) {
  background: #e2e8f0;
}

.characters-panel-header {
  height: 76px;
  flex-shrink: 0;          /* 关键：flex 容器空间不足时不要压缩 header */
  padding: 18px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dark .characters-panel-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.characters-panel-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #0f172a;
}

.dark .characters-panel-header h3 {
  color: #f1f5f9;
}

.characters-panel-header p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.members-panel-header {
  height: 76px;
  padding: 18px;
  border-bottom: 1px solid rgba(226, 232, 240, 0.9);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dark .members-panel-header {
  border-bottom-color: rgba(71, 85, 105, 0.85);
}

.members-panel-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #0f172a;
}

.dark .members-panel-header h3 {
  color: #f1f5f9;
}

.members-panel-header p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}

.add-char-btn {
  width: 100px;
  height: 36px;
  border-radius: 12px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.add-char-btn:hover {
  background: #1e293b;
}

.dark .add-char-btn {
  background: #f8fafc;
  color: #0f172a;
}

.dark .add-char-btn:hover {
  background: #e2e8f0;
}

.icon-close-role-panel-button {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s ease;
}

.icon-close-role-panel-button:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.dark .icon-close-role-panel-button:hover {
  background: #1e293b;
  color: #f1f5f9;
}

.characters-empty {
  margin: 18px;
  padding: 24px 16px;
  border-radius: 20px;
  border: 1px dashed #cbd5e1;
  text-align: center;
  color: #64748b;
}

.dark .characters-empty {
  border-color: rgba(71, 85, 105, 0.85);
}

.characters-empty h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.dark .characters-empty h4 {
  color: #f1f5f9;
}

.characters-empty p {
  margin: 8px 0 0;
  font-size: 13px;
}

.characters-empty button {
  margin-top: 14px;
  height: 38px;
  padding: 0 16px;
  border-radius: 12px;
  border: none;
  background: #0f172a;
  color: #ffffff;
  font-size: 14px;
  cursor: pointer;
}

.characters-empty button:hover {
  background: #1e293b;
}

.dark .characters-empty button {
  background: #f8fafc;
  color: #0f172a;
}

.dark .characters-empty button:hover {
  background: #e2e8f0;
}

.characters-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.character-chip-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 16px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.dark .character-chip-card {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
}

.character-chip-card img {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  object-fit: cover;
}

.char-avatar-placeholder {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 600;
  color: #64748b;
}

.dark .char-avatar-placeholder {
  background: #0f172a;
  color: #94a3b8;
}

.character-info-row {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.character-info-row .character-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.character-info-row .character-desc {
  /* 描述过长时截断为 2 行 + 省略号,避免撑高卡片 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: break-word;
  font-size: 0.75rem;
  color: var(--text-secondary);
  line-height: 1.4;
}

.edit-char-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: 8px;
  background: var(--button-bg);
  color: var(--button-text);
  cursor: pointer;
  transition: all 0.15s ease;
  align-self: flex-end;
  margin-top: 8px;
  flex-shrink: 0;
}

.edit-char-btn:hover {
  opacity: 0.85;
}

/* 群聊模式（character-chip-card 横排）下的编辑按钮变体：
   原 .edit-char-btn 的 align-self:flex-end + margin-top:8px 是给 single 模式
   （垂直布局卡片）用的，群聊里卡片是 flex row，应该让按钮自然落在右侧。 */
.characters-list .character-chip-card .edit-char-btn {
  align-self: center;
  margin-top: 0;
  margin-left: auto;
  padding: 6px;
  width: 28px;
  height: 28px;
  color: #64748b;
  background: transparent;
  border-color: transparent;
}
.characters-list .character-chip-card .edit-char-btn:hover {
  background: rgba(24, 24, 27, 0.06);
  color: #0f172a;
}
.dark .characters-list .character-chip-card .edit-char-btn {
  color: #94a3b8;
}
.dark .characters-list .character-chip-card .edit-char-btn:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #f1f5f9;
}

.character-chip-card strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.dark .character-chip-card strong {
  color: #f1f5f9;
}

.character-chip-card span {
  display: -webkit-box;
  margin-top: 3px;
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-word;
}

.conversation-mode-card {
  margin: 14px;
  padding: 14px;
  border-radius: 18px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.dark .conversation-mode-card {
  background: #1e293b;
  border-color: rgba(71, 85, 105, 0.85);
}

.mode-switch-header {
  margin-bottom: 12px;
}

.mode-switch-title {
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
}

.dark .mode-switch-title {
  color: #94a3b8;
}

.mode-switch-container {
  cursor: pointer;
  padding: 4px 0;
}

.mode-switch-track {
  position: relative;
  display: flex;
  align-items: center;
  background: #e2e8f0;
  border-radius: 20px;
  padding: 4px;
  height: 40px;
}

.dark .mode-switch-track {
  background: #0f172a;
}

.mode-switch-thumb {
  position: absolute;
  width: calc(50% - 4px);
  height: 32px;
  background: #0f172a;
  border-radius: 16px;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.dark .mode-switch-thumb {
  background: #f8fafc;
}

.mode-switch-thumb.thumb-left {
  left: 4px;
}

.mode-switch-thumb.thumb-right {
  left: calc(50%);
}

.mode-label {
  flex: 1;
  text-align: center;
  font-size: 13px;
  font-weight: 500;
  color: #94a3b8;
  transition: color 0.25s ease;
  position: relative;
  z-index: 1;
  cursor: pointer;
}

.dark .mode-label {
  color: #64748b;
}

.mode-label.active {
  color: #ffffff;
}

.dark .mode-label.active {
  color: #0f172a;
}

.mode-desc {
  margin: 10px 0 0;
  font-size: 12px;
  color: #94a3b8;
  text-align: center;
}

.dark .mode-desc {
  color: #64748b;
}

/* Responsive */
@media (max-width: 1200px) {
  .rooms-chat-shell {
    grid-template-columns: var(--room-list-width, 300px) minmax(0, 1fr);
  }

  .room-characters-panel {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    width: var(--role-panel-width, 280px);
    z-index: 1000;
    box-shadow: -4px 0 24px rgba(0, 0, 0, 0.15);
    transform: translateX(100%);
    transition: transform 0.3s ease;
  }

  .page-layout:not(.role-panel-collapsed) .room-characters-panel {
    transform: translateX(0);
  }

  .page-layout.role-panel-collapsed .room-characters-panel {
    transform: translateX(100%);
  }

  .page-layout.room-list-collapsed .rooms-list-panel {
    transform: translateX(-100%);
  }

  .room-list-toggle-btn {
    display: flex;
  }
}

@media (max-width: 768px) {
  .rooms-chat-shell {
    grid-template-columns: 1fr;
  }

  .rooms-list-panel {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    width: var(--room-list-width, 300px);
    z-index: 1000;
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
    transform: translateX(-100%);
    transition: transform 0.3s ease;
  }

  .page-layout:not(.room-list-collapsed) .rooms-list-panel {
    transform: translateX(0);
  }

  .page-layout.room-list-collapsed .rooms-list-panel {
    transform: translateX(-100%);
  }

  .chat-main-panel {
    height: 100%;
  }

  .room-list-toggle-btn {
    left: auto;
    right: -14px;
  }
}
</style>
