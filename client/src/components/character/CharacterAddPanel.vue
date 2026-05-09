<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Character } from '@/types'
import { useCharacterStore } from '@/stores/character'
import CharacterCard from './CharacterCard.vue'
import Button from '@/components/ui/Button.vue'
import Input from '@/components/ui/Input.vue'

interface Props {
  show: boolean
  editingCharacter?: Character | null
}

const props = withDefaults(defineProps<Props>(), {
  editingCharacter: null
})

const emit = defineEmits<{
  close: []
  characterAdded: [character: Character]
}>()

const characterStore = useCharacterStore()

// Form state
interface CharacterForm {
  name: string
  description: string
  avatarUrl: string
  prompt: string
}

const mode = ref<'create' | 'edit'>('create')
const form = ref<CharacterForm>({
  name: '',
  description: '',
  avatarUrl: '',
  prompt: ''
})
const loading = ref(false)
const error = ref<string | null>(null)

// Reset form when props change
watch(() => props.show, (newShow) => {
  if (newShow) {
    if (props.editingCharacter) {
      mode.value = 'edit'
      form.value = {
        name: props.editingCharacter.name,
        description: props.editingCharacter.description || '',
        avatarUrl: props.editingCharacter.avatarUrl || '',
        prompt: props.editingCharacter.prompt || ''
      }
    } else {
      mode.value = 'create'
      form.value = { name: '', description: '', avatarUrl: '', prompt: '' }
    }
    error.value = null
    // Fetch presets if not loaded
    if (characterStore.presets.length === 0) {
      characterStore.fetchPresets()
    }
  }
})

async function handleSave() {
  if (!form.value.name.trim()) {
    error.value = '请输入角色名称'
    return
  }

  loading.value = true
  error.value = null

  try {
    let result: Character | null = null
    if (mode.value === 'edit' && props.editingCharacter) {
      result = await characterStore.updateCharacter(props.editingCharacter.id, form.value)
    } else {
      result = await characterStore.createCharacter(form.value)
    }

    if (result) {
      emit('characterAdded', result)
      emit('close')
    } else {
      error.value = characterStore.error || '操作失败'
    }
  } finally {
    loading.value = false
  }
}

function handlePresetSelect(character: Character) {
  emit('characterAdded', character)
  emit('close')
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="panel">
      <div
        v-if="show"
        class="fixed inset-y-0 right-0 w-80 bg-white shadow-xl z-50 flex flex-col"
      >
        <!-- Header -->
        <div class="flex items-center justify-between p-4 border-b border-[#E5E7EB]">
          <h2 class="text-lg font-semibold text-[#1F2937]">
            {{ mode === 'create' ? '创建角色' : '编辑角色' }}
          </h2>
          <button
            @click="handleClose"
            class="p-1 rounded hover:bg-[#F0FDF4] transition-colors"
          >
            <svg class="w-5 h-5 text-[#6B7280]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Tabs -->
        <div class="flex border-b border-[#E5E7EB]">
          <button
            class="flex-1 py-2 text-sm font-medium text-[#10B981] border-b-2 border-[#10B981]"
          >
            创建角色
          </button>
          <button
            class="flex-1 py-2 text-sm font-medium text-[#6B7280] hover:text-[#1F2937]"
          >
            角色库
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 overflow-y-auto p-4">
          <!-- Create Tab -->
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-[#1F2937] mb-1">
                角色名称 <span class="text-[#EF4444]">*</span>
              </label>
              <Input
                v-model="form.name"
                placeholder="请输入角色名称"
              />
            </div>

            <div>
              <label class="block text-sm font-medium text-[#1F2937] mb-1">
                角色描述
              </label>
              <textarea
                v-model="form.description"
                rows="3"
                placeholder="请输入角色描述"
                class="w-full px-3 py-2 text-sm border border-[#E5E7EB] rounded-lg focus:outline-none focus:ring-2 focus:ring-[#10B981] focus:border-transparent resize-none"
              ></textarea>
            </div>

            <div>
              <label class="block text-sm font-medium text-[#1F2937] mb-1">
                头像 URL
              </label>
              <Input
                v-model="form.avatarUrl"
                placeholder="https://example.com/avatar.jpg"
              />
            </div>

            <div>
              <label class="block text-sm font-medium text-[#1F2937] mb-1">
                角色设定 (Prompt)
              </label>
              <textarea
                v-model="form.prompt"
                rows="4"
                placeholder="输入角色设定，用于定义 AI 角色的行为和风格"
                class="w-full px-3 py-2 text-sm border border-[#E5E7EB] rounded-lg focus:outline-none focus:ring-2 focus:ring-[#10B981] focus:border-transparent resize-none"
              ></textarea>
            </div>

            <p v-if="error" class="text-sm text-[#EF4444]">
              {{ error }}
            </p>
          </div>

          <!-- Library Tab (Preset Characters) -->
          <div v-if="characterStore.presets.length > 0" class="space-y-2 mt-4">
            <p class="text-sm text-[#6B7280] mb-2">点击选择预设角色：</p>
            <CharacterCard
              v-for="preset in characterStore.presets"
              :key="preset.id"
              :character="preset"
              @select="handlePresetSelect"
            />
          </div>
        </div>

        <!-- Footer -->
        <div class="p-4 border-t border-[#E5E7EB]">
          <Button
            @click="handleSave"
            :loading="loading"
            variant="primary"
            class="w-full"
          >
            {{ mode === 'create' ? '创建' : '保存修改' }}
          </Button>
        </div>
      </div>
    </Transition>

    <!-- Backdrop -->
    <Transition name="fade">
      <div
        v-if="show"
        class="fixed inset-0 bg-black/20 z-40"
        @click="handleClose"
      ></div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.panel-enter-active,
.panel-leave-active {
  transition: transform 0.3s ease;
}

.panel-enter-from,
.panel-leave-to {
  transform: translateX(100%);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
