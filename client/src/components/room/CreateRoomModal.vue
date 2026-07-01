<script setup lang="ts">
// 创建聊天室的弹窗（仅多人模式）：
// 用户填写聊天室名称/主题并选择≥1 个角色，发起多人讨论（"群聊"）；
// 选 1 个角色仍按多人模式建群，由用户在聊天室内通过"+ 邀请"按钮决定是否扩充角色，
// 不再在创建环节提供"单人对话"分支——保持单一创建入口，语义更清晰。
// 父组件通过 v-model 风格控制 show；监听 created 事件获取新房间 id 并跳转，
// 不在本组件内做路由跳转，以便复用同一个弹窗组件、避免耦合具体路由路径。

import { ref, watch, computed } from 'vue'
import { useRoomStore } from '@/stores/room'
import { useCharacterStore } from '@/stores/character'
import { useAuthStore } from '@/stores/auth'
import { resolveImageUrl } from '@/utils/avatarUrl'

// show：父组件 v-model 控制弹窗显隐
interface Props {
  show: boolean
}

// close：用户取消或主动关闭弹窗
// created：创建房间成功后抛出房间 id；不直接 router.push 是为了把「创建」与「导航」解耦，
// 父组件可决定跳转到 /rooms/:id 还是更新 my-rooms 当前选中项等。
interface Emits {
  close: []
  created: [roomId: string]
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const roomStore = useRoomStore()
const characterStore = useCharacterStore()
const authStore = useAuthStore()

// 多人模式表单
const name = ref('')
const topic = ref('')
const selectedCharacterIds = ref<Set<string>>(new Set())

const loading = ref(false)
const error = ref<string | null>(null)

// 仅展示「当前用户自己创建 + 非预设」的角色：预设角色由系统统一管理，普通用户不应直接基于其建房间
const myCharacters = computed(() => {
  return characterStore.characters.filter(
    c => c.ownerId === authStore.user?.id && !c.isPreset
  )
})

// 名称是否必填：单角色场景下可省略名称（自动用角色名），多角色场景必须显式命名以避免混淆
const isNameRequired = computed(() => selectedCharacterIds.value.size >= 2)

// 单角色场景下，默认回填该角色名作为占位提示；用户留空即直接用此名
const singleCharacterName = computed(() => {
  if (selectedCharacterIds.value.size !== 1) return null
  const id = [...selectedCharacterIds.value][0]
  return myCharacters.value.find(c => c.id === id)?.name ?? null
})

// 每次 show 切换时同步状态：false 时重置所有表单字段，防止残留上次填写；true 时若角色未加载则拉取
watch(() => props.show, (newShow) => {
  if (!newShow) {
    name.value = ''
    topic.value = ''
    selectedCharacterIds.value = new Set()
    error.value = null
  } else {
    if (characterStore.characters.length === 0) {
      characterStore.fetchCharacters()
    }
  }
})

function toggleGroupCharacter(characterId: string) {
  const next = new Set(selectedCharacterIds.value)
  if (next.has(characterId)) {
    next.delete(characterId)
  } else {
    next.add(characterId)
  }
  selectedCharacterIds.value = next
  error.value = null
}

// 总入口：直接走多人模式（mode='group'），提交逻辑统一：
// - 多角色（≥2）时名称必填；单角色时名称可省略，自动用角色名
// - 至少选 1 个角色
async function handleSubmit() {
  if (selectedCharacterIds.value.size === 0) {
    error.value = '请至少选择一个角色'
    return
  }

  // 单角色场景：留空则回退到角色名；多角色场景：必须显式命名
  const trimmedName = name.value.trim()
  let finalName: string
  if (trimmedName) {
    finalName = trimmedName
  } else if (singleCharacterName.value) {
    finalName = singleCharacterName.value
  } else {
    error.value = '请输入聊天室名称'
    return
  }

  loading.value = true
  error.value = null

  try {
    const room = await roomStore.createRoom(
      finalName,
      topic.value.trim() || undefined,
      [...selectedCharacterIds.value],
      'group'
    )
    emit('created', room.id)
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '创建失败'
  } finally {
    loading.value = false
  }
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="show"
        class="room-modal-overlay"
        @click.self="handleClose"
      >
        <!-- 弹窗容器 -->
        <div class="room-modal">
          <!-- 头部 -->
          <header class="room-modal-header">
            <div class="header-content">
              <h2 class="room-modal-title">创建聊天室</h2>
            </div>
            <button class="modal-close" @click="handleClose">
              <svg width="20" height="20" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </header>

          <!-- 主体 -->
          <div class="room-modal-body">
            <div class="room-form">
              <p class="form-description">设置聊天室名称和主题，选择多个角色发起讨论（选 1 个角色也能创建群聊，名称留空时将用该角色命名，后续可点「+ 邀请」继续扩充角色）</p>

              <!-- 名称 -->
              <div class="form-group">
                <label class="form-label">
                  聊天室名称 <span v-if="isNameRequired" class="required">*</span>
                </label>
                <input
                  v-model="name"
                  type="text"
                  :placeholder="singleCharacterName ? `留空将使用「${singleCharacterName}」` : '例如：哲学讨论群'"
                  class="form-input"
                />
              </div>

              <!-- 主题 -->
              <div class="form-group">
                <label class="form-label">主题（可选）</label>
                <textarea
                  v-model="topic"
                  rows="3"
                  placeholder="讨论什么话题？"
                  class="form-textarea"
                ></textarea>
              </div>

              <!-- 角色多选 -->
              <div class="form-group">
                <label class="form-label">
                  选择角色 <span class="required">*</span>
                </label>
                <div class="character-list">
                  <div
                    v-for="character in myCharacters"
                    :key="character.id"
                    class="character-item"
                    :class="{ selected: selectedCharacterIds.has(character.id) }"
                    @click="toggleGroupCharacter(character.id)"
                  >
                    <div class="character-avatar">
                      <img v-if="character.avatarUrl" :src="resolveImageUrl(character.avatarUrl)" :alt="character.name" />
                      <span v-else>{{ character.name.charAt(0) }}</span>
                    </div>
                    <div class="character-info">
                      <span class="character-item-name">{{ character.name }}</span>
                      <span class="character-item-desc">{{ character.description || '暂无描述' }}</span>
                    </div>
                    <div v-if="selectedCharacterIds.has(character.id)" class="check-icon">
                      <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                      </svg>
                    </div>
                  </div>
                  <div v-if="myCharacters.length === 0" class="character-empty">
                    暂无可用角色，请先在「角色库」创建角色
                  </div>
                </div>
              </div>

              <!-- 错误提示 -->
              <p v-if="error" class="form-error">{{ error }}</p>
            </div>
          </div>

          <!-- 底部 -->
          <footer class="room-modal-footer">
            <div class="footer-actions">
              <button
                type="button"
                class="footer-cancel-btn"
                @click="handleClose"
                :disabled="loading"
              >
                取消
              </button>
              <button
                type="button"
                class="footer-submit-btn"
                @click="handleSubmit"
                :disabled="loading || selectedCharacterIds.size === 0"
              >
                {{ loading ? '创建中...' : '创建聊天室' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/*** Light Mode Variables ***/
.room-modal-overlay {
  --overlay-bg: rgba(15, 23, 42, 0.06);
  --modal-bg: #ffffff;
  --modal-border: rgba(226, 232, 240, 0.95);
  --modal-shadow: 0 28px 90px rgba(15, 23, 42, 0.28);
  --header-bg: #ffffff;
  --header-border: rgba(226, 232, 240, 0.9);
  --footer-bg: #ffffff;
  --footer-border: rgba(226, 232, 240, 0.9);
  --body-bg: #ffffff;
  --text-primary: #0f172a;
  --text-secondary: rgba(71, 85, 105, 0.85);
  --text-muted: rgba(100, 116, 139, 0.8);
  --input-bg: #f8fafc;
  --input-border: rgba(203, 213, 225, 0.95);
  --input-focus-border: #0f172a;
  --input-shadow: 0 0 0 3px rgba(15, 23, 42, 0.08);
  --btn-primary-bg: #0f172a;
  --btn-primary-text: #ffffff;
  --btn-secondary-bg: rgba(248, 250, 252, 0.72);
  --btn-secondary-border: rgba(203, 213, 225, 0.55);
  --btn-secondary-text: #334155;
  --error-color: #dc2626;
  --close-hover-bg: rgba(148, 163, 184, 0.18);
  --tab-active-bg: #0f172a;
  --tab-active-text: #ffffff;
  --tab-inactive-text: #64748b;
  --tab-inactive-bg: #f1f5f9;
  --selected-bg: #f0fdf4;
  --selected-border: #22c55e;
}

/*** Dark Mode Variables ***/
.dark .room-modal-overlay {
  --overlay-bg: transparent;
  --modal-bg: #0f172a;
  --modal-border: rgba(71, 85, 105, 0.85);
  --modal-shadow: 0 0 0 0 rgba(0, 0, 0, 0.55);
  --header-bg: #0f172a;
  --header-border: rgba(71, 85, 105, 0.85);
  --footer-bg: #0f172a;
  --footer-border: rgba(71, 85, 105, 0.85);
  --body-bg: #0f172a;
  --text-primary: #f8fafc;
  --text-secondary: rgba(203, 213, 225, 0.72);
  --text-muted: rgba(148, 163, 184, 0.68);
  --input-bg: #1e293b;
  --input-border: rgba(71, 85, 105, 0.95);
  --input-focus-border: #94a3b8;
  --input-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16);
  --btn-primary-bg: #f8fafc;
  --btn-primary-text: #0f172a;
  --btn-secondary-bg: #1e293b;
  --btn-secondary-border: rgba(71, 85, 105, 0.95);
  --btn-secondary-text: #f8fafc;
  --error-color: #fca5a5;
  --close-hover-bg: rgba(255, 255, 255, 0.12);
  --tab-active-bg: #f8fafc;
  --tab-active-text: #0f172a;
  --tab-inactive-text: #94a3b8;
  --tab-inactive-bg: #1e293b;
  --selected-bg: #14532d;
  --selected-border: #22c55e;
}

/*** Overlay ***/
.room-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  background: rgba(0, 0, 0, 0.04) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

/*** Modal Container ***/
.room-modal {
  position: relative;
  width: min(520px, calc(100vw - 48px));
  max-height: min(640px, calc(100vh - 64px));
  display: flex;
  flex-direction: column;
  background: var(--modal-bg) !important;
  color: var(--text-primary) !important;
  border: 1px solid var(--modal-border) !important;
  border-radius: 24px;
  box-shadow: var(--modal-shadow) !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
  overflow: hidden;
}

.room-modal::before,
.room-modal::after {
  display: none !important;
}

.room-modal-header,
.room-modal-body,
.room-modal-footer {
  position: relative;
  z-index: 1;
}

/*** Header ***/
.room-modal-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 28px 32px 20px;
  background: var(--header-bg) !important;
  border-bottom: 1px solid var(--header-border) !important;
}

.header-content {
  flex: 1;
}

.room-modal-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.modal-close {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  border: none;
  background: transparent;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: all 0.15s;
}

.modal-close:hover {
  background: var(--close-hover-bg);
  color: var(--text-primary);
}

/*** Body ***/
.room-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px 28px;
  background: var(--body-bg) !important;
}

.room-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.form-description {
  font-size: 14px;
  color: var(--text-muted);
  margin: 0;
}

.character-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 240px;
  overflow-y: auto;
}

.character-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;
}

.character-item:hover {
  background: var(--tab-inactive-bg);
}

.character-item.selected {
  background: var(--selected-bg);
  border-color: var(--selected-border);
}

.character-avatar {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  overflow: hidden;
  background: var(--tab-inactive-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.character-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.character-avatar span {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-muted);
}

.character-info {
  flex: 1;
  min-width: 0;
}

.character-item-name {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.character-item-desc {
  display: block;
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.check-icon {
  color: var(--selected-border);
}

.character-empty {
  padding: 24px;
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

/*** Form Groups ***/
.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.form-label .required {
  color: var(--error-color);
  margin-left: 2px;
}

.form-error {
  font-size: 13px;
  color: var(--error-color);
}

/*** Inputs & Textareas ***/
.form-input,
.form-textarea {
  width: 100%;
  border-radius: 12px;
  border: 1px solid var(--input-border) !important;
  background: var(--input-bg) !important;
  color: var(--text-primary) !important;
  padding: 12px 14px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: var(--text-muted);
}

.form-input:focus,
.form-textarea:focus {
  border-color: var(--input-focus-border) !important;
  box-shadow: var(--input-shadow) !important;
}

.form-textarea {
  min-height: 80px;
  resize: vertical;
  line-height: 1.6;
}

/*** Footer ***/
.room-modal-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding: 18px 32px;
  background: var(--footer-bg) !important;
  border-top: 1px solid var(--footer-border) !important;
}

.footer-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.footer-cancel-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 14px;
  border: 1px solid var(--btn-secondary-border);
  background: var(--btn-secondary-bg);
  color: var(--btn-secondary-text);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}

.footer-cancel-btn:hover {
  border-color: var(--text-muted);
  color: var(--text-primary);
}

.footer-cancel-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.footer-submit-btn {
  height: 42px;
  padding: 0 20px;
  border-radius: 14px;
  border: none;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  transition: all 0.15s;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}

.footer-submit-btn:hover:not(:disabled) {
  opacity: 0.92;
}

.footer-submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/*** Transitions ***/
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-active .room-modal,
.modal-leave-active .room-modal {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .room-modal,
.modal-leave-to .room-modal {
  transform: scale(0.95) translateY(10px);
  opacity: 0;
}

/*** Responsive ***/
@media (max-width: 640px) {
  .room-modal-overlay {
    padding: 0;
    align-items: flex-end;
  }

  .room-modal {
    width: 100vw;
    max-height: 92vh;
    border-radius: 24px 24px 0 0;
  }

  .room-modal-header,
  .room-modal-body,
  .room-modal-footer {
    padding-left: 20px;
    padding-right: 20px;
  }
}

/*** Dark mode explicit overrides ***/
.dark .room-modal-overlay {
  background: rgba(0, 0, 0, 0.08) !important;
}

.dark .room-modal {
  background: #0f172a !important;
  color: #f8fafc !important;
  border-color: rgba(71, 85, 105, 0.85) !important;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.45) !important;
}

.dark .room-modal-header {
  background: #0f172a !important;
  border-bottom-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .room-modal-body {
  background: #0f172a !important;
}

.dark .room-modal-footer {
  background: #0f172a !important;
  border-top-color: rgba(71, 85, 105, 0.85) !important;
}

.dark .form-input,
.dark .form-textarea {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
}

.dark .form-input:focus,
.dark .form-textarea:focus {
  border-color: #94a3b8 !important;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.16) !important;
}

.dark .footer-submit-btn {
  background: #f8fafc !important;
  color: #0f172a !important;
}

.dark .footer-cancel-btn {
  background: #1e293b !important;
  border-color: rgba(71, 85, 105, 0.95) !important;
  color: #f8fafc !important;
}

.dark .character-item.selected {
  background: #14532d !important;
  border-color: #22c55e !important;
}
</style>