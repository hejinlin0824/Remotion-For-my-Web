# Remotion-Java 考研数学讲题视频工厂 — 设计文档

- 日期：2026-08-28
- 状态：待用户审阅
- 前身实践：《ClaudeCode真的比Codex强吗》抖音项目（`E:\codebase\all_remotion\claudecode真的比Codex强吗\video`），其数据驱动时间轴、TTS 验证重试管线、GLM 审帧管线全部验证可用，本项目为其产品化。

## 0. 一句话

一个 Java 微服务：输入一道考研数学题（文本或截图），输出一条五幕固定风格的讲题视频（mp4），全流程无人值守、可批量挂机。

## 1. 目标与成功标准

- **风格锁死**：五幕结构、版面、动画、固定台词全部烘焙在封版模板里，LLM 只产内容，产出视频结构 100% 一致。
- **音画同步**：每个镜头时长 = 对应 TTS 音频实际时长（音频驱动时间轴），口播到哪画面给到哪。语速永远 1.0，不变速。
- **批量无人值守**：批量提交后自动排队生产；可重试故障自动重试；不可恢复故障落盘全部中间产物等人工。
- **为网站铺路**：v1 无鉴权单机运行，但 API 与状态机设计预留回调、优先级、多任务并发的扩展位。

## 2. 已锁定决策

| # | 决策 | 说明 |
|---|------|------|
| D1 | 定位：批量内容工厂 | v1 不做租户/计费/鉴权，接口预留 |
| D2 | 架构：模板封版 + 内容填 JSON | 模板代码生成后永不被服务修改；每条视频 = 模板副本 + 一份 content.json + 音频 |
| D3 | LLM 接入：Java 直调 GLM API | 不用 Claude Code headless，不拆独立 agent 服务 |
| D4 | 模型：GLM-5.3-flash 全包 | 题干识图、内容生成、LLM-judge、审帧 QA 统一用它（open.bigmodel.cn，Anthropic 兼容接口，key 复用审帧脚本的） |
| D5 | 画幅：16:9 默认 + 9:16 可选 | 接口参数 `aspect`，一套内容两套布局分区 |
| D6 | TTS：DashScope qwen-tts，音色 Cherry | 完整性验证 + 重试管线从 Python 移植到 Java |
| D7 | 幕1/幕5 烘焙进模板 | content.json 只含 2/3/4 幕；固定台词音频预合成进模板资产 |
| D8 | JDK：Temurin 21.0.12 @ `D:\Java_opensdk_jv21` | Maven toolchain / 构建期 JAVA_HOME 指向，不碰系统 JDK8；JDK25 @ `D:\Java_opensdk_jv25` 备用 |
| D9 | 重试上限 | 内容工位 ≤3 次；TTS 每句 ≤3 take + 整批重录 ≤1 次；审帧 QA ≤5 轮（只审客观项） |
| D10 | Maven 3.9.16 单模块，Spring Boot 3 | Node v24 已装；Edge 作为渲染浏览器（已装，不下载 Chrome Headless Shell） |

## 3. 环境与依赖

- Windows 11，Git Bash；代理 `http://127.0.0.1:7890`（下载依赖用）。
- API key 不入代码库：`GLM_API_KEY`、`DASHSCOPE_API_KEY` 走环境变量或 `secrets.local.yml`（gitignore）。
- 渲染：`npx remotion render`（Node 24），浏览器指向本机 Edge 可执行文件。
- 无 ffprobe 依赖：WAV/MP4 元信息一律纯解析（沿用抖音项目的字节数÷byte_rate 法）。

## 4. 总体架构

```
POST /api/v1/jobs ──▶ [EXTRACTING 题干提取] ──▶ [GENERATING 素材+剧本] ──▶ [REVIEWING 校验链]
   文本/截图(GLM识图)            (GLM 三工位)                (V1-V4, 不合驳回重生成)
                                                                          │ 过
   ┌──────────────────────────────────────────────────────────────────────┘
   ▼
[SPEAKING TTS] ──▶ [RENDERING 渲染] ──▶ [QA 审帧] ──▶ DONE (mp4 + 报告)
 Cherry+完整性验证     模板副本+JSON      GLM 客观审帧
                      (npx remotion)     ≤5 轮
```

资源信号量：GLM 并发 2；TTS 串行（调用间隔 ≥3s，429 退避 +15s 冷却）；渲染并发 2；QA 串行。

## 5. 项目结构

```
E:\codebase\remotion_java\
├── server\                          ← Spring Boot 3 单模块（pom.xml 在此目录）
│   ├── pom.xml
│   └── src\main\java\...\           ← 编排器/校验链/TTS客户端/渲染Worker/REST
├── template\                        ← Remotion 讲题模板项目（封版，服务只读）
│   ├── package.json / remotion.config.ts
│   ├── src\
│   │   ├── engine\                  ← 纯渲染引擎：读JSON→时间轴→五幕调度（写死）
│   │   ├── acts\                    ← 幕1/幕5 固定组件 + 幕2/3/4 组件白名单实现
│   │   └── data\
│   │       ├── content.json         ← 每条视频唯一变化点（Java 覆写）
│   │       └── audio_meta.json      ← 音频清单+时长（Java 覆写）
│   └── public\audio\
│       ├── fixed\act1.wav, act5.wav ← 预合成固定台词（模板资产）
│       └── line_XX.wav              ← 每单覆写
├── workspace\{jobId}\               ← 任务沙箱 = 模板副本 + 该题数据（可删）
├── artifacts\{jobId}\               ← 成片 mp4 + 审帧截图 + QA 报告（保留）
└── docs\superpowers\specs\
```

## 6. 内容契约 content.json

```jsonc
{
  "meta": { "aspect": "16:9", "problemType": "计算题" },   // 基础题|计算题|证明题|应用题
  "problem": {                                              // 题干，逐字复刻，幕2/幕3 常驻
    "lines": [
      { "id": "L1", "segments": [
        { "type": "text", "value": "已知函数 " },
        { "type": "math", "value": "f(x)=x^2-2ax+1" },      // KaTeX 渲染
        { "type": "text", "value": " 在 R 上单调，求 a 的取值范围。" }
      ]}
    ]
  },
  "knowledge": [     // 幕2 素材 ×2-4：考点名、公式定理(KaTeX)、适用前提、易忽略前置条件
    { "claim": "单调性等价于导数恒非负/非正", "formula": "f'(x)\\ge 0", "premise": "……", "trap": "……" }
  ],
  "steps": [         // 幕3 素材 ×3-10：超细无跳跃
    { "usesAnchor": "L1",                       // 对应题干哪一行（引擎高亮该行）
      "statement": "对 f(x) 求导",
      "derivation": "f'(x)=2x-2a",              // KaTeX 推导弹窗
      "note": "单调 → 导数符号固定" }
  ],
  "pitfalls": [ { "claim": "忽略'在R上单调'要求恒成立", "why": "……" } ],   // ×1-3
  "generalMethod": [ { "step": "识别：含参二次+区间单调", "trick": "恒成立转判别式" } ], // 幕4 ×3-6
  "scenes": [        // 组装层：一个元素 = 一个镜头 = 一句 TTS；只允许 act 2/3/4
    { "act": 2, "component": "problem-card",  "ttsText": "先看这道题……" },
    { "act": 3, "component": "step-card",     "ttsText": "第一步，求导……",
      "props": { "stepRef": 1 } },
    { "act": 3, "component": "derivation-popup", "ttsText": "这里注意……",
      "props": { "stepRef": 1, "formula": "f'(x)=2x-2a" } }
  ]
}
```

规则：

- **时长不由 LLM 决定**：每镜头时长 = 该句 TTS 实际时长；幕边界由引擎插入章节大字（「知识点回顾」「本题解法」「以后怎么做」，属临时元素，LLM 无权生成）+ 0.18s 气口；片尾停留 2s 后整体淡出。
- **题干保真两级校验**：EXTRACTING 输出 vs 用户输入（文本路径，允许空白/标点归一与公式 LaTeX 化）；content.json.problem vs EXTRACTING 输出（逐字）。
- 固定台词（幕1/幕5）不在契约内，模板资产所有。

## 7. 组件白名单（LLM 只能选这 7 个枚举）

`problem-card` / `knowledge-card` / `step-card` / `derivation-popup` / `pitfall-card` / `checklist-card` / `general-list`

> 注：讨论中提过 9 个，幕1/幕5 烘焙进模板后，`intro-title`、`typewriter-end` 移出 LLM 枚举，归模板引擎所有。

## 8. 五幕模板规格（Phase 1 需求，来自用户生产规范）

| 幕 | 内容 | 元素生命周期 |
|----|------|-------------|
| 1 固定片头 | 居中「WhatsYourFuture帮你讲」/「WhatsYourFuture版权所有」淡入停留→整体淡出；固定 TTS「你好啊同学，我来帮你解决这道题，坐好发车!」 | 临时 |
| 2 题干+知识点 | 题干全屏居中→平滑缩小位移定格左上角常驻；中心大字「知识点回顾」1-2s 淡出；知识点弹窗高亮随讲随出随消 | 常驻(题干)+临时(章节字)+讲解(卡片) |
| 3 解法精讲 | 左上角题干完全不动；「本题解法」大字；逐步骤：用到条件即时高亮题干对应行、推导弹窗补全、抽象逻辑可视化；步骤弹窗用完即消，核心结论保留 | 常驻(题干)+讲解(步骤) |
| 4 通法总结 | 题干平滑淡出清空；「以后怎么做」大字；通法逐条弹出、逐条高亮、逐条讲解 | 临时+讲解 |
| 5 固定片尾 | 纯白底打字机「WhatsYourFuture出品」「希望能够帮到你」，停留 2s 整体淡出；固定 TTS「这就是本道题的解法，希望能够帮到你，祝你考研一战上岸！」 | 临时 |

- 双画幅：同一 content.json，两套布局分区常量（横 1920×1080 / 竖 1080×1920），组件按分区自适配。
- 全部数学式子 KaTeX 渲染；所有出现元素必须有明确的进入/退出动画，禁止无动画瞬现瞬失。

## 9. 内容生产：三工位（次 Agent 落地）

线性执行，每工位严格 JSON 输出 + 独立重试（错误清单随重试请求回传）：

1. **审题工位（EXTRACTING）**：截图 → GLM-5.3-flash 视觉提取题干转 text/math 分段；文本 → 规整化分段；输出题型分类 + problem.lines（带 id）。失败（读不出题/非数学题）→ 致命错误。
2. **素材工位（GENERATING-a）**：题干 → knowledge / steps / pitfalls / generalMethod 四段素材。
3. **剧本工位（GENERATING-b）**：素材 + 题干 → scenes[] 组装（选白名单组件、写口语化 ttsText、挂 stepRef）。

V4 语义审核（LLM-judge）由主 Agent 角色承担：单独一次 GLM 调用，输入契约+素材，输出 `PASS | REJECT+逐条理由`（步骤跳跃、条件对应错误、讲解与画面组件不匹配等），驳回理由回传素材/剧本工位重生成。

## 10. 校验链与驳回（主 Agent 落地，全部代码化除 V4）

| # | 校验 | 失败处理 |
|---|------|---------|
| V1 | 结构合规：JSON Schema（幕只允许 2/3/4、组件枚举、条数范围、必填字段） | 驳回重生成 ≤3 |
| V2 | 题干保真：content.json.problem 与 EXTRACTING 输出逐字一致 | 驳回重生成 ≤3 |
| V3 | 引用合法：stepRef、usesAnchor 必须存在且指向正确 | 驳回重生成 ≤3 |
| V4 | 语义质量：LLM-judge 审步骤跳跃/条件对应/讲解匹配 | 驳回重生成 ≤3 |
| V5 | TTS 完整性：每句尾部静音/衰减判据（见 §12） | 该句重合成 ≤3 take |
| V6 | 画面 QA：GLM 客观审帧（重叠/乱码/黑字/越界/公式崩坏，不审主观审美） | 重渲染或驳回 ≤5 轮 |

3 次内容级仍败 / 整批重录后仍败 / 5 轮 QA 仍败 → 任务 FAILED，workspace 与全部中间产物保留，错误阶段+原因+产物清单入 API。

## 11. 状态机与队列并发

```
QUEUED → EXTRACTING → GENERATING → REVIEWING → SPEAKING → RENDERING → QA → DONE
                                                                    ↺ (≤5)
任意阶段失败 → 带错误清单退回上游（工位≤3 / TTS≤3take+整批≤1）→ 仍败 → FAILED
```

- H2 文件库 `jobs` 表：id、status、stage、aspect、输入载荷、各阶段重试计数、错误、时间戳、callbackUrl。
- `@Scheduled` 轮询 + 乐观锁领单；每任务独立线程；信号量隔离四种资源（§4）。
- **断点续跑**：重启后已完成阶段凭 workspace 既有产物跳过，从断点继续；渲染进程 15 分钟超时强杀。
- 取消：DELETE 置标记，阶段间检查；渲染中不打断，完成后不入库成片。
- 批量：`POST /jobs/batch` 一次入队 N 题；FIFO，预留 priority 字段。

## 12. TTS 管线（Python → Java 移植）

- HTTP 直调 DashScope qwen-tts，voice=Cherry，rate=1.0 永不变速；调用间隔 ≥3s，429 指数退避 + 15s 冷却。
- **WAV 头不可信**（data-size 是 INT32_MAX 垃圾值）：时长 = 文件字节数 ÷ byte_rate。
- **完整性判据**：last80ms RMS < 100（数字静音）OR（last80/prev240 < 0.35 且 last80/prev480 < 0.35 且时长 ≥ 本轮最长 take 的 92%）——问句气音收尾不误杀，真截断不放行。
- 每句 ≤3 take 择优（先按时长过滤再取尾部最静）；某句 3 take 全败 → **整批废弃重录一次**（跨批次音色漂移坑，禁止单句补录）→ 再败 → FAILED。
- 时间轴：act1 固定 wav + 变量幕逐句 wav（气口 0.18s）+ act5 固定 wav（后停 2s）→ 总帧数 30fps 自动算出，写入 audio_meta.json。

## 13. REST API（v1 无鉴权）

```
POST   /api/v1/jobs        { inputType: TEXT|IMAGE, text?|imageBase64?, aspect?=16:9,
                             voice?=Cherry, callbackUrl? }        → 202 { jobId }
POST   /api/v1/jobs/batch  { items: [×N] }                        → 202 { jobIds }
GET    /api/v1/jobs/{id}   状态/阶段/阶段历史/重试计数/错误/产物清单
GET    /api/v1/jobs?status=&page=                                 列表分页
GET    /api/v1/jobs/{id}/video                                    mp4 流
DELETE /api/v1/jobs/{id}                                          取消
```

任务终态（DONE/FAILED）时若带 callbackUrl，POST 通知 `{ jobId, status, videoUrl?, error? }`。

## 14. 错误处理

- 可重试：LLM 超时/429、TTS 截断、QA 不合格 → 状态机内自动重试（§10 上限）。
- 致命：截图读不出题、非数学内容、schema 3 次不过、整批重录后仍截断、渲染反复崩溃 → FAILED + 产物保留。
- 外壳：所有外部调用带超时与指数退避；单任务异常不拖死 worker；磁盘水位检查（workspace 清理策略：DONE 后删沙箱留成片）。

## 15. 测试策略

| 层 | 内容 | API 成本 |
|----|------|---------|
| 单元 | V1-V3 校验链；WAV 解析 + RMS 判据（真实截断样本作 fixture）；时间轴计算（对照抖音项目公式）；信号量/状态机 | 0 |
| 契约 | 三工位 + LLM-judge 用录制 JSON fixture 回放，schema/驳回逻辑反复测 | 0 |
| 集成（slow 标签） | 模板 smoke：示例题 `remotion still` 抽帧 + 全渲染一条；TTS 真调 1 句；一条 golden 题全链路 | 少量 |

## 16. Phase 划分

- **Phase 1 五幕模板项目**：`template\` 全部内容 + 固定台词预合成 + 示例题全片渲染通过 + 双画幅抽帧检查。验收：示例题一条成片，每个镜头至少一帧 + 幕边界/片尾各一帧，全部 PASS（人工+GLM）。
- **Phase 2 Java 工厂服务**：REST/状态机/三工位/校验链/TTS/渲染 Worker/QA。验收：文本题与截图题各一条全链路无人值守成片，批量 3 题并发正常。

## 17. 风险与已知坑（从前项目记忆带入）

1. DashScope 服务端随机截断 → V5 判据 + 重试（§12）。
2. 跨批次音色漂移 → 每条视频单批次连发，禁单句补录（§12）。
3. 429 限流 → ≥3s 间隔 + 退避 + 冷却（§12）。
4. 继承色黑字坑 → 模板根节点显式 `color`，组件一律显式设色（§8）。
5. remotion-bits glitch 曲线以 0 结尾、瞬变相位 → 审帧避开瞬变帧（若模板复用 bits 组件）。
6. KaTeX 长公式溢出 → 布局分区预留缩放，QA 审"越界"项兜底。
7. GLM 内容幻觉（解题错误）→ V4 LLM-judge + 用户验收期人工抽检；v2 可增加数值验算工位。
