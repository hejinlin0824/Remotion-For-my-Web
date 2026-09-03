# Remotion-For-my-Web — 考研讲题视频批量工厂

把一道题目（文字或截图）变成一支讲题教学视频：**五幕 Remotion 封版模板 + Spring Boot 编排**。
GLM 分片生成讲稿（协调者骨架 → 题干/素材/场景分片）→ V1-V4 内容校验 → Cherry TTS 配音 →
QA 静帧预审 → Remotion 渲染 → GLM 审帧兜底。实测覆盖**数学、计算机 408、信号与系统**等考研科目。

- 16:9 单规格，720p / 1080p 两档（缺省 1080p，开发测试建议显式 720p）
- 全链一遍过 ~30min/题（720p，渲染占 ~16min），API 成本 ~2-5 毛/题
- Web 前端内置（http://localhost:8080），零构建单页，接口已预留网站接入

## 目录结构

```
template/    五幕讲题模板（Remotion 封版，含渲染/QA 脚本；契约详见 template/README.md）
server/      Spring Boot 3 工厂服务（编排/校验/TTS/QA/渲染调度/Web 前端）
docs/        设计 spec 与实施计划
```

## 环境要求（新机器部署清单）

| 依赖 | 版本 | 用途 |
|---|---|---|
| JDK | **21** | 服务端构建与运行 |
| Maven | 3.9+ | 构建 |
| Node.js + npm | 18+ | 模板渲染（Remotion）与依赖安装 |
| Python | 3.9+ | QA 审帧脚本（template/scripts/qa_glm.py） |
| Microsoft Edge | 本机标准路径 | 渲染浏览器。⚠️ `template/remotion.config.ts` 硬编码了
  `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`，路径不符的机器需改成实际浏览器路径
  （`template/scripts/qa_stills.mjs` 内镜像了同一路径常量，两处一起改） |

## 密钥配置（关键：全部 key 统一在一个文件管理）

**唯一密钥源 = `server/secrets.local.yml`**（已被 .gitignore 忽略，绝不入库）：

```bash
cd server
cp secrets.example.yml secrets.local.yml   # 然后编辑 secrets.local.yml 填入两个真实 key
bash start-server.sh                        # 一键启动：自动从该文件提取 key 注入 env（运行时提取，绝不打印）
```

不想用脚本、手动 `export` 也等价（**优先级：环境变量 > secrets.local.yml**，取其一即可）：

```bash
# GLM（智谱）。coding-plan 套餐的 key 必须配合 coding 端点，否则稳定 429：
export ZHIPU_API_KEY="你的智谱key"
export APP_GLM_BASE_URL="https://open.bigmodel.cn/api/coding/paas/v4"   # coding-plan key 必须；标准 API key 可不加（默认 paas/v4）

# TTS（DashScope / Cherry 音色）
export DASHSCOPE_API_KEY="你的百炼key"
```

> 红线：key 绝不打印、绝不入 repo/日志/报告/对话。`secrets.local.yml` 里除两个 key 外还可放
> `glm.base-url` 行（start-server.sh 会读取注入端点）；也可用不入库的该文件覆盖任意 `app.*` 配置。

## 部署与启动（clone 之后三步）

```bash
git clone https://github.com/hejinlin0824/Remotion-For-my-Web.git
cd Remotion-For-my-Web

# ① 模板依赖（node_modules 不入库，必须装）
cd template && npm install && cd ..

# ② 密钥：server/secrets.local.yml 已填好即免（见上一节；否则手动 export 两条）

# ③ 启动服务（首次会自动建 H2 库 server/data/jobs）
cd server
JAVA_HOME="<你的JDK21路径>" mvn spring-boot:run
```

看到 `Started FactoryApplication` 即起净（~3s）。浏览器打开 **http://localhost:8080**：

- **文字题**：粘贴题干 → 直接进全链（生成→校验→配音→QA→渲染）；
- **图片题**：传题图 → 识图停驻「待确认」→ 看 KaTeX 渲染的识图结果，**确认 / 修改 / 取消** 三选一
  （确认闸防乱传图：废图/无关图会被直接驳回并给出原因）；
- 进度条实时分段显示，DONE 后页内直接播放，产物存 `artifacts/{jobId}/`（成片+TTS 行音频+识图结果）。

打包部署（替代 spring-boot:run）：

```bash
cd server && mvn -q package -DskipTests
JAVA_HOME="<JDK21>" ZHIPU_API_KEY=... DASHSCOPE_API_KEY=... \
  APP_GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4 \
  java -jar target/*.jar
```

## 常用配置（server/src/main/resources/application.yml）

| 配置 | 缺省 | 说明 |
|---|---|---|
| `app.pipeline.extract-confirm` | true | IMAGE 确认闸开关；false=图片全自动 |
| `app.pipeline.max-revise` | 10 | 识图「修改重审」次数上限 |
| 分辨率 | 1080p | 提交时按题指定 `720p` / `1080p` |
| `app.retry.wall-clock-deadline-minutes` | 60 | 全局墙钟，超线判 FAILED |
| `app.cleanup.keep-tts-audio` | true | DONE 后保留 TTS 音频到 artifacts |
| `app.glm.*` / `app.render.*` / `app.qa.*` | 见 yml | 并发/超时/QA 轮数等 |

API 一览：`POST /api/v1/jobs`（单题）、`POST /api/v1/jobs/batch`（批量）、`GET /api/v1/jobs/{id}`（状态）、
`GET …/video`（成片）、`POST …/confirm|…/revise`（确认闸）、`DELETE …`（取消）、`GET …/review-errors`（驳回取证）。

## 已知注意点（实战沉淀）

- curl 发中文 JSON 在 Git Bash 必须用 `--data-binary @file.json`，内联必哑火；
- GLM 深夜（03:00-05:42 历史窗口）长生成不稳，批量生产排白天；
- 渲染是耗时大头（720p ~16min），本机核显无 NVENC，数量级提速需多机/Remotion Lambda；
- 首次渲染 Remotion 可能下载浏览器组件，需外网；代理环境给 npm 配 `HTTPS_PROXY`，服务进程加 `NO_PROXY='*'`。

## 测试

```bash
cd server && JAVA_HOME="<JDK21>" NO_PROXY='*' mvn -q test    # 390 项全绿（另 10 项 slow IT 需真实 key 单独跑）
```

## 更多文档

- `template/README.md` — 模板渲染/QA 工具操作契约（与代码逐项核对）
- `docs/superpowers/specs/` — 系统设计 spec；`docs/superpowers/plans/` — 实施计划与验收报告
