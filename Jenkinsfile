// =============================================================================
// Idea-Party 部署流水线 (腾讯云 CODING 持续集成)
//
// 这个文件是 CODING 的 Jenkinsfile 声明式 pipeline. 触发后:
//   1. Pre-flight:  SSH 到 VPS, 确认 .env.production 存在且 6 个关键 env 在
//   2. Build:       checkout 仓库, materialize SSH key
//   3. Deploy:      在 runner (深圳/上海节点) 上跑 deploy.py --skip-uploads --use-tar
//                  (tar 流 + SSH 把代码推到腾讯云上海 VPS, 走内网 1-3 MB/s, 不再跨境)
//   4. Smoke check: SSH 到 VPS 跑 deploy.py --status 验证容器 healthy
//
// CODING runner 在大陆, 跟目标 VPS 同服务商 (腾讯云), 走内网, 之前 GitHub
// Actions 海外 runner 跨境 14m+ 的瓶颈消失.
//
// 配置 Secret (在 CODING 项目 -> 凭据管理 里加):
//   - DEPLOY_HOST       VPS IP, 150.158.137.186
//   - DEPLOY_USER       VPS 用户名, ubuntu
//   - DEPLOY_SSH_KEY    SSH 私钥 (与 GitHub Actions 的 DEPLOY_SSH_KEY 内容相同)
// =============================================================================

pipeline {
    agent any

    // 限制不跑太频繁: push 触发, 走防抖
    triggers {
        // CODING 自动配 webhook, push 到 main 触发. 不需 polling.
    }

    options {
        // 30 分钟超时, 正常应该 1-3 分钟跑完
        timeout(time: 30, unit: 'MINUTES')
        // 不并发跑两次 (取消旧的, 保留新的)
        disableConcurrentBuilds()
    }

    environment {
        DEPLOY_REMOTE_DIR       = '/opt/ideaparty'
        DEPLOY_REMOTE_ENV_FILE  = '.env.production'
        DEPLOY_UPLOADS_VOLUME   = 'idea-server-uploads'
        DEPLOY_UPLOADS_IMAGE    = 'alpine:3.19'
        DEPLOY_UPLOADS_MIN_PRESETS = '100'
    }

    stages {
        stage('Pre-flight') {
            steps {
                sh '''
                    set -e
                    # 6 个关键 env 必须存在
                    REQUIRED_KEYS="DEEPSEEK_API_KEY ALIYUN_OSS_BUCKET ALIYUN_OSS_ENDPOINT ALIYUN_STS_ACCESS_KEY_ID ALIYUN_STS_ACCESS_KEY_SECRET ALIYUN_STS_ROLE_ARN"
                    ssh -i ~/.ssh/deploy_key -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
                        ${DEPLOY_USER}@${DEPLOY_HOST} "
                        set -e
                        cd ${DEPLOY_REMOTE_DIR} || { echo '::error::VPS 上 ${DEPLOY_REMOTE_DIR} 不存在'; exit 1; }
                        test -f ${DEPLOY_REMOTE_ENV_FILE} || { echo '::error::缺少 ${DEPLOY_REMOTE_ENV_FILE}'; exit 1; }
                        for key in $REQUIRED_KEYS; do
                            grep -q \"^\\${key}=.\\\\+\" ${DEPLOY_REMOTE_ENV_FILE} || {
                                echo \"::error::缺少 \\${key}\"; exit 1;
                            }
                        done
                        echo '✅ env file OK'
                    "
                '''
            }
        }

        stage('Materialize SSH key') {
            steps {
                sh '''
                    set -e
                    mkdir -p ~/.ssh
                    chmod 700 ~/.ssh
                    printf '%s\n' "$DEPLOY_SSH_KEY" > ~/.ssh/deploy_key
                    chmod 600 ~/.ssh/deploy_key
                    ssh-keyscan -H "$DEPLOY_HOST" >> ~/.ssh/known_hosts 2>/dev/null || true
                '''
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                    set -euo pipefail
                    # --skip-uploads: 不跑 Step 1.5 / 1.6, 那俩是大文件 / OSS 调用,
                    # CD 不必每次跑, uploads 同步留作运维手动 ./scripts/oss-sync-avatars.sh。
                    # --use-tar: tar 流式同步, 单连接 + 压缩, 适合 CI runner 跑 deploy.py。
                    python3 deploy.py --skip-uploads --use-tar
                '''
            }
        }

        stage('Smoke check') {
            steps {
                sh '''
                    set -e
                    ssh -i ~/.ssh/deploy_key -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
                        ${DEPLOY_USER}@${DEPLOY_HOST} "
                        set -e
                        cd ${DEPLOY_REMOTE_DIR}
                        python3 deploy.py --status 2>&1 | tail -n 20
                    "
                '''
            }
        }
    }

    post {
        always {
            // 清理临时 SSH key, 不留 artifact
            sh 'rm -f ~/.ssh/deploy_key || true'
        }
        success {
            echo '✅ Deploy 成功'
        }
        failure {
            echo '❌ Deploy 失败, 看上面 stage 日志定位'
        }
    }
}
