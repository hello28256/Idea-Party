-- V11__migrate_avatar_urls_to_oss.sql
-- 把所有指向本地 /uploads/ 或 /api/upload/avatars/ 的 avatar_url 一次性改成
-- 完整的阿里云 OSS URL(华南1 桶 idea-party-uploads, 公共读, 浏览器直连)。
--
-- 设计:
--   1. WHERE 限定相对路径,完整 URL(https://) 跳过(已是 OSS / 外链直链)
--   2. CONCAT 把 bucket domain 拼到相对路径前(去前导 /)
--   3. 幂等:已是 OSS URL 的不会被这条 SQL 改(因为不以 /uploads 开头)
--
-- 前置:Commit 6 的迁移脚本必须先跑(server/uploads/avatars/ → oss://idea-party-uploads/uploads/avatars/),
--      否则 V11 跑完后老 URL 变成 OSS URL,浏览器 404。
--
-- 桶名 idea-party-uploads 带横杠,不要写错(记忆 oss-bucket-name-hyphen.md)。

UPDATE characters
SET avatar_url = CONCAT(
    'https://idea-party-uploads.oss-cn-shenzhen.aliyuncs.com',
    SUBSTRING(avatar_url, 1)
)
WHERE avatar_url LIKE '/uploads/%' OR avatar_url LIKE '/api/upload/avatars/%';

UPDATE users
SET avatar_url = CONCAT(
    'https://idea-party-uploads.oss-cn-shenzhen.aliyuncs.com',
    SUBSTRING(avatar_url, 1)
)
WHERE avatar_url LIKE '/uploads/%' OR avatar_url LIKE '/api/upload/avatars/%';
