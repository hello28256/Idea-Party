-- V8__create_user_scenarios.sql
-- 用户私有场景表。
-- 与 characters 表的"预设/用户共享 + is_preset 区分"模式不同：
-- 预设场景仍是前端硬编码常量（22 条 SEED_SCENARIOS），不会落库；
-- 本表只承载用户私有场景，因此不需要 is_preset 字段，所有行都属于某个 owner。
-- 结构更简单，查询/索引更直白。

CREATE TABLE user_scenarios (
  id              CHAR(36)     NOT NULL,
  owner_id        CHAR(36)     NOT NULL,
  emoji           VARCHAR(8)   NOT NULL,
  title           VARCHAR(100) NOT NULL,
  description     VARCHAR(500) NOT NULL,
  character_name  VARCHAR(100) NOT NULL,
  user_input_label        VARCHAR(100) NULL,
  user_input_placeholder  VARCHAR(200) NULL,
  prompt_template TEXT         NOT NULL,
  created_at      DATETIME(6)  NOT NULL,
  updated_at      DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  -- 用户注销自动清理私有场景。
  CONSTRAINT fk_user_scenarios_owner
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
  -- 同 owner 下场景标题唯一，避免"我建了三个「面试」全混在一起"。
  -- 与 characters 表的 uk_characters_owner_name 行为对齐：
  -- 前端查重 + 业务层 findFirst 失效时，DB 仍是最后兜底。
  CONSTRAINT uk_user_scenarios_owner_title UNIQUE (owner_id, title),
  -- 列表按更新时间倒序的联合索引，避免全表排序。
  KEY idx_user_scenarios_owner (owner_id, updated_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
