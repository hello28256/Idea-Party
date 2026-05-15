# 设置页面账户和偏好系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现完整的账户设置（头像、用户名、显示名、邮箱修改）和偏好设置（主题模式）功能

**Architecture:**
- 后端：Spring Boot + JPA，JWT 认证，文件上传到 `uploads/avatars/`
- 前端：Vue 3 + Pinia + TypeScript，设置页分 Tab（账户设置、偏好设置）
- 主题：system/light/dark 三模式，支持跟随系统，持久化到数据库

**Tech Stack:** Spring Boot 3.5, Vue 3, Pinia, TypeScript, MySQL

---

## 文件结构

```
server/
├── src/main/java/com/ideaparty/
│   ├── controller/
│   │   └── UserController.java          # 新增：头像上传、偏好设置、获取资料
│   ├── dto/
│   │   ├── UpdatePreferencesRequest.java # 新增
│   │   ├── UserProfileResponse.java     # 新增
│   │   └── AvatarUploadResponse.java    # 新增
│   ├── entity/
│   │   └── User.java                   # 修改：添加 avatarUrl, themeMode 字段
│   ├── repository/
│   │   └── UserRepository.java         # 修改：添加 existsByUsernameAndIdNot, existsByEmailAndIdNot
│   └── config/
│       └── WebConfig.java              # 新增：静态资源映射
client/
├── src/
│   ├── views/
│   │   └── SettingsView.vue            # 重构：添加账户设置和偏好设置 Tab
│   ├── stores/
│   │   ├── auth.ts                     # 修改：添加 fetchProfile, uploadAvatar 方法
│   │   └── theme.ts                    # 重构：支持 system/light/dark，后端持久化
│   ├── api/
│   │   └── user.ts                    # 新增：用户相关 API
│   └── types/
│       └── index.ts                    # 修改：添加 themeMode 类型
```

---

## Task 1: 后端 - User 实体添加缺失字段

**Files:**
- Modify: `server/src/main/java/com/ideaparty/entity/User.java`

- [ ] **Step 1: 在 User.java 中添加 avatarUrl 字段**

在 `lastUsernameChangeAt` 字段后添加：

```java
@Column(name = "avatar_url")
private String avatarUrl;

@Column(name = "theme_mode")
private String themeMode = "system";
```

- [ ] **Step 2: 在 User.java 中添加 themeMode 字段并设置默认值**

如上一步所示，字段已添加。

- [ ] **Step 3: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/entity/User.java
git commit -m "feat(user): add avatar_url and theme_mode fields to User entity"
```

---

## Task 2: 后端 - UserRepository 添加唯一性检查方法

**Files:**
- Modify: `server/src/main/java/com/ideaparty/repository/UserRepository.java`

- [ ] **Step 1: 添加排除当前用户的唯一性检查方法**

```java
boolean existsByUsernameAndIdNot(String username, UUID id);

boolean existsByEmailAndIdNot(String email, UUID id);
```

- [ ] **Step 2: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/repository/UserRepository.java
git commit -m "feat(user): add existsByUsernameAndIdNot and existsByEmailAndIdNot to UserRepository"
```

---

## Task 3: 后端 - 新增 DTO 类

**Files:**
- Create: `server/src/main/java/com/ideaparty/dto/UpdatePreferencesRequest.java`
- Create: `server/src/main/java/com/ideaparty/dto/UserProfileResponse.java`
- Create: `server/src/main/java/com/ideaparty/dto/AvatarUploadResponse.java`

- [ ] **Step 1: 创建 UpdatePreferencesRequest.java**

```java
package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePreferencesRequest {
    private String themeMode;
}
```

- [ ] **Step 2: 创建 UserProfileResponse.java**

```java
package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {
    private UUID id;
    private String username;
    private String displayName;
    private String email;
    private String avatarUrl;
    private Instant usernameUpdatedAt;
    private String themeMode;
}
```

- [ ] **Step 3: 创建 AvatarUploadResponse.java**

```java
package com.ideaparty.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvatarUploadResponse {
    private String avatarUrl;
}
```

- [ ] **Step 4: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/dto/UpdatePreferencesRequest.java server/src/main/java/com/ideaparty/dto/UserProfileResponse.java server/src/main/java/com/ideaparty/dto/AvatarUploadResponse.java
git commit -m "feat(user): add DTO classes for preferences and profile"
```

---

## Task 4: 后端 - 新增 UserController（头像上传、偏好设置、获取资料）

**Files:**
- Create: `server/src/main/java/com/ideaparty/controller/UserController.java`

- [ ] **Step 1: 创建 UserController.java**

```java
package com.ideaparty.controller;

import com.ideaparty.dto.AvatarUploadResponse;
import com.ideaparty.dto.UpdatePreferencesRequest;
import com.ideaparty.dto.UserProfileResponse;
import com.ideaparty.entity.User;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final AuthService authService;

    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5MB
    private static final String UPLOAD_DIR = "uploads/avatars/";

    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(@RequestHeader("Authorization") String authHeader) {
        UUID userId = extractUserIdFromToken(authHeader);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        UserProfileResponse response = UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .usernameUpdatedAt(user.getLastUsernameChangeAt())
                .themeMode(user.getThemeMode() != null ? user.getThemeMode() : "system")
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/avatar")
    public ResponseEntity<AvatarUploadResponse> uploadAvatar(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) {
        UUID userId = extractUserIdFromToken(authHeader);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("头像文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_AVATAR_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的头像格式，仅支持 jpg/jpeg/png/webp");
        }

        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new IllegalArgumentException("头像文件过大，最大 5MB");
        }

        try {
            // Create upload directory if not exists
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Generate unique filename
            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".png";
            String filename = "avatar_" + userId.toString() + "_" + System.currentTimeMillis() + extension;

            // Save file
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath);

            // Update user avatar URL
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
            String avatarUrl = "/uploads/avatars/" + filename;
            user.setAvatarUrl(avatarUrl);
            userRepository.save(user);

            log.info("[DEBUG] [uploadAvatar] userId={}, avatarUrl={}", userId, avatarUrl);

            return ResponseEntity.ok(AvatarUploadResponse.builder()
                    .avatarUrl(avatarUrl)
                    .build());
        } catch (Exception e) {
            log.error("[DEBUG] [uploadAvatar] failed for userId={}, error={}", userId, e.getMessage());
            throw new RuntimeException("头像上传失败");
        }
    }

    @PutMapping("/preferences")
    public ResponseEntity<UserProfileResponse> updatePreferences(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody UpdatePreferencesRequest request) {
        UUID userId = extractUserIdFromToken(authHeader);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // Validate theme mode
        String themeMode = request.getThemeMode();
        if (themeMode == null || (!themeMode.equals("system") && !themeMode.equals("light") && !themeMode.equals("dark"))) {
            throw new IllegalArgumentException("无效的主题模式");
        }

        user.setThemeMode(themeMode);
        user = userRepository.save(user);

        return ResponseEntity.ok(UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .usernameUpdatedAt(user.getLastUsernameChangeAt())
                .themeMode(user.getThemeMode())
                .build());
    }

    private UUID extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        return authService.validateToken(token);
    }
}
```

- [ ] **Step 2: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/controller/UserController.java
git commit -m "feat(user): add UserController with avatar upload and preferences endpoints"
```

---

## Task 5: 后端 - 添加静态资源映射配置

**Files:**
- Create: `server/src/main/java/com/ideaparty/config/WebConfig.java`

- [ ] **Step 1: 创建 WebConfig.java**

```java
package com.ideaparty.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadsPath = Paths.get(System.getProperty("user.dir"), "uploads").toAbsolutePath();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadsPath + "/");
    }
}
```

- [ ] **Step 2: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/config/WebConfig.java
git commit -m "feat(config): add WebConfig for static resource mapping"
```

---

## Task 6: 后端 - 更新 AuthService 的 buildAuthResponse 添加缺失字段

**Files:**
- Modify: `server/src/main/java/com/ideaparty/service/AuthService.java`

- [ ] **Step 1: 更新 buildAuthResponse 方法，添加 avatarUrl 和 themeMode**

找到 `AuthResponse.UserResponse.builder()` 部分，添加：
```java
.avatarUrl(user.getAvatarUrl())
.themeMode(user.getThemeMode() != null ? user.getThemeMode() : "system")
```

修改后：
```java
private AuthResponse buildAuthResponse(String token, User user) {
    return AuthResponse.builder()
            .accessToken(token)
            .tokenType("Bearer")
            .expiresIn(jwtExpiration)
            .user(AuthResponse.UserResponse.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .displayName(user.getDisplayName())
                    .email(user.getEmail())
                    .avatarUrl(user.getAvatarUrl())
                    .lastUsernameChangeAt(user.getLastUsernameChangeAt())
                    .themeMode(user.getThemeMode() != null ? user.getThemeMode() : "system")
                    .build())
            .build();
}
```

- [ ] **Step 2: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/service/AuthService.java
git commit -m "feat(auth): include avatarUrl and themeMode in auth response"
```

---

## Task 7: 后端 - AuthResponse DTO 添加缺失字段

**Files:**
- Modify: `server/src/main/java/com/ideaparty/dto/AuthResponse.java`

- [ ] **Step 1: 在 AuthResponse.UserResponse 内部类中添加 avatarUrl 和 themeMode 字段**

```java
private String avatarUrl;
private String themeMode;
```

并在 builder 中添加对应的 setter。

- [ ] **Step 2: 提交变更**

```bash
git add server/src/main/java/com/ideaparty/dto/AuthResponse.java
git commit -m "feat(auth): add avatarUrl and themeMode to AuthResponse DTO"
```

---

## Task 8: 前端 - 新增 User API 模块

**Files:**
- Create: `client/src/api/user.ts`

- [ ] **Step 1: 创建 user.ts API 模块**

```typescript
import { api } from './auth'
import type { User } from '@/types'

export interface UserProfileResponse {
  id: string
  username: string
  displayName: string
  email: string
  avatarUrl?: string
  usernameUpdatedAt?: string
  themeMode: string
}

export interface UpdatePreferencesRequest {
  themeMode: 'system' | 'light' | 'dark'
}

export const getProfile = () =>
  api.get<UserProfileResponse>('/user/profile')

export const uploadAvatar = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return api.post<{ avatarUrl: string }>('/user/avatar', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export const updatePreferences = (data: UpdatePreferencesRequest) =>
  api.put<UserProfileResponse>('/user/preferences', data)
```

- [ ] **Step 2: 提交变更**

```bash
git add client/src/api/user.ts
git commit -m "feat(api): add user API module for profile, avatar, and preferences"
```

---

## Task 9: 前端 - 更新 AuthResponse 类型

**Files:**
- Modify: `client/src/types/index.ts`

- [ ] **Step 1: 在 User 类型中添加 themeMode 字段**

```typescript
export interface User {
  id: string
  username: string
  displayName: string
  email: string
  avatarUrl?: string
  lastUsernameChangeAt?: string // ISO date string
  createdAt?: string // ISO date string
  themeMode?: 'system' | 'light' | 'dark'
}
```

- [ ] **Step 2: 提交变更**

```bash
git add client/src/types/index.ts
git commit -m "feat(types): add themeMode to User type"
```

---

## Task 10: 前端 - 重构 theme store

**Files:**
- Modify: `client/src/stores/theme.ts`

- [ ] **Step 1: 重构 theme store 支持 system/light/dark**

```typescript
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { updatePreferences } from '@/api/user'

export type ThemeMode = 'system' | 'light' | 'dark'

export const useThemeStore = defineStore('theme', () => {
  const themeMode = ref<ThemeMode>(
    (localStorage.getItem('themeMode') as ThemeMode) || 'system'
  )
  const isDark = ref(getIsDark(themeMode.value))

  function getIsDark(mode: ThemeMode): boolean {
    if (mode === 'dark') return true
    if (mode === 'light') return false
    // system mode
    return window.matchMedia('(prefers-color-scheme: dark)').matches
  }

  function applyTheme() {
    const dark = getIsDark(themeMode.value)
    isDark.value = dark
    if (dark) {
      document.documentElement.classList.add('dark')
      document.documentElement.dataset.theme = 'dark'
    } else {
      document.documentElement.classList.remove('dark')
      document.documentElement.dataset.theme = 'light'
    }
  }

  async function setThemeMode(mode: ThemeMode) {
    themeMode.value = mode
    localStorage.setItem('themeMode', mode)
    applyTheme()
    try {
      await updatePreferences({ themeMode: mode })
    } catch (e) {
      console.error('[DEBUG] Failed to save theme to backend:', e)
    }
  }

  // Initialize theme on store creation
  applyTheme()

  // Watch for system theme changes
  const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
  mediaQuery.addEventListener('change', () => {
    if (themeMode.value === 'system') {
      applyTheme()
    }
  })

  // Watch for changes and apply
  watch(themeMode, applyTheme)

  return {
    themeMode,
    isDark,
    setThemeMode,
    applyTheme
  }
})
```

- [ ] **Step 2: 提交变更**

```bash
git add client/src/stores/theme.ts
git commit -m "feat(theme): refactor to support system/light/dark modes with backend persistence"
```

---

## Task 11: 前端 - 更新 auth store 添加 fetchProfile 和 uploadAvatar

**Files:**
- Modify: `client/src/stores/auth.ts`

- [ ] **Step 1: 添加 import 和新方法**

在 import 部分添加：
```typescript
import { getProfile, uploadAvatar as uploadAvatarApi } from '@/api/user'
```

添加新方法：
```typescript
async function fetchProfile(): Promise<void> {
  try {
    const response = await getProfile()
    const userData = response.data
    user.value = userData
    localStorage.setItem('user', JSON.stringify(userData))
    
    // Sync theme mode to theme store
    if (userData.themeMode) {
      themeStore.setThemeMode(userData.themeMode as 'system' | 'light' | 'dark')
    }
  } catch (e) {
    console.error('[DEBUG] Failed to fetch profile:', e)
  }
}

async function uploadAvatar(file: File): Promise<{ success: boolean; avatarUrl?: string; error?: string }> {
  try {
    const response = await uploadAvatarApi(file)
    const avatarUrl = response.data.avatarUrl
    if (user.value) {
      user.value.avatarUrl = avatarUrl
      localStorage.setItem('user', JSON.stringify(user.value))
    }
    return { success: true, avatarUrl }
  } catch (e: any) {
    const message = e?.response?.data?.message || e?.message || '头像上传失败'
    return { success: false, error: message }
  }
}
```

注意：需要导入 `useThemeStore` 并在 return 中导出 `fetchProfile` 和 `uploadAvatar`。

- [ ] **Step 2: 提交变更**

```bash
git add client/src/stores/auth.ts
git commit -m "feat(auth): add fetchProfile and uploadAvatar methods"
```

---

## Task 12: 前端 - 重构 SettingsView.vue

**Files:**
- Modify: `client/src/views/SettingsView.vue`

- [ ] **Step 1: 重构 SettingsView.vue，添加账户设置和偏好设置 Tab**

这是最大的 UI 改动，需要：
1. 添加侧边栏 Tab 导航（账户设置、偏好设置、AI 配置）
2. 账户设置 Tab：头像上传、用户名、显示名、邮箱编辑
3. 偏好设置 Tab：主题模式选择

参考代码结构：

```vue
<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useSettingsStore } from '@/stores/settings'

const authStore = useAuthStore()
const themeStore = useThemeStore()
const settingsStore = useSettingsStore()

// Tab state
const activeTab = ref<'account' | 'preferences' | 'ai'>('account')

// Account form state
const accountForm = ref({
  username: '',
  displayName: '',
  email: '',
  avatarUrl: ''
})
const avatarFile = ref<File | null>(null)
const avatarPreview = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

// Theme state
const selectedTheme = ref<'system' | 'light' | 'dark'>('system')

// Load data
onMounted(async () => {
  await settingsStore.fetchApiKey()
  await authStore.fetchProfile()
  
  if (authStore.user) {
    accountForm.value = {
      username: authStore.user.username || '',
      displayName: authStore.user.displayName || '',
      email: authStore.user.email || '',
      avatarUrl: authStore.user.avatarUrl || ''
    }
  }
  
  selectedTheme.value = themeStore.themeMode
})

// Watch for user changes
watch(() => authStore.user, (user) => {
  if (user) {
    accountForm.value = {
      username: user.username || '',
      displayName: user.displayName || '',
      email: user.email || '',
      avatarUrl: user.avatarUrl || ''
    }
  }
}, { immediate: true })

// Computed: check if username can be changed
const canChangeUsername = computed(() => {
  if (!authStore.user?.lastUsernameChangeAt) return true
  const lastChange = new Date(authStore.user.lastUsernameChangeAt)
  const daysSinceChange = (Date.now() - lastChange.getTime()) / (1000 * 60 * 60 * 24)
  return daysSinceChange >= 30
})

// Avatar handling
function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files && input.files[0]) {
    const file = input.files[0]
    if (file.size > 5 * 1024 * 1024) {
      saveError.value = '头像文件过大，最大 5MB'
      return
    }
    avatarFile.value = file
    avatarPreview.value = URL.createObjectURL(file)
  }
}

// Save account
async function saveAccount() {
  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  
  try {
    // Upload avatar first if changed
    if (avatarFile.value) {
      const result = await authStore.uploadAvatar(avatarFile.value)
      if (!result.success) {
        saveError.value = result.error || '头像上传失败'
        saving.value = false
        return
      }
      accountForm.value.avatarUrl = result.avatarUrl!
    }
    
    // Update profile
    const result = await authStore.updateProfile({
      username: accountForm.value.username,
      displayName: accountForm.value.displayName,
      email: accountForm.value.email
    })
    
    if (!result.success) {
      saveError.value = result.error || '保存失败'
      saving.value = false
      return
    }
    
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  } catch (e: any) {
    saveError.value = e?.response?.data?.message || e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

// Theme change
function handleThemeChange(mode: 'system' | 'light' | 'dark') {
  selectedTheme.value = mode
  themeStore.setThemeMode(mode)
}

// Check if form has changes
const hasChanges = computed(() => {
  if (!authStore.user) return false
  return accountForm.value.username !== authStore.user.username ||
         accountForm.value.displayName !== authStore.user.displayName ||
         accountForm.value.email !== authStore.user.email ||
         avatarFile.value !== null
})
</script>

<template>
  <div class="settings-page">
    <div class="settings-container">
      <!-- Tab Navigation -->
      <div class="tab-nav">
        <button 
          class="tab-btn" 
          :class="{ active: activeTab === 'account' }"
          @click="activeTab = 'account'"
        >
          账户设置
        </button>
        <button 
          class="tab-btn" 
          :class="{ active: activeTab === 'preferences' }"
          @click="activeTab = 'preferences'"
        >
          偏好设置
        </button>
        <button 
          class="tab-btn" 
          :class="{ active: activeTab === 'ai' }"
          @click="activeTab = 'ai'"
        >
          AI 配置
        </button>
      </div>

      <!-- Account Settings Tab -->
      <div v-if="activeTab === 'account'" class="tab-content">
        <div class="account-card">
          <h2 class="card-title">账户设置</h2>
          
          <!-- Avatar -->
          <div class="avatar-section">
            <div class="avatar-wrapper">
              <img 
                :src="avatarPreview || accountForm.avatarUrl || '/default-avatar.png'" 
                alt="Avatar" 
                class="avatar-img"
              />
              <label class="avatar-edit">
                <input type="file" accept="image/jpeg,image/png,image/webp" @change="handleAvatarChange" hidden />
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
              </label>
            </div>
            <p class="avatar-hint">支持 jpg/png/webp，最大 5MB</p>
          </div>

          <!-- Username -->
          <div class="field-group">
            <label class="field-label">用户名</label>
            <input 
              v-model="accountForm.username"
              :disabled="!canChangeUsername"
              class="field-input"
              placeholder="3-20位字母、数字、下划线"
            />
            <p v-if="!canChangeUsername" class="field-hint error">
              用户名 30 天只能修改一次
            </p>
          </div>

          <!-- Display Name -->
          <div class="field-group">
            <label class="field-label">显示名</label>
            <input 
              v-model="accountForm.displayName"
              class="field-input"
              placeholder="1-50位字符"
            />
          </div>

          <!-- Email -->
          <div class="field-group">
            <label class="field-label">邮箱</label>
            <input 
              v-model="accountForm.email"
              type="email"
              class="field-input"
              placeholder="example@domain.com"
            />
          </div>

          <!-- Save Button -->
          <div class="form-actions">
            <button 
              class="btn-save" 
              @click="saveAccount"
              :disabled="saving || !hasChanges"
            >
              {{ saving ? '保存中...' : '保存' }}
            </button>
          </div>

          <!-- Messages -->
          <Transition name="fade">
            <div v-if="saveSuccess" class="toast success">
              保存成功
            </div>
          </Transition>
          <Transition name="fade">
            <div v-if="saveError" class="toast error">
              {{ saveError }}
            </div>
          </Transition>
        </div>
      </div>

      <!-- Preferences Tab -->
      <div v-if="activeTab === 'preferences'" class="tab-content">
        <div class="account-card">
          <h2 class="card-title">偏好设置</h2>
          
          <!-- Theme Mode -->
          <div class="field-group">
            <label class="field-label">主题模式</label>
            <div class="theme-options">
              <button 
                class="theme-option"
                :class="{ active: selectedTheme === 'system' }"
                @click="handleThemeChange('system')"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="5"/>
                  <line x1="12" y1="1" x2="12" y2="3"/>
                  <line x1="12" y1="21" x2="12" y2="23"/>
                  <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
                  <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
                  <line x1="1" y1="12" x2="3" y2="12"/>
                  <line x1="21" y1="12" x2="23" y2="12"/>
                  <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
                  <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
                </svg>
                <span>跟随系统</span>
              </button>
              <button 
                class="theme-option"
                :class="{ active: selectedTheme === 'light' }"
                @click="handleThemeChange('light')"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="5"/>
                  <line x1="12" y1="1" x2="12" y2="3"/>
                  <line x1="12" y1="21" x2="12" y2="23"/>
                  <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
                  <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
                  <line x1="1" y1="12" x2="3" y2="12"/>
                  <line x1="21" y1="12" x2="23" y2="12"/>
                  <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
                  <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
                </svg>
                <span>浅色模式</span>
              </button>
              <button 
                class="theme-option"
                :class="{ active: selectedTheme === 'dark' }"
                @click="handleThemeChange('dark')"
              >
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>
                </svg>
                <span>深色模式</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- AI Config Tab (keep existing) -->
      <div v-if="activeTab === 'ai'" class="tab-content">
        <!-- 保留原有的 API Key 配置 UI -->
        <div class="account-card">
          <div class="api-key-header">
            <div class="api-key-icon">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>
              </svg>
            </div>
            <div class="api-key-info">
              <h2 class="api-key-title">DeepSeek API Key</h2>
              <p class="api-key-desc">用于 AI 角色对话的 API 密钥</p>
            </div>
          </div>

          <div class="field-group">
            <div class="password-input-wrapper">
              <input
                v-model="settingsStore.deepseekApiKey"
                :type="settingsStore.showApiKey ? 'text' : 'password'"
                placeholder="sk-..."
                class="field-input"
              />
              <button class="toggle-password-btn" @click="settingsStore.toggleShowKey" type="button">
                <!-- toggle icon -->
              </button>
            </div>
          </div>

          <div class="api-key-actions">
            <button class="btn-save" @click="handleSave" :disabled="loading">
              保存
            </button>
            <button class="btn-clear" @click="handleClear" :disabled="!settingsStore.hasApiKey || loading">
              清除
            </button>
          </div>

          <!-- status -->
          <div class="api-key-status">
            <div class="status-dot" :class="{ active: settingsStore.hasApiKey }"></div>
            <span>{{ settingsStore.hasApiKey ? 'API Key 已配置' : 'API Key 未配置' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Tab Navigation */
.tab-nav {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  background: var(--color-ivory);
  padding: 0.5rem;
  border-radius: 1rem;
  border: 1px solid var(--color-border);
}

.tab-btn {
  flex: 1;
  padding: 0.75rem 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
  background: transparent;
  color: var(--color-text-secondary);
}

.tab-btn.active {
  background: var(--color-navy);
  color: white;
}

.tab-btn:hover:not(.active) {
  background: var(--color-cream);
}

/* Account Card */
.account-card {
  background: var(--color-ivory);
  border: 1px solid var(--color-border);
  border-radius: 1rem;
  padding: 1.5rem;
}

.card-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-navy);
  margin: 0 0 1.5rem 0;
}

/* Avatar */
.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 1.5rem;
}

.avatar-wrapper {
  position: relative;
  width: 80px;
  height: 80px;
}

.avatar-img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--color-border);
}

.avatar-edit {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--color-navy);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border: 2px solid white;
}

.avatar-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-top: 0.5rem;
}

/* Field Groups */
.field-group {
  margin-bottom: 1rem;
}

.field-label {
  display: block;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-navy);
  margin-bottom: 0.5rem;
}

.field-input {
  width: 100%;
  padding: 0.625rem 0.875rem;
  font-size: 0.875rem;
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  background: white;
  color: var(--color-text-primary);
}

.field-input:focus {
  outline: none;
  border-color: var(--color-gold);
  box-shadow: 0 0 0 3px var(--color-gold-bg);
}

.field-input:disabled {
  background: var(--color-cream);
  cursor: not-allowed;
}

.field-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  margin-top: 0.25rem;
}

.field-hint.error {
  color: var(--color-destructive);
}

/* Theme Options */
.theme-options {
  display: flex;
  gap: 0.75rem;
}

.theme-option {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 1rem;
  border: 2px solid var(--color-border);
  border-radius: 0.75rem;
  background: white;
  cursor: pointer;
  transition: all 0.2s;
  color: var(--color-text-secondary);
}

.theme-option:hover {
  border-color: var(--color-gold);
}

.theme-option.active {
  border-color: var(--color-navy);
  background: var(--color-navy);
  color: white;
}

.theme-option span {
  font-size: 0.75rem;
  font-weight: 500;
}

/* Form Actions */
.form-actions {
  margin-top: 1.5rem;
}

.btn-save {
  width: 100%;
  padding: 0.75rem 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  border: none;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s;
  background: var(--color-navy);
  color: white;
}

.btn-save:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Toast */
.toast {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  margin-top: 1rem;
}

.toast.success {
  background: #f0fdf4;
  color: #166534;
  border: 1px solid #bbf7d0;
}

.toast.error {
  background: #fef2f2;
  color: #991b1b;
  border: 1px solid #fecaca;
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
```

- [ ] **Step 2: 提交变更**

```bash
git add client/src/views/SettingsView.vue
git commit -m "feat(settings): add account settings and preferences tabs"
```

---

## Task 13: 前端 - 应用启动时加载主题

**Files:**
- Modify: `client/src/main.ts` 或 `client/src/App.vue`

- [ ] **Step 1: 在应用启动时从 localStorage 读取主题并应用**

在 `main.ts` 或 `App.vue` 的 `onMounted` 中添加：

```typescript
import { useThemeStore } from '@/stores/theme'

// In onMounted or app initialization:
const themeStore = useThemeStore()
themeStore.applyTheme()
```

- [ ] **Step 2: 提交变更**

```bash
git add client/src/main.ts
git commit -m "feat(app): apply theme on application startup"
```

---

## 验证清单

### 账户设置
- [ ] 打开 /settings，进入账户设置 Tab
- [ ] 能看到当前头像、用户名、显示名、邮箱
- [ ] 修改显示名，保存成功
- [ ] 修改邮箱，保存成功
- [ ] 用旧邮箱登录失败，用新邮箱登录成功
- [ ] 修改用户名，保存成功
- [ ] 用旧用户名登录失败，用新用户名登录成功
- [ ] 30 天内再次修改用户名，后端拒绝并提示
- [ ] 使用已存在用户名，提示用户名已被占用
- [ ] 使用已存在邮箱，提示邮箱已被占用
- [ ] 上传头像成功，页面头像立即更新
- [ ] 刷新页面后头像仍然正确

### 偏好设置
- [ ] 选择暗黑模式，页面立即变暗
- [ ] 刷新后仍然是暗黑模式
- [ ] 选择浅色模式，页面立即变亮
- [ ] 选择跟随系统，跟随系统主题
- [ ] 重新登录后偏好仍然存在

### 回归
- [ ] AI 配置页不受影响
- [ ] 角色库页面不受影响
- [ ] 登录注册流程不受影响
- [ ] 侧边栏用户信息同步更新
