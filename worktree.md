一、核心基础命令（必学）
1. 初始化 / 新增工作树（最常用）
# 1. 先进入主仓库目录（比如你的项目根目录）
cd your-project

# 2. 新增工作树：把指定分支检出到指定目录
git worktree add ../your-project-feature feature-branch
# 解释：
# ../your-project-feature → 新工作树的目录（在主仓库外，避免冲突）
# feature-branch → 要检出的分支名（可以是已存在/新建分支）

# 3. 新增并创建新分支（比如专门开发语音克隆的分支）
git worktree add -b voice-clone ../your-project-voice master
# -b voice-clone → 基于master创建新分支voice-clone，并检出到指定目录
2. 查看所有工作树
git worktree list
# 输出示例：
# /path/to/your-project  (master)
# /path/to/your-project-voice  (voice-clone)
3. 删除工作树（完成开发后清理）
# 1. 先退出要删除的工作树目录，回到主仓库
cd your-project

# 2. 删除工作树（-f 强制删除未提交的修改，谨慎用）
git worktree remove ../your-project-voice
# 或强制删除：
git worktree remove -f ../your-project-voice
4. 修复损坏的工作树（偶尔遇到）
git worktree repair

# ####我是张山
helo你好