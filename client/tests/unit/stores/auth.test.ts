import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'

// Mock the API
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn()
}))

import { login as loginApi, register as registerApi } from '@/api/auth'

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('should have empty user on init', () => {
    const store = useAuthStore()
    expect(store.user).toBeNull()
    expect(store.accessToken).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('should set user after login', async () => {
    const mockUser = {
      id: 'user-123',
      email: 'test@example.com',
      name: 'Test User'
    }
    const mockResponse = {
      data: {
        accessToken: 'mock-token-123',
        user: mockUser
      }
    }

    vi.mocked(loginApi).mockResolvedValue(mockResponse)

    const store = useAuthStore()
    await store.login('test@example.com', 'password123')

    expect(store.user).toEqual(mockUser)
    expect(store.accessToken).toBe('mock-token-123')
    expect(store.isAuthenticated).toBe(true)
    expect(loginApi).toHaveBeenCalledWith({ email: 'test@example.com', password: 'password123' })
  })

  it('should clear user after logout', () => {
    const store = useAuthStore()

    // First login to set state
    store.user = { id: 'user-123', email: 'test@example.com', name: 'Test User' }
    store.accessToken = 'mock-token-123'

    // Then logout
    store.logout()

    expect(store.user).toBeNull()
    expect(store.accessToken).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('isAuthenticated should return correct value based on user presence', () => {
    const store = useAuthStore()

    // No user, not authenticated
    expect(store.isAuthenticated).toBe(false)

    // User present, authenticated (need both user and token)
    store.user = { id: 'user-123', email: 'test@example.com', name: 'Test User' }
    store.accessToken = 'mock-token'
    expect(store.isAuthenticated).toBe(true)

    // User null, not authenticated even with token
    store.user = null
    expect(store.isAuthenticated).toBe(false)

    // Token null, not authenticated even with user
    store.user = { id: 'user-123', email: 'test@example.com', name: 'Test User' }
    store.accessToken = null
    expect(store.isAuthenticated).toBe(false)
  })

  it('should register new user', async () => {
    const mockUser = {
      id: 'user-456',
      email: 'new@example.com',
      name: 'New User'
    }
    const mockResponse = {
      data: {
        accessToken: 'mock-token-456',
        user: mockUser
      }
    }

    vi.mocked(registerApi).mockResolvedValue(mockResponse)

    const store = useAuthStore()
    await store.register('New User', 'new@example.com', 'password123')

    expect(store.user).toEqual(mockUser)
    expect(store.accessToken).toBe('mock-token-456')
    expect(store.isAuthenticated).toBe(true)
    expect(registerApi).toHaveBeenCalledWith({ name: 'New User', email: 'new@example.com', password: 'password123' })
  })

  it('should load user from localStorage on init if exists', () => {
    const mockUser = {
      id: 'user-789',
      email: 'stored@example.com',
      name: 'Stored User'
    }

    // Mock localStorage to return stored data
    vi.stubGlobal('localStorage', {
      getItem: vi.fn((key: string) => {
        if (key === 'user') return JSON.stringify(mockUser)
        if (key === 'accessToken') return 'stored-token-789'
        return null
      }),
      setItem: vi.fn(),
      removeItem: vi.fn(),
      clear: vi.fn()
    })

    const store = useAuthStore()
    expect(store.user).toEqual(mockUser)
    expect(store.accessToken).toBe('stored-token-789')
  })
})
