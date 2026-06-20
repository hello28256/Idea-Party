<script setup lang="ts">
// 通用受控输入框：遵循 Vue3 v-model 标准约定（modelValue + update:modelValue），
// 由父组件持有真实值，便于在登录/注册等场景统一做校验、提交与重置。
// 非全局单例：每个表单字段实例化一次（登录页用户名/密码、注册表单、反馈备注等）。
//
// type 限定为 HTML5 标准值：复用浏览器原生校验（email/url）与移动端键盘体验（tel/number 唤起数字键盘）。
import { computed } from 'vue'

// Props 契约：限定 type 取值是为复用 HTML5 原生校验（email/url）与移动端键盘体验（tel/number）；
// error 非空时同时驱动红框与错误文案，表单提交前只需赋值即可联动显示。
interface Props {
  modelValue: string
  type?: 'text' | 'email' | 'password' | 'number' | 'tel' | 'url'
  placeholder?: string
  disabled?: boolean
  error?: string
  label?: string
  id?: string
}

const props = withDefaults(defineProps<Props>(), {
  type: 'text',
  placeholder: '',
  disabled: false
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

// 未显式传 id 时生成一个随机 id，保证 label[for] 与 input[id] 始终配对，
// 避免在同一页面多次复用组件时出现 a11y 警告与点击 label 失效的问题。
const inputId = computed(() => props.id || `input-${Math.random().toString(36).slice(2, 9)}`)

// 仅转发原生 input 事件的目标值，保持单一数据源在父组件；
// 不在此处做 trim/格式化等副作用，确保受控输入的可预测性。
function handleInput(event: Event) {
  const target = event.target as HTMLInputElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <div class="flex flex-col gap-1">
    <label
      v-if="label"
      :for="inputId"
      class="text-label text-text-secondary"
    >
      {{ label }}
    </label>
    <input
      :id="inputId"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :class="[
        'input',
        {
          'border-destructive focus:border-destructive focus:ring-red-100': error,
          'opacity-50 cursor-not-allowed': disabled
        }
      ]"
      @input="handleInput"
    />
    <span
      v-if="error"
      class="text-sm text-destructive"
    >
      {{ error }}
    </span>
  </div>
</template>
