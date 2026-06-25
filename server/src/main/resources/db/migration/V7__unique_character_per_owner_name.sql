-- V7__unique_character_per_owner_name.sql
-- 防止同一 owner 下出现同名角色（用户私有 + 预设角色共享同一张表，靠 is_preset 区分）。
-- 在前端 in-flight 锁 + CharacterService.create 的 findFirstByOwnerId 去重都失效时，
-- 数据库唯一约束是最后一道兜底：并发 INSERT 同名角色会直接报错，由 Service 层捕获
-- DataIntegrityViolationException 后回退到 findFirstByOwnerIdAndNameAndIsPresetFalse 返回已存在记录。
-- 索引定义与 Character entity 的 uniqueConstraints 保持一致，
-- 避免 Hibernate ddl-auto=update 与手动 schema 漂移。

ALTER TABLE characters
ADD CONSTRAINT uk_characters_owner_name UNIQUE (owner_id, name);