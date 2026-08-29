# Phase 2 E2E 验收报告（spec §16：三题并发全链路）

- **执行**：Task 12，2026-08-29 23:26:42 batch 入队（HTTP 202 整批接受）→ 2026-08-30 01:06:42 全局看门狗（100min）到点收线。**终态结论：0/3 DONE，验收不通过（带完整诊断）**——三 job 均健康推进至 QA 循环、无一失败/卡死，但 QA 单轮延迟使 100min 预算对三题 batch 结构性不可行（见 F2）。
- **生产/测试代码改动**：无。H2 生产库未删除，移至 `server/target/e2e/h2-backup/` 后全新建库。
- **三题**：① TEXT Z 变换（sim-001 原题文字版）② IMAGE `template/out/qa/test.png`（base64，视觉审题 11s）③ TEXT 拉格朗日中值定理证明。

## 1. 各 job 里程碑时间戳表（stageHistory 权威值，30s 轮询佐证）

| 里程碑 | job1 9fabbdf8（Z变换·TEXT） | job2 88acc1b2（截图·IMAGE） | job3 941eb307（拉格朗日·TEXT） |
|---|---|---|---|
| ENQUEUE | 23:26:42 | 23:26:42 | 23:26:42 |
| EXTRACTING | 23:26:42 | 23:26:44 | 23:26:46 |
| GENERATING（审题完成） | 23:26:54 | 23:26:55 | 23:27:02 |
| REVIEWING | 23:34:31 | 23:32:37 | 23:31:09 |
| **V1-V4 驳回** | 23:46:09（第 2 轮驳回，rev=2）→ 23:49:50 重生成通过 | 一次通过 | 一次通过 |
| SPEAKING（V 校验通过） | 23:50:42 | 23:35:10 | 23:34:23 |
| RENDERING（TTS 完成） | 23:53:40（21 句） | 23:40:47（20 句） | 23:37:52（23 句） |
| QA 第 1 轮进入 | 00:12:29（渲染 18.8min） | 23:56:34（渲染 15.8min） | 23:55:49（渲染 18.0min） |
| QA 第 1 轮结果 | 渲染 EPERM 重试（F3，qa=1），00:14→00:41 重渲后回 QA | 00:53:48 **未过驳回**（qa=1，57.2min）→ 全片重渲 → 01:09 新 final.mp4 | 00:25:28 **未过驳回**（qa=1，29.7min）→ 全片重渲 → 00:42:06 回 QA |
| 看门狗 01:06:42 截止态 | QA 第 2 轮进行中 | RENDERING（第 2 轮成片已出） | QA 第 2 轮进行中 |
| 终态 | 无（预算内健康推进） | 无（同左） | 无（同左） |

计数器（截止）：job1 rev=2 qa=1；job2 rev=0 qa=1；job3 rev=0 qa=1。均远未触及 QA maxRounds=5 / review 预算，无 FAILED。

## 2. 并发 ≤2 实证（通过）

- 进程级硬证据：`server/target/e2e/renders.tsv` 168 个有效采样（20s 间隔，按 workspace jobId 去重），**峰值 2 个 job** 同时存在 remotion 子进程（53 次采样=1 job、30 次=2 jobs、0 次≥3）。
- 状态层与进程层分离：23:54-23:56 三 job 状态同时为 RENDERING（`enterStage(RENDERING)` 先落库、`semaphores.render().acquire()` 后执行，JobOrchestrator.java:404/421），但 job1 当时无渲染进程——信号量排队行为实证。
- 附带实证：TTS 信号量=1 串行、GLM 信号量=2（三 job GENERATING 重叠但内容工位交错）、QA 信号量=1（job2 在 QA 状态等 57min 才轮到执行）。

## 3. 成片清单（artifacts 保留，均可看）

| job | 文件 | 大小 | 说明 |
|---|---|---|---|
| 9fabbdf8 | `artifacts/9fabbdf8-…/final.mp4` | 21,430,445 B | QA 第 1 轮在盘成片（非 DONE 终版） |
| 88acc1b2 | `artifacts/88acc1b2-…/final.mp4` | 20,498,344 B | 第 2 轮重渲成片（01:09 落盘，未及 QA） |
| 941eb307 | `artifacts/941eb307-…/final.mp4` | 25,078,195 B | 第 2 轮重渲成片（00:42 落盘） |

`GET /jobs/{id}/video`：job1/job3 200（200/206<sup>size</sup> 见上）、job2 在重渲期间 404（stale 丢弃设计生效，470d26f 实证）。三目录互不串扰，各片独立。

## 4. 发现与偏差（均未改代码，交控制器裁决）

- **F1（内容/模板层，主因之一）行内公式排版崩坏被 QA 拦截**：2/3 job QA 首轮同因驳回——job2「X(z)= 与分式被拆行、Z 字号异常插入句中」；job3「行内公式基线下沉致断行错乱、“闭区间/连续”被拆散、元素骑跨」。凡 math-heavy 题面（ProblemPanel 行内公式断行策略）系统性触发；QA 审帧链有效，但「驳回→全片重渲」对确定性排版缺陷无效（重渲不复原样概率高），大概率连环耗尽 5 轮 FAILED。
- **F2（验收主因）QA 单轮延迟 17.6-57.2min**：17 帧 stills（npx remotion still 逐帧拉起）+ 逐帧 GLM judge（含退避重试）+ QA 信号量=1 三 job 串行；叠加每轮全片重渲 16-19min。三题 batch 即使 QA 全一次过也需 ~75-95min，任何一轮驳回即突破 100min 看门狗。**建议**（供裁决）：QA 信号量提并发 / stills 批量化 / judge 并发化 / 或验收看门狗按轮数而非墙钟。
- **F3 job1 渲染 EPERM 一次**：`渲染失败 exit=1：EPERM: operation not permitted, rmdir 'workspace\9fabbdf8…\node_modules\.'`（qa=1 计入），就地重渲后自愈。疑为 QA stills 子进程仍持 node_modules 句柄时 workspace 拆除/重建撞车。
- **F4 stageHistory 完整性缺口（仅 job1）**：00:14 QA→RENDERING 与 00:41 RENDERING→QA 两次迁移（30s 轮询证实发生）无对应 history 条目；其余全部 25 条迁移均有条目。疑与乐观锁重读（saveTolerant 丢弃内存态追加）或就地重渲不写 history 有关，待代码层面定位。
  【**2026-08-30 更正：经 T13c 三方复核确认为误报**】polls.tsv 中 job1 的 RENDERING 恰 37 行（23:54:06→00:12:07）连续零夹行；timeline.txt 证 QA→RENDERING 00:25:38 属 **job2**（本报告误记为 job1）；final-9fabbdf8.json 的 updatedAt（00:12:29.939）与最后一条 history 时间戳精确相等且 qaRounds=1——若 00:14/00:41 迁移为真则必有第二条 ENTER 且 updatedAt≈00:41。**结论：job1 从未发生 QA↔RENDERING 往返，「RENDERING→RENDERING 就地重渲不落 history」为设计行为**，回归测试已由 commit 43024ff 固化。
- **F5（minor）video 端点无状态门禁**：非 DONE 期间只要 final.mp4 在盘即 200（job1 在 QA 中返回第一轮旧片）。看 `JobService.videoPath:98` 按文件存在性判定。
- **运维记录**：轮询/采样后台 shell 两次被会话收割（服务 JVM 孤儿存活不受影响，前台轮询续采）；H2 旧库移至 `server/target/e2e/h2-backup/`（未删）；收尾已 taskkill 本任务全部 java/node 进程树，8080 释放；4 个 06:0x 的 sim-001 `remotion render --help` 残留 node 进程系本任务开始前已存在，未动。

## 5. 有效验证面（本轮通过的部分）

整批接受/整批校验语义（202+3 jobId）；TEXT/IMAGE 双路径审题；QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→RENDERING→QA 全链路真实迁移；V1-V4 驳回→错误清单回传→重生成→过审闭环（job1 rev=2）；TTS 分句与计时；渲染信号量 ≤2 进程级实证；QA 审帧链真实拦截缺陷；QA 驳回→stale 丢弃→全片重渲闭环；三 job artifacts 隔离；断点续跑 sweep 空载正确；服务在两轮 shell 被杀后仍稳定运行 100min。

## 6. 证据文件（`server/target/e2e/`，gitignored）

`timeline.txt`（状态迁移+运维事件）、`polls.tsv`（30s 全量轮询 270+ 行）、`renders.tsv`（并发采样）、`render-procs.txt`（进程级存证）、`final-<jobId>.json`（三 job 截止态完整 JobView）、`batch-response.json`（入队响应）、`server-run.log`（服务日志，无密钥）、`start-server.sh / poller.py / rendermon.py / build-payload.py`（可复跑）。
