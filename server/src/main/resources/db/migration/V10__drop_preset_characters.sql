-- V10__drop_preset_characters.sql
-- 移除 characters 表里的 120 条预设角色（is_preset=TRUE）。
--
-- 背景：
-- 预设角色（孔子/爱因斯坦/贝多芬 等 36+84 条）此前持久化在 characters 表里，
-- 但它们的实际"权威数据"已经迁移到 classpath 资源 presets.json，由
-- PresetCharacterCache 启动时一次性加载到 JVM 内存。
-- 让 DB 不再持有这 120 条可以：
--   1) GET /api/characters/recommended 走纯内存，0 DB 查询
--   2) 部署新版本不再需要 V1~V9 那些数据迁移，DB schema 更轻
--   3) preset 修改走 git diff，不再需要 SQL 脚本
--
-- 前置安全检查（在执行本脚本前必须满足）：
--   - 0 个房间（通过 room_characters）引用 preset
--   - 0 条消息（messages.character_id）来自 preset
--   - 0 条 expertise/observation 关联 preset
-- 上述条件在 V10 部署前已手动验证（5 个孤儿房间的 room_characters 关联行
-- 已迁移到同名用户 clone，或删除；其他表 0 引用）。
--
-- 风险：执行后 is_preset=TRUE 的所有行被物理删除，preset 数据只保留在
-- classpath:presets.json。如果新版本启动时 presets.json 缺失或损坏，
-- GET /api/characters/recommended 会抛 500 — 这是预期行为（fail fast），
-- 比"DB 还在写但 presets.json 也写了一份"的双源混乱好。

-- 1) 清理理论上可能仍残留的引用（防御性，实际应该 0 行）
DELETE FROM character_expertise
WHERE character_id IN (SELECT id FROM characters WHERE is_preset = TRUE);

-- 2) 物理删除 120 条 preset
DELETE FROM characters WHERE is_preset = TRUE;
