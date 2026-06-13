// Centralized sidebar nav configuration.
// AppSidebar (components/ui/AppSidebar.vue) reads from this list.

export interface NavItem {
  id: string
  label: string
  emoji: string
  route: string
}

// All available nav entries. Each view picks the subset it needs.
export const ALL_NAV_ITEMS: NavItem[] = [
  { id: 'discover', label: '发现', emoji: '🔍', route: '/rooms' },
  { id: 'characters', label: '角色库', emoji: '📚', route: '/characters' },
  { id: 'scenarios', label: '场景', emoji: '💡', route: '/scenarios' },
  { id: 'trending', label: '热门', emoji: '🔥', route: '/rooms?tab=trending' },
  { id: 'categories', label: '分类', emoji: '📂', route: '/rooms?tab=categories' },
  { id: 'my-rooms', label: '我的聊天', emoji: '💬', route: '/rooms?tab=my-rooms' }
]

// Minimal set used by RoomListView (no 热门/分类 entries).
export const MINIMAL_NAV_ITEMS: NavItem[] = ALL_NAV_ITEMS.filter(item =>
  !['trending', 'categories'].includes(item.id)
)
