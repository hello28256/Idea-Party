package com.ideaparty.dto;

public enum DiscussionPhase {
    IDLE,           // 无讨论
    MODERATING,    // Moderator 分析中
    SPEAKING,       // AI 角色发言中
    WAITING_FOR_USER, // 等待用户输入
    PAUSED          // 用户手动暂停
}
