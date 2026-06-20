/// <reference types="vite/client" />

// Vite 环境变量与模块声明。
// 1) `/// <reference types="vite/client" />` 把 Vite 内置的 import.meta.env / HMR API 类型引入到全局，
//    否则使用 import.meta.env.VITE_XXX 时 TS 会报"找不到名称"。
// 2) declare module '*.vue' 让 TS 识别 .vue 文件的默认导出为 Vue 组件（DefineComponent），
//    否则 import App from './App.vue' 会因缺少类型而报错。
// 3) declare module '*.css' 让 import './style.css' 之类的副作用导入在 TS 中合法，
//    避免 TS 把 CSS 文件当成缺失模块。
// 注意：本文件仅做类型声明，不输出任何运行时代码，不要在这里写业务逻辑或 import 实际模块。

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

declare module '*.css' {
  const content: string
  export default content
}
