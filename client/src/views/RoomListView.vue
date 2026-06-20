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
//   - useToast：统一提示（删除成功/失败等）
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { charactersApi } from '@/api/characters'
import { scenariosApi } from '@/api/scenarios'
import { useRouter, useRoute } from 'vue-router'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import CreateRoomModal from '@/components/room/CreateRoomModal.vue'
import CreateCharacterModal from '@/components/character/CreateCharacterModal.vue'
import UserDropdown from '@/components/ui/UserDropdown.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
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

const showCreateModal = ref(false)
const showCreateCharacterModal = ref(false)
const showAddExistingCharacterModal = ref(false)
const showCreateDropdown = ref(false)
const showEditCharacterModal = ref(false)
const editingCharacter = ref<any>(null)

// 在聊天室内创建角色的上下文状态
const createCharacterRoomId = ref<string | null>(null)
const dropdownRef = ref<HTMLElement | null>(null)
const selectedCategory = ref('all')
const searchQuery = ref('')
// mounted 在 onMounted 延迟 50ms 后置 true，触发 .page-layout 渐显动画（避免首帧空白闪烁）
const mounted = ref(false)

// Selected room for chat panel (my-rooms tab two-column layout)
const selectedRoomId = ref<string | null>(null)

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

// 解析成员头像 URL
// 后端返回的头像地址已经是可直接使用的相对路径或绝对 URL，
// 这里保留作为扩展点：如果未来接入 CDN 或外部图床，按前缀规则改写即可。
function resolveAvatarUrl(url: string | null | undefined): string {
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://')) return url
  if (url.startsWith('/api/')) return url
  if (url.startsWith('/uploads/')) return url
  if (url.startsWith('/')) return url
  return url
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

// Navigation items
const navItems = [
  { id: 'discover', label: '发现', emoji: '🔍' },
  { id: 'characters', label: '角色库', emoji: '📚' },
  { id: 'scenarios', label: '场景', emoji: '💡' },
  { id: 'my-rooms', label: '我的聊天', emoji: '💬' },
]

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
    const characterName = scenario.id === 'product-brainstorm'
      ? '产品顾问'
      : scenario.id === 'english-tutor'
        ? 'Emma · English Tutor'
        : scenario.id === 'writing-coach'
          ? '资深写作编辑'
          : scenario.title + ' 助手'
    const promptResp = await charactersApi.generatePrompt({
      name: characterName,
      description
    })
    const newCharacter = await charactersApi.create({
      name: characterName,
      description,
      prompt: promptResp.data?.prompt || ''
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
      roomStore.fetchMyRooms()
    }
  },
  { immediate: true }
)

// Get current user's characters
const myCharacters = computed(() => {
  if (!authStore.user) return []
  return characterStore.characters.filter(
    c => c.ownerId === authStore.user!.id && !c.isPreset
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

// Start chat with a character - creates/joins a single-person chat room
// 幂等保证：每次先 fetchMyRooms 再查找已有房间，避免跨页面/跨设备创建重复会话。
async function startChat(character: any) {
  try {
    console.log('[DEBUG] startChat called with character:', character)

    // 刷新 myRooms 以获取最新数据
    await roomStore.fetchMyRooms()

    console.log('[DEBUG] myRooms after fetch:', JSON.stringify(roomStore.myRooms.map(r => ({
      id: r.id,
      name: r.name,
      characters: r.characters
    }))))

    // Check if this character already has a chat room in my-rooms
    const existingRoom = roomStore.myRooms.find(room =>
      room.characters?.some(c => c.id === character.id)
    )

    console.log('[DEBUG] looking for character.id:', character.id)
    console.log('[DEBUG] existingRoom:', existingRoom)

    if (existingRoom) {
      // 跳转到已有房间
      router.replace({
        path: '/rooms',
        query: {
          ...route.query,
          tab: 'my-rooms',
          roomId: existingRoom.id
        }
      })
    } else {
      // 为该角色创建一个新房间
      const room = await roomStore.createRoom(character.name)
      await roomStore.addCharacterToRoom(room.id, character.id)
      // Navigate to my-rooms tab with the room selected
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
const categories = [
  { id: 'all', label: '全部', emoji: '✨' },
  { id: 'scientist', label: '科学家', emoji: '🔬', color: '#4F7DF3' },
  { id: 'star', label: '明星', emoji: '🌟', color: '#F472B6' },
  { id: 'entrepreneur', label: '企业家', emoji: '🚀', color: '#FB923C' },
  { id: 'philosopher', label: '哲学家', emoji: '💭', color: '#8B5CF6' },
  { id: 'athlete', label: '运动员', emoji: '🏆', color: '#10B981' },
  { id: 'writer', label: '作家', emoji: '📖', color: '#34D399' },
  { id: 'anime', label: '动漫', emoji: '🎨', color: '#EC4899' },
  { id: 'historical', label: '历史人物', emoji: '🏛️', color: '#D4AF6A' },
]

// Featured characters - loaded from API
const featuredCharacters = ref<any[]>([])
const featuredCharactersLoading = ref(false)

// 发现页"推荐角色"列表的拉取与兜底：头像缺失时用 DiceBear SVG 生成确定性占位（seed=name），
// 这样同一角色无论何时显示都是同一张图，提升品牌识别一致性。
async function fetchFeaturedCharacters() {
  featuredCharactersLoading.value = true
  try {
    const characters = await charactersApi.getRecommended()
    featuredCharacters.value = characters.data.map((char: any) => ({
      id: char.id,
      name: char.name,
      role: char.description || 'AI 角色',
      avatar: char.avatarUrl || `https://api.dicebear.com/7.x/personas/svg?seed=${encodeURIComponent(char.name)}&backgroundColor=c0aede`,
      online: false
    }))
  } catch (e) {
    console.error('[DEBUG] Failed to fetch featured characters:', e)
    featuredCharacters.value = []
  } finally {
    featuredCharactersLoading.value = false
  }
}

// Room cards data
const roomCardsData = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    title: 'AI 会取代人类创造力吗？',
    cover: 'https://images.unsplash.com/photo-1620712943543-bcc4688e7485?w=400&h=225&fit=crop',
    participants: ['爱因斯坦', '马斯克', '宫崎骏'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4',
      'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9',
      'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf'
    ],
    latestMessage: { sender: '爱因斯坦', text: '时间并不是线性的...' },
    onlineCount: 128,
    messageCount: 892,
    category: 'scientist',
    isHot: true
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    title: '天赋与努力，哪个更重要？',
    cover: 'https://images.unsplash.com/photo-1579952363873-27f3bade9f55?w=400&h=225&fit=crop',
    participants: ['梅西', '乔丹', '泰勒'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Messi&backgroundColor=c0aede',
      'https://api.dicebear.com/7.x/personas/svg?seed=Jordan&backgroundColor=ffd5dc',
      'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=c0aede'
    ],
    latestMessage: { sender: '梅西', text: '每天训练8小时...' },
    onlineCount: 256,
    messageCount: 1543,
    category: 'athlete',
    isHot: true
  },
  {
    id: '00000000-0000-0000-0000-000000000003',
    title: '时间是否真实存在？',
    cover: 'https://images.unsplash.com/photo-1531297484001-80022131f5a1?w=400&h=225&fit=crop',
    participants: ['苏格拉底', '爱因斯坦', '牛顿'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Socrates&backgroundColor=d1f4d1',
      'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4',
      'https://api.dicebear.com/7.x/personas/svg?seed=Newton&backgroundColor=c4b5fd'
    ],
    latestMessage: { sender: '苏格拉底', text: '我知道我一无所知...' },
    onlineCount: 89,
    messageCount: 567,
    category: 'philosopher',
    isHot: false
  },
  {
    id: '00000000-0000-0000-0000-000000000004',
    title: '创作的本质是什么？',
    cover: 'https://images.unsplash.com/photo-1513364776144-60967b0f800f?w=400&h=225&fit=crop',
    participants: ['宫崎骏', '莎士比亚', '泰勒'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Miyazaki&backgroundColor=ffdfbf',
      'https://api.dicebear.com/7.x/personas/svg?seed=Shakespeare&backgroundColor=e0c3fc',
      'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=c0aede'
    ],
    latestMessage: { sender: '宫崎骏', text: '创造让世界更温暖...' },
    onlineCount: 167,
    messageCount: 723,
    category: 'anime',
    isHot: true
  },
  {
    id: '00000000-0000-0000-0000-000000000005',
    title: '星际旅行能实现吗？',
    cover: 'https://images.unsplash.com/photo-1446776811953-b23d57bd21aa?w=400&h=225&fit=crop',
    participants: ['马斯克', '爱因斯坦'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Musk&backgroundColor=d1d4f9',
      'https://api.dicebear.com/7.x/personas/svg?seed=Einstein&backgroundColor=b6e3f4'
    ],
    latestMessage: { sender: '马斯克', text: '2050年火星城市...' },
    onlineCount: 312,
    messageCount: 2104,
    category: 'entrepreneur',
    isHot: true
  },
  {
    id: '00000000-0000-0000-0000-000000000006',
    title: '音乐能改变世界吗？',
    cover: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&h=225&fit=crop',
    participants: ['泰勒', '贝多芬'],
    participantAvatars: [
      'https://api.dicebear.com/7.x/personas/svg?seed=Taylor&backgroundColor=c0aede',
      'https://api.dicebear.com/7.x/personas/svg?seed=Beethoven&backgroundColor=ffd5dc'
    ],
    latestMessage: { sender: '泰勒', text: '每一首歌都是一个故事...' },
    onlineCount: 198,
    messageCount: 945,
    category: 'star',
    isHot: false
  },
]

// Recent chats - computed from sortedMyRooms (max 4)
const recentChats = computed(() => {
  return roomStore.sortedMyRooms.slice(0, 4).map(room => ({
    id: room.id,
    name: room.name,
    lastMessage: room.topic || '开始聊天吧',
    avatar: room.characters && room.characters.length > 0
      ? (room.characters[0].avatarUrl || null)
      : null
  }))
})

// 挂载时并发拉取四份数据（不互依赖），让发现/我的/角色库等多个 tab 都能秒开。
// document 级 click 监听注册在此处而非组件内：避免 dropdown 被 portal 出去后点不到关闭。
onMounted(() => {
  roomStore.fetchRooms()
  roomStore.fetchMyRooms()
  characterStore.fetchCharacters()
  fetchFeaturedCharacters()
  setTimeout(() => { mounted.value = true }, 50)

  // 点击外部时关闭下拉菜单
  document.addEventListener('click', handleClickOutside)
  document.addEventListener('click', onRoomMenuOutsideClick)

  // 加载已保存的折叠状态
  loadLayoutState()
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  document.removeEventListener('click', onRoomMenuOutsideClick)
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

// For demo/placeholder rooms in Discover view - still uses old navigation
// 即使是 demo 占位房间也走 my-rooms tab：新版三栏布局是唯一支持 chat 渲染的入口。
async function enterRoom(roomId: string) {
  await roomStore.recordEnter(roomId)
  selectedRoomId.value = roomId
  router.replace({
    path: '/rooms',
    query: {
      tab: 'my-rooms',
      roomId: roomId
    }
  })
}

function toggleCreateDropdown(e: Event) {
  e.stopPropagation()
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
      '--global-sidebar-width': isGlobalSidebarCollapsed ? '72px' : '260px',
      '--room-list-width': isRoomListCollapsed ? '0px' : '320px',
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
        <img src="/image.png" alt="logo" class="sidebar-brand-logo" />
        <span class="logo-text">Idea Party</span>
      </div>

      <!-- 创建按钮（下拉菜单） -->
      <div
        class="create-dropdown-wrapper"
        ref="dropdownRef"
      >
        <button class="create-btn" @click.stop="toggleCreateDropdown">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
          </svg>
          <span>创建</span>
        </button>

        <!-- 下拉菜单 -->
        <Transition name="dropdown">
          <div
            v-if="showCreateDropdown"
            class="create-dropdown-menu"
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
      </div>

      <!-- 导航 -->
      <nav class="nav-menu">
        <a
          v-for="item in navItems"
          :key="item.id"
          href="#"
          class="nav-item"
          :class="{ active: item.id === activeNavId }"
          @click.prevent="handleNavClick(item.id)"
        >
          <span class="nav-emoji">{{ item.emoji }}</span>
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
            <div class="chat-avatar">
              <img v-if="chat.avatar" :src="chat.avatar" :alt="chat.name" />
              <span v-else class="chat-avatar-placeholder">{{ chat.name.charAt(0) }}</span>
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
        <header class="content-header">
          <h1 class="page-title">场景</h1>
          <p class="page-subtitle">选一个场景，一键创建带模板的聊天室</p>
        </header>
        <div class="scenarios-grid">
          <button
            v-for="s in scenarioStore.scenarios"
            :key="s.id"
            type="button"
            class="scenario-card"
            @click="openScenario(s)"
          >
            <div class="scenario-emoji">{{ s.emoji }}</div>
            <div class="scenario-body">
              <h3 class="scenario-title">{{ s.title }}</h3>
              <p class="scenario-desc">{{ s.description }}</p>
            </div>
          </button>
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
                      <label class="scenario-modal-label">岗位 / 行业 *</label>
                      <textarea
                        v-model="userInput"
                        class="scenario-modal-input"
                        :placeholder="activeScenario.id === 'interview-coach'
                          ? '例如：高级前端工程师 / SaaS / 5年'
                          : activeScenario.userInputPlaceholder"
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
                      <pre
                        v-if="!activeScenario.dynamicPrompt && activeScenario.promptTemplate"
                        class="scenario-modal-template"
                      >{{ activeScenario.promptTemplate }}</pre>
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
        <header class="content-header">
          <h1 class="page-title">角色库</h1>
          <button class="create-btn-large" @click="showCreateCharacterModal = true">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
            创建角色
          </button>
        </header>

        <!-- 空状态 -->
        <div v-if="myCharacters.length === 0" class="empty-state">
          <div class="empty-icon">📚</div>
          <h2 class="empty-title">还没有创建角色</h2>
          <p class="empty-desc">创建你的第一个 AI 角色，开始对话吧！</p>
          <button class="empty-btn" @click="showCreateCharacterModal = true">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
            </svg>
            创建角色
          </button>
        </div>

        <!-- 角色卡片网格 -->
        <div v-else class="character-grid">
          <div
            v-for="character in myCharacters"
            :key="character.id"
            class="character-card-item"
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
              <p class="character-date">创建于 {{ formatDate(character.createdAt) }}</p>
            </div>
            <div class="card-footer">
              <button class="chat-btn" @click.stop="startChat(character)">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                </svg>
                对话
              </button>
              <button class="edit-btn" @click.stop="openEditCharacterModal(character)">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                </svg>
                编辑
              </button>
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

            <!-- 聊天室列表 -->
            <div v-else class="rooms-list-scroll">
              <div
                v-for="room in roomStore.sortedMyRooms"
                :key="room.id"
                class="room-list-item"
                :class="{ active: selectedRoomId === room.id }"
                @click="selectRoom(room.id)"
              >
                <div class="room-list-icon">💬</div>
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
                    <div>
                      <strong>{{ char.name }}</strong>
                      <span>{{ char.description || '暂无描述' }}</span>
                    </div>
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
                        :src="resolveAvatarUrl(member.avatarUrl)"
                        :alt="member.displayName"
                        class="member-avatar"
                      />
                      <img
                        v-else
                        src="/image.png"
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
        <!-- 头部 -->
        <header class="content-header">
          <h1 class="page-title">发现</h1>
          <div class="search-bar">
            <svg class="search-icon" width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
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
            <h2 class="section-title">推荐角色</h2>
            <a href="#" class="see-all" @click.prevent>查看全部</a>
          </div>
          <div v-if="featuredCharactersLoading" class="featured-loading">
            <div class="loading-spinner"></div>
          </div>
          <div v-else-if="featuredCharacters.length === 0" class="featured-empty">
            暂无推荐角色
          </div>
          <div v-else class="featured-scroll">
            <div
              v-for="char in featuredCharacters.slice(0, 6)"
              :key="char.id"
              class="character-card"
            >
              <div class="character-avatar-wrap">
                <img :src="char.avatar" :alt="char.name" class="character-avatar" />
                <span v-if="char.online" class="online-indicator"></span>
              </div>
              <div class="character-info">
                <span class="character-name">{{ char.name }}</span>
                <span class="character-role">{{ char.role }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 分类标签 -->
        <section class="category-tabs">
          <button
            v-for="cat in categories"
            :key="cat.id"
            class="category-chip"
            :class="{ active: selectedCategory === cat.id }"
            @click="selectedCategory = cat.id"
            :style="selectedCategory === cat.id && cat.color ? { backgroundColor: cat.color + '20', borderColor: cat.color, color: cat.color } : {}"
          >
            <span class="chip-emoji">{{ cat.emoji }}</span>
            <span class="chip-label">{{ cat.label }}</span>
          </button>
        </section>

        <!-- 热门聊天室 -->
        <section class="rooms-section">
          <div class="section-header">
            <h2 class="section-title">
              <span class="hot-badge">🔥</span>
              热门聊天室
            </h2>
            <span class="room-count">{{ roomCardsData.length }} 个房间</span>
          </div>

          <!-- 聊天室卡片网格 -->
          <div class="room-grid">
            <div
              v-for="room in roomCardsData"
              :key="room.id"
              class="room-card"
              @click="enterRoom(room.id)"
            >
              <!-- 封面图 -->
              <div class="room-cover">
                <img :src="room.cover" :alt="room.title" class="cover-img" />
                <div v-if="room.isHot" class="hot-tag">🔥 热门</div>
                <div class="cover-overlay"></div>
              </div>

              <!-- 聊天室信息 -->
              <div class="room-body">
                <h3 class="room-title">{{ room.title }}</h3>

                <!-- 参与者 -->
                <div class="room-participants">
                  <div class="avatar-stack">
                    <img
                      v-for="(avatar, i) in room.participantAvatars"
                      :key="i"
                      :src="avatar"
                      :alt="room.participants[i]"
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

                <!-- 统计数据 -->
                <div class="room-stats">
                  <span class="stat">
                    <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                    {{ room.onlineCount }} 在线
                  </span>
                  <span class="stat">
                    <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                    </svg>
                    {{ room.messageCount }} 条消息
                  </span>
                </div>
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

    <!-- 编辑角色弹窗 -->
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
  padding: 1rem;
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
  padding: 0.6rem;
}

.page-layout.global-collapsed .nav-emoji {
  margin: 0;
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

/* Create Dropdown Wrapper */
.create-dropdown-wrapper {
  position: relative;
  display: inline-block;
  width: fit-content;
  margin-bottom: 1rem;
}

.create-dropdown-menu {
  position: absolute;
  left: calc(100% + 6px);
  top: 0;
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
  transition: all 0.15s ease;
}

.nav-item:hover {
  background: var(--bg-primary);
  color: var(--text-primary);
}

.nav-item.active {
  background: var(--bg-primary);
  color: var(--text-primary);
  font-weight: 500;
}

.nav-emoji {
  font-size: 1rem;
  width: 20px;
  text-align: center;
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
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--bg-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  flex-shrink: 0;
  overflow: hidden;
}

.chat-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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
  /* 不在这里滚动，让 rooms-list-scroll 自己滚（避免左右两栏跟着滚） */
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

.page-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text-primary);
  transition: color 0.25s ease;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.65rem 1rem;
  background: var(--input-bg);
  border: 1px solid var(--border-color);
  border-radius: 999px;
  width: 320px;
  transition: all 0.25s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.search-bar:focus-within {
  border-color: #27272a;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.1);
}

.search-icon {
  color: var(--text-muted);
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 0.9rem;
  color: var(--text-primary);
  outline: none;
}

.search-input::placeholder {
  color: var(--text-muted);
}

/* Featured Section */
.featured-section {
  margin-bottom: 2rem;
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

/* Featured Scroll */
.featured-scroll {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 1rem;
  padding: 0.5rem 0;
}
@media (max-width: 1100px) {
  .featured-scroll { grid-template-columns: repeat(4, 1fr); }
}
@media (max-width: 700px) {
  .featured-scroll { grid-template-columns: repeat(3, 1fr); }
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
  word-break: break-word;
}

/* Category Tabs */
.category-tabs {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  overflow-x: auto;
  padding: 0.25rem 0;
  scrollbar-width: none;
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
  transition: all 0.2s ease;
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

.room-count {
  font-size: 0.85rem;
  color: var(--text-muted);
  transition: color 0.25s ease;
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

.hot-tag {
  position: absolute;
  top: 0.75rem;
  right: 0.75rem;
  padding: 0.3rem 0.6rem;
  background: rgba(255, 100, 50, 0.9);
  border-radius: 6px;
  font-size: 0.7rem;
  font-weight: 600;
  color: white;
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

.room-stats {
  display: flex;
  gap: 1rem;
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
}

/* ===== Character Library Styles ===== */
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

.scenarios-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.scenario-card {
  display: flex;
  align-items: flex-start;
  gap: 0.875rem;
  padding: 1.25rem;
  background: var(--bg-secondary, #fff);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 16px;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s ease;
}
.scenario-card:hover {
  border-color: #0f172a;
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08);
}
.scenario-emoji {
  font-size: 32px;
  line-height: 1;
  flex-shrink: 0;
}
.scenario-body { flex: 1; min-width: 0; }
.scenario-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #111827);
  margin: 0 0 4px;
}
.scenario-desc {
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
  margin: 0;
  line-height: 1.5;
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

.character-card-item {
  display: flex;
  align-items: flex-start;
  gap: 1rem;
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
  width: 56px;
  height: 56px;
  border-radius: 12px;
  overflow: hidden;
  flex-shrink: 0;
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
  flex: 1;
  min-width: 0;
}

.character-card-item .character-name {
  font-size: 1.1rem;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 0.25rem;
}

.character-card-item .character-tagline {
  font-size: 0.875rem;
  color: var(--text-secondary);
  margin-bottom: 0.5rem;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.character-card-item .character-date {
  font-size: 0.75rem;
  color: var(--text-muted);
}

.character-card-item .edit-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  width: 100px;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.character-card-item .edit-btn:hover {
  background: var(--border-color);
  color: var(--text-primary);
}

.character-card-item .card-footer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  flex-shrink: 0;
  margin-left: auto;
}

.character-card-item .chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.35rem;
  width: 100px;
  padding: 0.5rem 0.75rem;
  background: var(--button-bg);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  color: var(--button-text);
  font-size: 0.8rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.character-card-item .chat-btn:hover {
  opacity: 0.85;
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
  padding: 20px 18px 14px;
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
  padding: 0 14px 12px;
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
  padding: 8px 10px 16px;
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
}

.member-avatar-wrapper .member-avatar {
  position: absolute;
  top: 0;
  left: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
}

.member-avatar-wrapper .member-avatar-placeholder {
  position: absolute;
  top: 0;
  left: 0;
  width: 40px;
  height: 40px;
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
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
}

.member-avatar-placeholder {
  width: 40px;
  height: 40px;
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
  align-items: center;
  flex: 1;
  gap: 12px;
  min-width: 0;
  overflow: hidden;
}

.character-info-row .character-info {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  max-width: calc(100% - 80px);
}

.edit-char-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid;
  border-radius: 8px;
  background: var(--button-bg);
  border-color: var(--button-bg);
  color: var(--button-text);
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.edit-char-btn:hover {
  opacity: 0.85;
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
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  display: -webkit-box;
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
