# Phase 2 夜间工作报告（2026-08-31 晨）

> 授权：用户 2026-08-30 23:52「继续按照我们约定的做吧……你来做主，最后还是记录下来，给我一个完整的你的夜间工作报告」。执行链=T16a 评审收口 → T17 → T15b → 测试链 → 本报告。全程 SDD 双 agent（实现者+独立评审），安全红线（key 绝不打印/入 repo）全程执行。

## 1. 结论速览

- **三个开发任务全部收口**：T16a（QA 前置）、T17（可选 720p）、T15b（cancel 500/墙钟/off-by-one）——实现+独立评审+修复/勘误全部闭环，244/244 测试绿。
- **冒烟（单题全链 720p）全项通过**：34 分钟 DONE，两大新机制（Ruling-18 QA 前置序、Ruling-17 驳回回环）首次实链验证，成片 1280×720 逐帧目验合格。
- **E2E 三题批 0/3（4 次尝试全败于同一外部因素）**：GLM 深夜时段对长生成流不稳定（超时/截断），服务侧故障路径 4 次实证全部正确——墙钟死线干净判 FAILED、无一僵尸（与昨夜 14h 僵尸形成直接对照）。spec §16 三题并发验收**未达成**，堵点=外部，非代码。
- **重要预期修正：720p 不是提速手段**（本机实测 ≈0.85x），其价值=体积 ~0.85x + 网站档位选项。

## 2. 三个开发任务详情

### T16a：QA 前置为 still 预审（Ruling-18）— 收口 00:15
- 实现 `66e1b18`：状态机 SPEAKING→QA→RENDERING→DONE（渲染只走一次，每轮驳回省 14-17min）。
- 评审 Changes-requested 仅 1 个 I 项：授权对计数披露错误（实际 23→22 = +2/−3；实现者报告与简报都漏算两条删除对）——行为与 100 格全表测试自洽无误，控制器亲改两行文字 `8c15a9b`。
- 偏差 a（环境异常就地重审消耗 qaRounds 预算）评审裁定**可接受**：旧序同预算且每轮白付整片渲染，新序严格更优，终局报文可区分归因。
- 顺延：M1 升级窗口旧 RENDERING 任务零 QA 直达 DONE（建议 stageHistory QA-ENTER 守卫）；M2 工作区复用守卫建议加 `ctx.audioMeta != null`；M3 qaRounds 多因共用预算备忘。

### T17：可选 720p 渲染档位 — 收口 01:20
- 实现 `1333496`（12 文件仅 server/，模板/composition 零改动=非截戳，等比 scale）：API `resolution` 字段（"1080p" 默认 | "720p"，非法 400，batch 逐题独立）、Job 落库续跑读回、RenderWorker 映射 `--scale=0.6666666666666666`（全精度 2/3，1920/1080×scale 恰为 1280/720）、JobView 回显、未知档位 IAE 快速失败。231/231 绿。
- 评审 **APPROVE 零 I**：scale 参数亲读 Remotion 4.0.518 源码证实无整数限制；1080p golden f313 MD5 独立复现逐字节一致（`c0b5d9b1…5797`，零漂移门槛过）；720p 目验+mp4 探头 1280x720 DAR 16:9；反向实证 `--width/--height` 确实裁切。
- **README §2 勘误 `0949b07`**（评审裁决）：红字"--scale 被拒"与实证矛盾（全精度与 0.6667 字面量均被接受），改为推荐 `--scale` 直渲、ffmpeg 降备选、保留 --width/--height 裁切警告、历史根因不断言。
- **耗时定论（评审复测）**：600 帧暖缓存两档各 2 次——1080p 均 81.3s，720p 均 69.3s，**≈0.85x**。预期"14-17min→7-9min"决定性证伪：瓶颈非像素吞吐（CPU 饱和点在布局/截图 IPC/编码调度）。720p 实测收益=文件体积 ~0.85x（13MB 级）+ 未来网站档位；开发测试仅省 ~15%。
- 顺延：isBlank 分支无显式测试；无单条全链 720p 续跑测试（测试链自然覆盖）。

### T15b：服务小修三件 — 收口 02:15
- 实现 `13742f5`（8 文件，244/244 绿，TDD 先红后绿）：
  ① **cancel 500**：真实根因=JobService.cancel 读→改→写无并发兜底，与 GENERATING 期编排器循环落库**确定性**撞 `@Version`（H2 行锁持到 commit，窗口=整个读写跨度，非毫秒窗）→ OLE 穿出 500、标记从未落库。修复=撞锁重读按最新状态重判 + 5×20ms 有界重试，任意非终态稳定 202；终态/SPEAKING 语义未动。
  ② **生成墙钟死线**：新列 `gen_deadline_at`（进 GENERATING 落库 now+30min，`app.retry.gen-deadline-minutes` 可配；驳回回环重进刷新；sweep 续跑仍生效；渲染链不加墙钟——已有 30min spawn 硬界）。retryOrFail 先查墙钟，超线无视剩余次数直接 failJob（lastError 注明"重试墙钟超限"）。
  ③ **renderRetryOrFail off-by-one**：恰 N 次（红测实证修复前 max=5 跑 6 次）。
- 评审 **APPROVE 零 I**（关键前提独立发现：JobService 全类无 @Transactional 才使修复成立；真 JPA 竞速测试红绿可比）。
- 偏差 b 须向用户披露：retryOrFail 为共享预算方法，**V4/审题重试也受当轮墙钟约束**（未进过 GENERATING 的死线为 NULL 走既有计数）——方向单调向好（多一层保护），但超出任务书字面范围。
- 顺延：M1 竞速测试清理未包 try/finally；M2 墙钟被动检查依赖 glm.timeout-seconds=120 兜单请求（配置耦合，**本夜被实战点名**，见 §4）；M3 EXTRACTING 段仍纯计数；M4 enterGenerating 撞 cancel 死线丢失属良性。

## 3. 测试链结果（全部显式 720p）

### 冒烟：全项通过（02:26-03:00，job 4b390a7f）
- golden 题（f(x)=x³+ax²+x 单调递增求 a）POST→DONE **34 分钟**，含 1 轮 QA 判负驳回。
- **Ruling-18 新序实链验证**：SPEAKING→QA（still 预审）→（判负→GENERATING｜过→RENDERING）→DONE。
- **Ruling-17 驳回回环实链验证**：第 1 轮 QA 判负→带 FAIL 清单回 GENERATING→重生成→第 2 轮过审→渲染只走一次。
- 分段：GEN ~3m / REVIEW 40s / TTS 2m30s / QA 预审 2m30s / 驳回回环 13m / **RENDER 11m30s（720p）**。
- 成片 `artifacts/4b390a7f…/final.mp4`：**1280×720 SAR 1:1 DAR 16:9**，30fps，3:54，13MB。三帧目验（题面/考点/步骤/结论卡）KaTeX 清晰、排版等比、结论 a∈[−√3,√3] 数学正确。
- video 端点门禁：DONE→200 ✓，FAILED→404（"成片未定版"，既定语义）✓。
- resolution=720p 落库+回显实证；golden f313 @ 终 HEAD（0949b07）MD5 零漂移复核 ✓。
- 对照 rerun4（同题 1080p，40.4min 零驳回）：本夜 34min 且多扛了一轮驳回。

### E2E 三题批（Z变换 TEXT / 截图 IMAGE / 拉格朗日 TEXT，各 720p）：0/3，4 次尝试全败于同一外部因素

| 尝试 | 时段 | 结果 | 证据 |
|---|---|---|---|
| 1 | 03:01-03:42 | 0/3 FAILED | 三题墙钟死线（03:31）到后干净判负，lastError=GLM request timed out |
| 2 | 03:44-04:18 | 0/3 FAILED | 两题超时；Z变换拿到**开头有真内容但被截断**的响应（"素材输出不是 JSON"） |
| 3 | 04:20-05:00 | 0/3 FAILED | 全部 request timed out |
| 4 | 05:04-05:42 | 0/3 FAILED | 同上（发射前大请求探测 200/62.6s 通过，窗口随即再恶化） |

- **根因画像（定向探测实证）**：小请求（几十 token）全程稳定 200/1.4s；**长生成请求（数千 token）在 03:00-05:42 反复超时/中途截断**。服务端 GLM 客户端超时 120s：健康时长生成 ~62s 可过，降级窗口即超——碎片化健康窗口撞不上 30 分钟的整批生成期。
- **与昨夜 14h 僵尸的本质对照**：同一故障，昨夜 job 挂 14h+、cancel 还 500；本夜 4 次全部 30 分钟内有界终态、lastError 可归因（"重试墙钟超限"）、无僵尸。**T15b 出厂当晚即被真实事故验证。**
- 服务侧故障路径 4 次实证全正确：重试预算、墙钟、干净 FAILED、并发信号量、批整体拒绝语义、既有 job 不受扰（冒烟 DONE 成片完好）。
- **spec §16 三题并发验收未达成，堵点=GLM 外部夜间不稳定，非代码。** 建议：白天重跑一次（见 §7 复跑指引）；现场归档 `server/target/nightchain/attempt1-failed|attempt2-failed|attempt3-failed/`。

## 4. 关键发现（影响后续决策）

1. **720p ≈0.85x，不是提速手段**——本机渲染瓶颈不在像素吞吐。"开发默认 720p 因测试快"的预期修正为"省 ~15% + 体积小"；720p 的真实价值是未来网站档位。
2. **GLM 深夜长生成流不稳定（两次独立夜晚实证）**——夜里跑批不可靠，正式验收/批量生产应安排在白天健康时段；建议顺延项：GLM 客户端默认超时 120s→300s 或按阶段分级（T15b 评审 M2 配置耦合被实战点名）。
3. **README "--scale 被拒"为误记**（已勘误 0949b07）——720p 直渲路径成立，服务已采用。
4. 单机 GPU 路线再证非答案（720p 收益仅 15% 说明渲染非像素瓶颈）——数量级提速仍=多机/Lambda（有真实量再议）。

## 5. 本夜 commit 链

```
13742f5 feat(server): cancel 并发兜底+生成墙钟死线+渲染链恰 N 次（Task 15b）
0949b07 docs(template): README 720p 路径勘误（--scale 直渲实证）
1333496 feat(server): 可选 720p 渲染档位，resolution→--scale=2/3 等比输出（Task 17）
8c15a9b docs(server): 授权对计数披露勘误 24→22（T16a 评审 I1）
52a40f9 docs(template): v0.3 README 压力实测数字勘误（睡前）
66e1b18 feat(server): QA 前置为 still 预审（Ruling-18，Task 16a）
d7d1101 feat(template): 行级宽度/列表高度自适应排版（v0.3，Task 15a）
```

测试基线：217（T14a 后）→ 220（T16a）→ 231（T17）→ **244（T15b，当前）**，全量绿 exit 0（实现者与评审各独立复跑一遍）。

## 6. 安全事件披露与红线执行

- **历史事件（08-29，持续披露）**：GLM key 整行曾进入一次工具输出（未入任何 commit/报告/日志）——**建议用户轮换该 key**。
- 本夜执行：key 全程只经运行时 env 注入（start-server.sh 提取进环境变量，输出仅打印 `${VAR:+SET}` 探针）；本报告与晨报无任何 key 物料；评审/实现 agent 均报告未接触 key。

## 7. 留给用户的决策点（不阻塞，均可白天拍板）

1. **分支合并**：phase2-java-service → main 时机与方式（你的既定保留项）。
2. **E2E 白天重跑**：一条命令可复跑（下节）；3/3 DONE 即 spec §16 达成，届时总验收闭环。
3. **GLM 超时配置**：是否把 120s 放宽到 ~300s（顺延项，见 §4.2）。
4. **720p 语义确认**：本夜两档各已验证 OK（1080p=golden 零漂移四方对拍；720p=冒烟成片目验），按你 23:41 钉死的语义，**开发/测试自即日起默认只渲 720p**（本夜已如此执行）。
5. key 轮换（§6）。

### E2E 复跑指引（白天做）
```bash
cd /e/codebase/remotion_java/server/target/nightchain
sh start-server.sh &        # key 运行时注入，等 8080
curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/jobs/batch \
  -H "Content-Type: application/json" --data-binary @batch-payload.json
python -X utf8 poller.py    # 30s 轮询，160min 看门狗
```

## 8. 顺延清单（累计，均不阻塞）

- T16a：M1 升级窗口 QA 旁路守卫（stageHistory QA-ENTER）；M2 复用守卫 `ctx.audioMeta != null`；M3 qaRounds 多因共用预算
- T17：isBlank 分支显式测试；单条全链 720p 续跑测试
- T15b：M1 竞速测试 try/finally 清理；M2 GLM 超时配置耦合（建议放宽/分级）；M3 EXTRACTING 段纯计数；M4 enterGenerating 撞 cancel 良性
- 历史：V4 长度上限/回调无签名/I6 in-flight 加固/+38 采样余量/裸 x^2 收紧/V1 错误文案 props.formula/MATERIAL 硬约束镜像/长分式规则最弱/preview 端点/reviewErrors 落库/渲染 Node-API 重构（bundle 一次+暖浏览器 ~1-2min/job）/TTS 句级并行/GPU 实验前置条件
- 提速路线图（不变）：单机已无便宜杠杆 → 多机 / Remotion Lambda（有真实量再上）

## 9. 证据索引

- 夜间现场：`server/target/nightchain/`（payloads/poller/rendermon/日志/attempt1-3 归档/timeline）
- 冒烟成片：`artifacts/4b390a7f-56f5-4907-aa8f-ea3de473addc/final.mp4`（1280×720）
- T17 证据：`server/target/t17-evidence/`（两档并排 still + mp4 探针）
- golden 复核：`template/out/nightchain/f313-1080p-head.png`（MD5=c0b5d9b1b244e9cd8ef0c69c6f5c5797）
- 评审报告：`.superpowers/sdd/2026-08-29-phase2-java-service/task-16a-review.md`、`task-17-review.md`、`task-15b-review.md`
- 台账：同目录 `progress.md`（第十三版）；晨报：同目录 `morning-report-2026-08-31.md`
