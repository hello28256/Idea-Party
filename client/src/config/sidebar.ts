// Centralized sidebar nav configuration.
// AppSidebar (components/ui/AppSidebar.vue) reads from this list.
// 侧边栏导航配置中心：所有路由入口在此声明一次，
// 视图层（AppSidebar、各 view）只通过 ALL_NAV_ITEMS / MINIMAL_NAV_ITEMS 取自己需要的子集。
// 之所以集中：新增/重命名路由只需改此处，避免在多个 view 文件里重复硬编码。

// 导航项契约：id 在同一数组内唯一，用于匹配 activeId 与过滤；route 支持 path 或 path+query，
// 由消费方在 router.push / RouterLink 中直接消费，组件本身不做跳转解析。
export interface NavItem {
  id: string
  label: string
  emoji: string
  route: string
}

// All available nav entries. Each view picks the subset it needs.
// 单一数据源：避免在各 view 中重复维护菜单项，新增/重命名路由只需改此处；
// view 通过传 :navItems 自行筛选，AppSidebar 保持无业务态、可复用。
export const ALL_NAV_ITEMS: NavItem[] = [
  { id: 'discover', label: '发现', emoji: '🔍', route: '/rooms' },
  { id: 'characters', label: '角色库', emoji: '📚', route: '/characters' },
  { id: 'scenarios', label: '场景', emoji: '💡', route: '/scenarios' },
  { id: 'trending', label: '热门', emoji: '🔥', route: '/rooms?tab=trending' },
  { id: 'categories', label: '分类', emoji: '📂', route: '/rooms?tab=categories' },
  { id: 'my-rooms', label: '我的聊天', emoji: '💬', route: '/rooms?tab=my-rooms' }
]

// Minimal set used by RoomListView (no 热门/分类 entries).
// 在 RoomListView 内 热门/分类 已作为 tab 同页呈现，侧栏再列会与顶部 tab 重复造成视觉冗余；
// 通过过滤而非硬编码子集，保证 ALL_NAV_ITEMS 调整时 MINIMAL 仍自动同步。
export const MINIMAL_NAV_ITEMS: NavItem[] = ALL_NAV_ITEMS.filter(item =>
  !['trending', 'categories'].includes(item.id)
)
