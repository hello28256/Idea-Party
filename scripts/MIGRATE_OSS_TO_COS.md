# OSS → COS 数据迁移指南

PR3 完成后,前端用户访问的图片 URL 改走腾讯云 COS。但 1200+ 头像文件
还在阿里云 OSS 桶 `idea-party-uploads` 里。需要一次性拷到 COS。

## 准备(只做一次)

### 1. 阿里云 RAM 加 ListObject 权限

迁移脚本要 `oss:ListObject` 列出桶对象。当前 RAM 角色
`idea-party-uploader` 只有 `oss:PutObject` 权限,**ListObject 失败**。

路径:
1. https://ram.console.aliyun.com/roles
2. 找 `idea-party-uploader` → 点进去
3. **权限管理** tab → 找绑的策略(可能叫 `ideaparty-oss-put-only`)
4. 点策略 → **修改策略内容**
5. 加这个 Action:
   ```json
   {
     "Action": "oss:ListObjects",
     "Resource": "acs:oss:*:*:idea-party-uploads/uploads/*",
     "Effect": "Allow"
   }
   ```
6. 保存策略

### 2. 服务器装 aliyun CLI(用于调 STS)

服务器需要 aliyun CLI 拿 STS 临时凭证(走 RAM 角色)然后 OSS API list_objects。
(也可以用 oss2 SDK 的 StsAuth,但 STS 凭证需要从 CLI 拿,麻烦。)

**只** migrate 时需要这一步,跑完可卸载。

## 跑迁移

### Dry-run 测

```bash
cd /opt/ideaparty
python3 scripts/migrate_oss_to_cos.py --dry-run --limit 10
```

应该输出 "找到 N 个文件" 不会真传。

### 真正跑

```bash
# 后台跑, 写日志
nohup python3 scripts/migrate_oss_to_cos.py > /tmp/migrate.log 2>&1 &

# 实时看进度
tail -f /tmp/migrate.log

# 跑完看统计
grep "^== 完成 ==" /tmp/migrate.log
```

预计耗时:
- 1200+ 文件, 每个 ~30KB (大头像 525KB)
- 总数据 ~30-50 MB
- 跨云 跨境 ~50 KB/s 上传(慢)
- 预计 10-20 分钟

## 跑完验证

```bash
# 抽查几个 key
python3 -c "
from qcloud_cos import CosConfig, CosS3Client
import os
c = CosS3Client(CosConfig(Region='ap-seoul', SecretId=os.environ['TENCENT_COS_SECRET_ID'], SecretKey=os.environ['TENCENT_COS_SECRET_KEY'], Scheme='https'))
for key in ['uploads/avatars/brand/image.png', 'uploads/avatars/presets/0.png', 'uploads/avatars/scenarios/scn-interview-coach.jpg']:
    try:
        r = c.head_object(Bucket='idea-party-uploads-1361890600', Key=key)
        print(f'✅ {key} ({r[\"ContentLength\"]} bytes)')
    except Exception as e:
        print(f'❌ {key}: {e}')
"
```

## 跑完清理

迁移完成后,前端用户才能看到图片(COS 上有文件)。**然后才能 push PR3 部署**。

阿里云 OSS 数据**保留 1-2 周** 观察,确认 COS 上都正常访问后再 ossutil rm 删 OSS 上的旧文件。
