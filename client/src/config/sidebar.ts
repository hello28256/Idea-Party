// 侧边栏导航配置中心：所有路由入口在此声明一次，
// 视图层（AppSidebar、各 view）只通过 ALL_NAV_ITEMS / MINIMAL_NAV_ITEMS 取自己需要的子集。
// 之所以集中：新增/重命名路由只需改此处，避免在多个 view 文件里重复硬编码。

// 导航项契约：id 在同一数组内唯一，用于匹配 activeId 与过滤；route 支持 path 或 path+query，
// 由消费方在 router.push / RouterLink 中直接消费，组件本身不做跳转解析。
// icon 与 emoji 二选一：icon（lucide-vue-next 组件名）优先；emoji 缺失或 icon 缺失时模板跳过渲染，
// 避免空 span 占位导致该项文字左对齐位置与其他项不一致。
export interface NavItem {
  id: string
  label: string
  icon?: string
  emoji?: string
  route: string
}

// 单一数据源：避免在各 view 中重复维护菜单项，新增/重命名路由只需改此处；
// view 通过传 :navItems 自行筛选，AppSidebar 保持无业务态、可复用。
// icon 选用 lucide-vue-next（24px 描边、currentColor 跟随父元素色）。
// 选择依据：emoji 在侧栏里风格不统一（不同字体供应商各画各的），且颜色不能随 active 深色块变化；
// lucide 描边统一、可 currentColor 跟随 active 白字。
export const ALL_NAV_ITEMS: NavItem[] = [
  { id: 'discover', label: '发现', icon: 'Compass', route: '/rooms' },
  { id: 'characters', label: '角色库', icon: 'UsersRound', route: '/characters' },
  { id: 'scenarios', label: '场景', icon: 'Sparkles', route: '/scenarios' },
  { id: 'trending', label: '热门', icon: 'Flame', route: '/rooms?tab=trending' },
  { id: 'categories', label: '分类', icon: 'FolderOpen', route: '/rooms?tab=categories' },
  { id: 'my-rooms', label: '我的聊天', icon: 'MessageSquare', route: '/rooms?tab=my-rooms' }
]

// 在 RoomListView 内 热门/分类 已作为 tab 同页呈现，侧栏再列会与顶部 tab 重复造成视觉冗余；
// 通过过滤而非硬编码子集，保证 ALL_NAV_ITEMS 调整时 MINIMAL 仍自动同步。
export const MINIMAL_NAV_ITEMS: NavItem[] = ALL_NAV_ITEMS.filter(item =>
  !['trending', 'categories'].includes(item.id)
)
