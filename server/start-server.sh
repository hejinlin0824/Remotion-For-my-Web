#!/bin/sh
# 工厂服务启动脚本（规范版）。唯一密钥源 = 本目录 secrets.local.yml，key 运行时提取注入 env，绝不打印。
# 用法：bash server/start-server.sh   （子进程 qa_glm.py 等靠 env 继承，故仍需 export）
# 前置：cp secrets.example.yml secrets.local.yml 并填入真实 key（gitignored，绝不入库）。
cd "$(dirname "$0")" || exit 1

SECRETS="secrets.local.yml"
if [ ! -f "$SECRETS" ]; then
  echo "[fatal] 缺少 $SECRETS（密钥统一管理文件）。执行: cp secrets.example.yml secrets.local.yml 后填入真实 key"
  exit 1
fi

# JAVA_HOME 按机修改（要求 JDK 21）
export JAVA_HOME="D:\Java_opensdk_jv21"

# 从 secrets.local.yml 按前缀取值（与 Secrets.java 同款两行式解析；命中前输出 SET，绝不回显值）
get() {
  python -X utf8 -c "
import sys
for line in open(sys.argv[1], encoding='utf-8'):
    t = line.strip()
    if t.startswith('#') or not t.startswith(sys.argv[2]):
        continue
    v = t[len(sys.argv[2]):].strip().strip('\"').strip(\"'\")
    if v:
        print(v)
        break
" "$SECRETS" "$1"
}

ZHIPU_API_KEY=$(get 'glm.api-key:')
if [ -z "$ZHIPU_API_KEY" ]; then echo "[fatal] $SECRETS 缺 glm.api-key"; exit 1; fi
export ZHIPU_API_KEY

DASHSCOPE_API_KEY=$(get 'tts.api-key:')
if [ -z "$DASHSCOPE_API_KEY" ]; then echo "[fatal] $SECRETS 缺 tts.api-key"; exit 1; fi
export DASHSCOPE_API_KEY

BASE_URL=$(get 'glm.base-url:')
if [ -n "$BASE_URL" ]; then export APP_GLM_BASE_URL="$BASE_URL"; fi

echo "env ready: ZHIPU_API_KEY=SET DASHSCOPE_API_KEY=SET APP_GLM_BASE_URL=${APP_GLM_BASE_URL:-<default paas/v4>}"
exec mvn -q spring-boot:run
