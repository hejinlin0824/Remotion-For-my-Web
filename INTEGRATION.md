# INTEGRATION.md — 讲题视频工厂 × 网站微服务对接文档

> **读者**：负责把本工厂接入网站的工程智能体。
> **本文承诺**：所有字段名、端点、状态枚举、错误码均与 `d935179` 版源码逐一核对过，可当契约直接写代码。
> **深度文档**：部署看根 `README.md`；模板契约看 `template/README.md`；完整设计 spec 看
> `docs/superpowers/specs/2026-08-28-remotion-java-design.md`；工程全史台账（45 版）看
> `.superpowers/sdd/2026-08-29-phase2-java-service/progress.md`。

---

## 1. 你要接的是什么

一个 REST 微服务（Spring Boot 3 / JDK 21 / 端口 8080），把**一道考研题目（文字或截图）**
变成一支**讲题教学视频 mp4**（五幕：审题 → 分片生成讲稿 → 内容校验 → TTS 配音 → QA 静帧预审 → 渲染）。
内部是 Remotion 封版模板，对外只需关心 HTTP API。实测覆盖数学、计算机 408、信号与系统等考研科目。

```
你的网站 ──HTTP──> 工厂服务(8080) ──> GLM(生成/审校) + DashScope(TTS) + 本地 Remotion 渲染
                    │
                    └─ H2 文件库(server/data/) + 本地盘(artifacts/{jobId}/)
```

关键预期：**全链一遍过 ~30-47min/题（720p）**，API 成本 ~2-5 毛/题。这不是秒级接口，
是**异步任务工厂**——提交后靠轮询或回调收结果。

---

## 2. 部署清单（新机器）

按根 `README.md` 三步：`template && npm install` → 注入密钥 → `mvn spring-boot:run`。
看到 `Started FactoryApplication` 即起净（~3s）。**两个必踩坑提前说**：

1. **Edge 路径**：`template/remotion.config.ts` 硬编码了 Edge 浏览器路径，
   `template/scripts/qa_stills.mjs` 镜像了同一常量——浏览器路径不符的机器**两处一起改**。
2. **GLM 端点**：coding-plan 套餐的 key 必须配 `APP_GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4`，
   否则稳定 429（无渠道，非瞬态）。标准 API key 不用加（默认 paas/v4）。

密钥全部走环境变量（`ZHIPU_API_KEY` / `DASHSCOPE_API_KEY`），**绝不写进任何文件/日志/报告/对话**。

健康检查：`GET /` 返回内置单页 200，或 `GET /api/v1/jobs?size=1` 返回 200 JSON。

---

## 3. API 契约（10 端点，前缀 `/api/v1/jobs`）

统一错误形状：`{"error": "<原因>"}`。Jackson `non_null`：**null 字段不出现在响应里**。

### 3.1 提交单题 — `POST /api/v1/jobs` → `202 {"jobId": "..."}`

```jsonc
{
  "inputType": "TEXT",          // 必填："TEXT" | "IMAGE"
  "text": "题目全文…",           // TEXT 必填非空白
  "imageBase64": "<纯base64>",  // IMAGE 必填（剥掉 data: 前缀）
  "aspect": "16:9",             // 可空，缺省 "16:9"；提供则必须恰为 "16:9"（唯一画幅）
  "voice": "Cherry",            // 可空，缺省 "Cherry"；提供则必须恰为 "Cherry"（唯一音色）
  "resolution": "720p",         // 可空，缺省 "1080p"；可选 "1080p"|"720p"，逐题独立
  "callbackUrl": "https://…"    // 可空，终态回调（见 §6.2）
}
```

校验失败 → `400 {"error": …}`。**注意**：字段非法（aspect/voice/resolution 不在白名单）也是 400。

### 3.2 批量提交 — `POST /api/v1/jobs/batch` → `202 {"jobIds": ["…","…"]}`

```jsonc
{ "items": [ { …同单题… }, { … } ] }
```

**整批预校验：任一非法 → 400 整批拒绝，不产生部分入队**。批量并发受内部信号量约束自动排队。

### 3.3 单查 — `GET /api/v1/jobs/{id}` → `200 JobView`（轮询主端点）

```jsonc
{
  "jobId": "529b0fbd-…",
  "status": "RENDERING",       // 11 态枚举，见 §4
  "stage": "RENDERING",        // 机器阶段名（与 status 同值域，非中文文案）；人类可读进度文案取 stageHistory[].note
  "inputType": "IMAGE",
  "aspect": "16:9", "voice": "Cherry", "resolution": "720p",
  "cancelRequested": false,
  "extractRetries": 0, "genRetries": 0, "reviewRetries": 0,
  "ttsRetries": 0, "qaRounds": 0,
  "lastError": null,            // 取证用：最后一次底层错误原文（可能含长堆栈尾）
  "errorMessage": null,         // 人读用：终态失败原因一句话（前端展示优先用这个）
  "artifactsDir": "…/artifacts/{jobId}",   // 服务端本地路径；网站请走 /video 端点，勿读盘
  "stageHistory": [ { "stage": "QUEUED", "state": "ENTER", "note": "入队", "at": "2026-09-03T16:30:05" }, … ],
  "createdAt": "…", "updatedAt": "…"
}
```

不存在 → `404`。**刻意不回显** text/imageBase64/callbackUrl（泄漏面最小化，别指望从查询里拿回原文）。

### 3.4 列表 — `GET /api/v1/jobs?status=&page=0&size=20`

→ `200 {"content":[JobView…], "totalElements":35, "page":0, "size":20}`。
`status` 必须是合法枚举值否则 400。**实测坑：`size>20` 可能返回空页，分页请 `size≤20` 逐页翻**。

### 3.5 取消 — `DELETE /api/v1/jobs/{id}`

| 场景 | 响应 |
|---|---|
| QUEUED/EXTRACTING/AWAITING_CONFIRM/GENERATING/REVIEWING 中 | `202` 已受理（置 cancelRequested，到检查点落 CANCELLED） |
| QA/RENDERING 中 | `202` 已受理（**不打断进行中的渲染**，阶段完成后检查点取消、成片丢弃不入库） |
| SPEAKING 起 | `409 {"error":"SPEAKING 起不可取消，任务将继续至终态"}` |
| 已终态 | `200`（幂等） |
| 不存在 | `404` |

### 3.6 成片流 — `GET /api/v1/jobs/{id}/video`

→ `200 video/mp4`（`Content-Disposition: inline`），**支持 HTTP Range（206），可直接 `<video>` 流播/拖动**。
未 DONE 或文件缺失 → `404`。

### 3.7 驳回清单（取证）— `GET /api/v1/jobs/{id}/review-errors`

→ `200 [{"jobId","round","source","reason","createdAt"}]` 按 id 升序。
每一轮质量驳回（生成校验/QA/素材片）逐条在此留痕——**FAILED 定因先看这里，不要盲重试**（见 §8）。

### 3.8 识图结果（只读）— `GET /api/v1/jobs/{id}/extracted`

→ `200 extracted.json 原体`（GLM 识图的结构化结果：题型/题干/选项/公式 LaTeX）。job 不存在或未落盘 → `404`。
仅 IMAGE 路径产出；TEXT 路径会尽力从题干补写一份。

### 3.9 确认识图 — `POST /api/v1/jobs/{id}/confirm` → `202` 异步续跑

仅 `AWAITING_CONFIRM` 态可用，否则 `409`；不存在 `404`。

### 3.10 修改识图重审 — `POST /api/v1/jobs/{id}/revise`

```jsonc
{ "text": "修改后的题目全文" }   // 非空白且 ≤2000 码点，否则 400
```

→ `202` 异步（转 TEXT 通道重审）。仅 `AWAITING_CONFIRM` 可用（409）；修改次数超上限（默认 10，`app.pipeline.max-revise`）→ `409`。

---

## 4. 状态机（11 态，照此写前端映射）

```
正向主链：
QUEUED → EXTRACTING → GENERATING → REVIEWING → SPEAKING → QA → RENDERING → DONE
                        ↑  └──────────┬──────────┘
                        └── REVIEWING 驳回重生成 / QA 判负回 GENERATING（带 FAIL 清单自纠）

识图确认闸（仅 IMAGE，app.pipeline.extract-confirm=true 时）：
EXTRACTING → AWAITING_CONFIRM → confirm: GENERATING
                               → revise:  EXTRACTING（转 TEXT 重审）
                               → 废题判定: FAILED（原因给足）
                               → cancel:  CANCELLED
   TEXT 通道永不过闸。

终态：DONE / FAILED / CANCELLED（任意非终态可 → FAILED；墙钟/预算尽/致命错误）
```

- `stageHistory` 追加式记录每次迁移（`stage/state/note/at`），是**还原时间线的权威依据**。
- **驳回回环是正常自纠，不是故障**：`genRetries>0` 或 `qaRounds>0` 且最终 DONE = 质量闸工作正常。
- **墙钟语义（重要）**：全局 60min 死线（`app.retry.wall-clock-deadline-minutes`），从首次进
  EXTRACTING 落库，**绝对死线**——排队不计、驳回回环不刷新、**机器睡眠照计时**。AWAITING_CONFIRM
  停驻期间**暂停计时**（等人不算超时）。超线 → FAILED，`errorMessage="全局墙钟超限（>60min），本题作废"`。

---

## 5. 识图确认闸：网站的必做交互

IMAGE 提交后任务会**停驻在 AWAITING_CONFIRM**（识图 ~15-30s 后），等人三选一：

1. **确认** → `POST /{id}/confirm`（202，继续全链）；
2. **修改** → 展示 `GET /{id}/extracted` 的结构化结果（含 LaTeX 公式，前端可用 KaTeX 渲染），
   让用户改题文后 `POST /{id}/revise`（202，转 TEXT 重审，不再二次停驻确认）；
3. **取消** → `DELETE /{id}`（202）。

废图/无关图（非考研题目）会被自动驳回 → FAILED，原因在 `errorMessage` +
review-errors（例：「截图为终端开发对话与项目文档内容，非考研科目题目」）。
**网站侧务必实现这个停驻态的 UI/轮询分支**，否则 IMAGE 单会永远停在 AWAITING_CONFIRM。
不想要此闸：配置 `app.pipeline.extract-confirm=false`（IMAGE 全自动，TEXT 行为不变）。

---

## 6. 集成模式（推荐做法）

### 6.1 进度获知：轮询 or 回调

- **轮询**：`GET /{id}` 每 3-5s 一次。前端进度条建议按段加权
  （EXTRACTING[1,5] → GENERATING[5,30] → REVIEWING[30,33] → SPEAKING[33,42] → QA[42,48] →
  RENDERING[48,99] → DONE=100），段内按 `stageHistory` 最后一条 ENTER 的时间渐进爬升。
- **回调**：提交时带 `callbackUrl`，终态时工厂 `POST`：
  ```jsonc
  { "jobId": "…", "status": "DONE", "videoUrl": "http://<public-base-url>/api/v1/jobs/{id}/video", "error": null }
  ```
  `videoUrl` 仅 DONE 有值；`error` 仅 FAILED 有值；null 字段不序列化。
  重试策略：失败退避重发（1+3 次）。**反代/域名部署必须覆盖 `app.public-base-url`**
  （env `APP_PUBLIC_BASE_URL`），否则回调里的 videoUrl 是 localhost。
- 两者可并用：回调做终态通知，轮询做进度展示。

### 6.2 成片获取与存储

`videoUrl`（回调）或 `GET /{id}/video`（轮询）拿到 mp4 流。DONE 后服务端 `artifacts/{jobId}/`
保留三件套：`final.mp4` + `audio/lines/*.wav`（TTS 行音频）+ `extracted.json`（识图结果）；
其余过程产物（渲染帧/QA stills/工作区）已自动清理。**网站要不要把成片搬运进自己的对象存储由你定**——
工厂不提供云存储，`artifactsDir` 是服务端本地盘。

### 6.3 健康路径耗时预期（720p，一遍过）

| 阶段 | 典型耗时 | 说明 |
|---|---|---|
| EXTRACTING | 10-30s | TEXT 快；IMAGE 含视觉识图 |
| （AWAITING_CONFIRM） | 不定 | 等人，墙钟暂停 |
| GENERATING | 6-12min | 分片生成（P0 骨架 → P1∥P2a → P2b → 场景片）；驳回回环每轮另计 |
| REVIEWING | 1-3min | V1-V4 内容校验 |
| SPEAKING | ~3min | TTS 逐行合成 |
| QA | 2-5min | 静帧预审（GLM 审帧） |
| RENDERING | ~16min | **最大头，也是未来提速第一杠杆** |
| **合计** | **~30-47min** | 实测一遍过样本：31.5min / 47min |

成本 ~2-5 毛/题。并发上限：GLM=2、TTS=1、RENDER=2、QA=1（`application.yml` 可调），批量自动排队。

---

## 7. 部署形态约束（网站接入前必读）

1. **单实例**：H2 文件库（`server/data/jobs`）+ 本地盘 workspace/artifacts——**一个数据目录只跑一个实例**。
   水平扩容需先外置存储（现版本不支持多副本共享）；量小直接单实例即可。
2. **无鉴权**：v1 接口零鉴权（刻意，预留网站网关层做）。**必须放在内网/网关之后**，不要裸暴露公网。
3. **重启自愈**：服务重启后 QUEUED/在途任务自动续跑（分片缓存断点续生成；AWAITING_CONFIRM 从
   extracted.json 恢复停驻；RENDERING 中断则重渲）。频繁发版不用担心烧任务。
4. **内置 Web 单页**（`http://localhost:8080/`）是给人验收用的调试工具；网站对接全走 API。
5. 日志在服务 stdout；`favicon.ico` 404 噪音是已知无害项。

---

## 8. 失败处理语义（别盲重试）

FAILED 分两类，处置完全不同：

- **内容质量类**（驳回轮数烧尽）：`review-errors` 里有每一轮的驳回理由（数学错/排版病/叙事病…）。
  这是**题目本身难 or GLM 内容天花板**，重试同题大概率同样失败——先看 reason 再决定换题/人工干预。
- **墙钟/基建类**：`errorMessage` 带「全局墙钟超限」「GLM 请求 IO/超时」等字样。
  瞬态类（网络抖动）可重提；墙钟超限类先看是不是驳回回环太多（还是指向内容病）。

取证现场：**FAILED 任务的工作区完整保留**（`workspace/{jobId}/`，含中间产物），供排查；CANCELLED 清理。
`errorMessage` 给人看、`lastError` 是底层原文取证（可能很长），前端展示用 `errorMessage`。

---

## 9. 运行红线（违反会烧钱/烧信任）

1. **密钥纪律**：`ZHIPU_API_KEY` / `DASHSCOPE_API_KEY` 只走 env；绝不入 repo/日志/报告/对话/截图。
2. **template/ 封版**：`template/` 是封版资产（Ruling-16）——任何影响渲染输出的改动都必须走重封版流程
   （golden 渲染零漂移验证 + 用户看片），**不要顺手改**。唯一例外见 §2 的浏览器路径配置。
3. **golden 样张不动**：`template/src/data/content.json` 等金标 fixture 是回归基线，字节级不许碰。
4. **生产排白天**：GLM 平台历史上 03:00-05:42 深夜窗长请求不稳（超时/断流）；长生成已做拆片缓解，
   但批量生产仍建议排白天。
5. **批产机器勿合盖**：墙钟是绝对死线，机器睡眠时间照算（曾有过 render 被睡眠判死的实例）。
6. **开发/测试显式传 `resolution=720p`**（省时省盘）；1080p 留给正式出片。
7. **验收姿态**：新功能上线前用真实题目全链跑一遍并**人眼看片**——机器闸全绿≠视频没毛病。

---

## 10. 给对接智能体的工作守则

- 改动任何行为前，先读 `progress.md` 台账——45 版工程史里每条设计裁定（Ruling-1..18）都有来龙去脉，
  大多数「看起来可以优化」的地方其实是踩过坑的刻意设计。
- 发现新问题：按「事故取证 → 根因定性 → 事故驱动规则生长」走，**不要**未经定性就改模板或调参。
- 本服务是被验证过的稳定系统（412 项测试全绿 + 多轮实战验收）；改动走小步提交 + 全量测试 +
  独立评审，别大爆炸重构。
- 拿不准的需求（鉴权方案、多实例、对象存储、提速）先记录并问需求方，别自行拍板基建级变更。
