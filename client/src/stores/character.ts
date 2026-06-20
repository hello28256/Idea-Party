import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Character, CharacterRequest } from '@/types'
import { charactersApi } from '@/api/characters'

/**
 * 角色全局状态（Pinia setup store）。
 * 统一管理「用户自建角色 + 系统预设角色」的缓存与 CRUD，
 * 让房间、场景配置等视图共享同一份角色数据，避免重复请求后端。
 */
export const useCharacterStore = defineStore('character', () => {
  // State
  // 当前用户可见的全部角色（混合预设与自建），供房间、场景、角色管理界面复用同一份数据
  const characters = ref<Character[]>([])
  // 仅系统预设角色：独立缓存便于「创建房间」弹窗快速选择，避免每次进入都重复拉全部角色再过滤
  const presets = ref<Character[]>([])
  // 任意一个 CRUD 请求进行中都置 true；UI 用其展示全局 Loading，避免多个按钮各自维护状态
  const loading = ref(false)
  // 最近一次失败的错误信息；UI 在 toast/dialog 中直接读取展示，不在 action 内部消化
  const error = ref<string | null>(null)

  // Computed
  // 仅暴露用户自建角色，预设角色走 presets，避免在「我的角色」管理界面混入系统角色。
  const userCharacters = computed(() => characters.value.filter(c => !c.isPreset))

  // 检查用户是否已存在同名角色
  /**
   * 重名校验：同一 ownerId 下不允许出现同名角色（大小写不敏感）。
   * excludeId 用于编辑场景下排除自身，避免「未改名也判重」的假阳性。
   */
  function hasDuplicateName(ownerId: string, name: string, excludeId?: string): boolean {
    return characters.value.some(c =>
      c.ownerId === ownerId &&
      c.name.toLowerCase() === name.toLowerCase() &&
      c.id !== excludeId
    )
  }

  // Actions
  /**
   * 拉取当前用户可见的全部角色。
   * 副作用：写入 characters、loading、error；失败保留旧数据，由调用方决定是否提示用户。
   */
  async function fetchCharacters() {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.list()
      characters.value = response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to fetch characters'
      console.error('[DEBUG] fetchCharacters failed:', e)
    } finally {
      loading.value = false
    }
  }

  /**
   * 拉取系统预设角色列表。
   * 与 fetchCharacters 分开请求，原因是预设数据更新频率低、可独立缓存，避免每次进入房间都重复拉取。
   */
  async function fetchPresets() {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.getPresets()
      presets.value = response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to fetch presets'
      console.error('[DEBUG] fetchPresets failed:', e)
    } finally {
      loading.value = false
    }
  }

  /**
   * 创建新角色，成功后立即追加到本地缓存，避免再次拉取列表。
   * 返回值：成功为新建实体，失败为 null（不抛错，错误写入 error 供 UI 展示）。
   */
  async function createCharacter(data: CharacterRequest): Promise<Character | null> {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.create(data)
      characters.value.push(response.data)
      return response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to create character'
      console.error('[DEBUG] createCharacter failed:', e)
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * 更新指定角色，本地用 findIndex 定位后原地替换（保留数组顺序与引用稳定的 UI）。
   * 若本地不存在该 id（极端并发：被其他端删除），静默忽略并直接返回服务端最新实体。
   */
  async function updateCharacter(id: string, data: CharacterRequest): Promise<Character | null> {
    loading.value = true
    error.value = null
    try {
      const response = await charactersApi.update(id, data)
      const index = characters.value.findIndex(c => c.id === id)
      if (index !== -1) {
        characters.value[index] = response.data
      }
      return response.data
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to update character'
      console.error('[DEBUG] updateCharacter failed:', e)
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * 删除角色，乐观失败语义：服务端成功才从本地缓存移除，避免「后端拒绝但前端消失」的不一致。
   * 返回 boolean 给上层（场景/房间）做后续清理判断。
   */
  async function deleteCharacter(id: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      await charactersApi.remove(id)
      characters.value = characters.value.filter(c => c.id !== id)
      return true
    } catch (e: any) {
      error.value = e.response?.data?.message || e.response?.data?.error || e.message || 'Failed to delete character'
      console.error('[DEBUG] deleteCharacter failed:', e)
      return false
    } finally {
      loading.value = false
    }
  }

  function getCharacterById(id: string): Character | undefined {
    return characters.value.find(c => c.id === id)
  }

  /**
   * 上传头像并返回可访问的 URL（写入角色表单的 avatar 字段）。
   * 与其他 action 不同：不修改 store 内部状态、不操作 loading/error，因为这是被表单调用方管理的辅助接口。
   */
  async function uploadAvatar(file: File): Promise<string | null> {
    try {
      const response = await charactersApi.uploadAvatar(file)
      return response.data.url
    } catch (e: any) {
      console.error('[DEBUG] uploadAvatar failed:', e)
      return null
    }
  }

  return {
    characters,
    presets,
    loading,
    error,
    userCharacters,
    hasDuplicateName,
    fetchCharacters,
    fetchPresets,
    createCharacter,
    updateCharacter,
    deleteCharacter,
    getCharacterById,
    uploadAvatar
  }
})
