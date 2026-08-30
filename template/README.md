# 讲题视频封版模板（template-v0.1）

五幕考研数学讲题视频的封版模板，Phase 2 Java 服务的操作契约。本文档所有命令、字段、常量均与仓库代码实况逐项核对（`src/engine/constants.ts` / `types.ts` / `timeline.ts` / `contract.ts` / `scripts/*.py|*.mjs` / `src/Root.tsx` / `remotion.config.ts`）。

## 1. 定位

- **封版模板**：`template/` 是只读原件。改版面/动画 = 改模板代码并重新封版（升 tag）；**绝不为单条视频改代码**。单条视频的生产只允许「复制副本 → 覆写数据 → 渲染」（见 §2）。
- **画幅**：**16:9 是唯一验收画幅**（1920×1080，composition `Lecture169`）。`Lecture916` composition 与 `LAYOUTS["9:16"]` 代码保留但为**休眠路径**——不验收、不出成片，勿在生产管线调用。
- **golden 17 场示例**：`src/data/content.json`、`src/data/audio_meta.json`、`public/audio/` 里的 17 场示例**永久保留在模板里**，身份是①字段手册的活样例 ②冒烟测试基准（封版后可随时 `npm run studio` 或抽帧验证模板本身没坏）。副本覆写即弃，不属于"已填内容"。

## 2. 单条视频生产 SOP（Phase 2 自动化对象）

每单复制副本，封版原件只读（副本覆写制）：

```bash
cp -r template/ workspace/{jobId}/      # 每单复制副本，封版原件只读
# 1. 覆写副本 src/data/content.json     （工位产物，过契约校验，见 §3）
# 2. 覆写副本 src/data/audio_meta.json  （TTS 管线产物，durationSec = 字节数 ÷ byte_rate 实测）
# 3. 覆写副本 public/audio/lines/line_NN.wav（NN 按 scenes 数组顺序，01 起，两位零填充）；
#    public/audio/fixed/ 幕1/幕5 模板资产【不动、不重合成】
# 4. 渲染
npx remotion render Lecture169 out/final.mp4    # 1080p 原生母版
```

- 契约校验在 bundle 模块加载时执行（`src/engine/data.ts`：`validateContract` + `buildTimeline`，任一违规直接 throw 中止渲染）。渲染前可用 `npx remotion compositions src/index.ts` 快速自检契约。
- **720p 衍生**：只允许「1080p 母版 + ffmpeg 转码」一条路：

  ```bash
  npx remotion ffmpeg -y -i out/final.mp4 -vf scale=1280:720 -c:a copy -crf 18 out/final-720p.mp4
  ```

  > **⚠️ 红字警告：`--width/--height` 只改画布不缩放内容（1080p 元素按绝对像素布局，直接被裁切）；`--scale` 因 2/3 的浮点表示非整数（如 1080×0.6667=720.036）被 Remotion 拒绝。720p 只允许母版 + ffmpeg 转码。**（sim-001 实证，详见其 pipeline-record F9。）

## 3. 字段契约（两个 JSON）

### 3.1 `src/data/content.json`

```jsonc
{
  "meta": { "aspect": "16:9", "problemType": "计算题" },
  "problem": { "lines": [
    { "id": "L1", "segments": [ { "type": "text", "value": "…" }, { "type": "math", "value": "KaTeX 源码" } ] }
  ]},
  "knowledge":    [ { "claim": "…", "formula": "KaTeX", "premise": "…", "trap": "…" } ],            // ×2-4
  "steps":        [ { "usesAnchor": "L1", "statement": "…", "derivation": "KaTeX", "note": "…" } ], // ×3-10
  "pitfalls":     [ { "claim": "…", "why": "…" } ],                                                 // ×1-3
  "generalMethod":[ { "step": "…", "trick": "…" } ],                                                // ×3-6
  "scenes": [
    { "id": "s01", "act": 2, "component": "problem-card", "ttsText": "口语讲解词", "props": {} }
  ]
}
```

- **条数范围 knowledge 2-4 / steps 3-10 / pitfalls 1-3 / generalMethod 3-6** 是场景数动态伸缩的基础——scenes 数量随难度/解析内容增减（sim-001 实证 20 场 ≠ golden 17 场，totalFrames 5334→5805 自动适配）。代码（`contract.ts`）不硬校验条数，只查 ref 越界——条数范围是工位约定；V1 硬校验属 Phase 2。
- `meta.aspect` 只是请求画幅记录，渲染层不做 throw（Phase 2 的 V1 负责与任务参数核对）。
- `ttsText` 是口语讲解词（不是题干朗读，题干在画面上）；一句 TTS = 一个镜头。
- `scenes` 顺序 = 播放顺序，且必须 act2 全部 → act3 全部 → act4 全部。

**props 矩阵（按组件钉死，Phase 2 的 V1/V3 校验对象；1-based，越界即契约失败）：**

| component | 允许 act | props |
|---|---|---|
| `problem-card` | 2 | `{}`，且必须是 act2 第一场（`scenes[0]`） |
| `knowledge-card` | 2 | `{ "knowledgeRef": N }` → `knowledge[N-1]` |
| `step-card` | 3 | `{ "stepRef": N }` → `steps[N-1]` |
| `derivation-popup` | 3 | `{ "stepRef": N, "formula": "KaTeX" }` |
| `pitfall-card` | 3 | `{ "pitfallRef": N }` → `pitfalls[N-1]` |
| `checklist-card` | 3 | `{ "pitfallRefs": [N…] }`（非空数组，逐项不越界） |
| `general-list` | 4 | `{ "itemRef": N }` → `generalMethod[N-1]` |

**代码实施校验（`contract.ts` + `timeline.ts`，加载即执行）：**

- `act` 只允许 2/3/4；组件按 §5 白名单归幕。
- `scenes` 必须按 act 升序排列；act2 第一场必须是 `problem-card`；act2 至少一场 `knowledge-card`（章节字挂靠点）。
- 每场必须有对应音频行（`audio_meta.lines[].sceneId` 匹配）；`ttsText` 非空。
- 各 ref 为 ≥1 整数且不越对应数组上界；`derivation-popup` 必须带非空 `formula`。
- `steps[].usesAnchor` 必须存在于 `problem.lines[].id`。

### 3.2 `src/data/audio_meta.json`（TTS 管线产物）

```jsonc
{
  "voice": "Cherry", "model": "qwen-tts", "rate": 1.0, "fps": 30,
  "breathSec": 0.18, "act5TailSec": 2.0,
  "fixed": {
    "act1": { "file": "audio/fixed/act1.wav", "durationSec": 5.201 },
    "act5": { "file": "audio/fixed/act5.wav", "durationSec": 5.901 }
  },
  "lines": [ { "index": 1, "sceneId": "s01", "file": "audio/lines/line_01.wav", "durationSec": 9.541, "text": "…" } ]
}
```

- `durationSec` = WAV 文件字节数 ÷ byte_rate（`ch × sr × bits / 8`）实测，不是估算。
- `lines[].file` 命名 `audio/lines/line_NN.wav`，`NN` 按 `scenes` 数组顺序从 01 起；`index` 同序。
- `rate` 恒 1.0（见 §8）；`fps`/`breathSec`/`act5TailSec` 必须与 §7 钉死常量一致。

## 4. 时间轴公式（`src/engine/timeline.ts`）

```
BREATH_F = round(0.18 × 30) = 5 帧                          # 场间呼吸，场与场之间各插一次
幕1: startFrame = 0，durFrames = round(act1.durationSec × 30)          # golden 156f
场次 i: startFrame = t；durFrames = round(line.durationSec × 30)；t += durFrames + BREATH_F
幕5: startFrame = t − BREATH_F（抹掉末场后的呼吸）；
     durFrames = round((act5.durationSec + 2.0) × 30)                  # golden 237f
幕2/3/4 窗口 = 该幕首场 startFrame → 末场 startFrame + durFrames
totalFrames = 幕5.startFrame + 幕5.durFrames                # golden 17 场 = 5334 帧 = 177.8s
章节字挂靠: act2 = 幕2 首个 knowledge-card 的 startFrame；act3/act4 = 该幕 startFrame
            （叠加层，时长恒 CHAPTER_F=54f，不占场景时间轴，见 §7）
```

- **`scripts/pick_frames.mjs` 是同一公式的镜像实现（独立复算，不 import 引擎代码），两者必须同步改**。改时间轴公式 = 同时改 `timeline.ts` 与 `pick_frames.mjs`，并跑 `npx vitest run`（`timeline.test.ts` / `contract.test.ts`）。
- `node scripts/pick_frames.mjs` 打印 `totalFrames` + 推荐审帧清单（每镜头 55% 处一帧 + 幕1 中段 + 三个章节字帧 + 幕5 打字机/定格末尾帧）；`--scene s03` 按名过滤。

## 5. 白名单矩阵（`contract.ts` ACT_COMPONENTS）

| act | 允许组件 |
|---|---|
| 2 | `problem-card`、`knowledge-card` |
| 3 | `step-card`、`derivation-popup`、`pitfall-card`、`checklist-card` |
| 4 | `general-list` |

越白名单 / 越幕 = 契约失败，渲染中止。

## 6. QA 流程（客观项门禁）

```bash
cd template
# ① 复算时间轴，输出审帧清单
node scripts/pick_frames.mjs > out/qa/frames.txt
# ② 逐帧截图
while IFS=$'\t' read -r name f; do
  [[ "$name" == totalFrames* ]] && continue   # pick_frames 首行输出 "totalFrames = N"（空格分隔，非 tab），整行落入 $name——按前缀跳过
  safe=$(echo "$name" | tr -d '/')
  npx remotion still Lecture169 "out/qa/${safe}.png" --frame="$f"
done < out/qa/frames.txt
# ③ GLM 客观审帧（人工翻看 out/qa/*.png 同时进行）
python scripts/qa_glm.py
```

`qa_glm.py` 现机制（glm-5.3-flash，Anthropic 兼容端点 `https://open.bigmodel.cn/api/anthropic/v1/messages`）：

- 只审客观项：重叠/遮挡/溢出、乱码缺字、对比度失效（黑底黑字/白底白字）、公式渲染崩坏；不评审美。自动跳过 `scaffold.png`。
- Key 来源（**文档与日志绝不出现 key 内容**）：环境变量 `ZHIPU_API_KEY` / `ZHIPUAI_API_KEY` / `GLM_API_KEY`，回落 `~/.claude/settings.json` 的 `env.ANTHROPIC_AUTH_TOKEN`；都没有则 `[fatal]` 退出。
- 网络 429 / 5xx 退避重试（20s × 次数递增，最多 5 次）；200 但正文为空（thinking 吃满 max_tokens 预算）也按瞬态重试。
- 报告写 `out/qa/report.md`，写前自动把上一轮备份为 `report_prev.md`。
- **任一 FAIL → exit 1**（门禁）。

## 7. 已钉死常量（改任何一项 = 重新封版）

| 常量 | 值 | 出处 |
|---|---|---|
| FPS | 30 | `constants.ts` |
| 场间呼吸 BREATH_SEC | 0.18s（= 5 帧） | `constants.ts` |
| 章节字 CHAPTER_SEC | 1.8s（CHAPTER_F = 54 帧） | `constants.ts` |
| 片尾定格 ACT5_TAIL_SEC | 2.0s | `constants.ts` |
| 题干过渡 TRANSITION_F | 18 帧（act2 首场 problem-card 末 18 帧全屏→左上角；幕4 开始题干 18 帧淡出） | `constants.ts` / `ProblemStage.tsx` |
| 组件退场 EXIT_F | 10 帧 | `constants.ts` |
| 章节标题 CHAPTER_TITLES | 幕2「知识点回顾」/ 幕3「本题解法」/ 幕4「以后怎么做」 | `constants.ts` |
| 字体 FONT_FAMILY | `'Microsoft YaHei', 'PingFang SC', 'Noto Sans SC', sans-serif` | `constants.ts` |

**蓝块章节转场（Ruling-11，`ChapterTitle.tsx`）**：`#4F6EF7`（`COLORS.chapterBg`）整块自顶部 12f 扫入 → 白字 110px spring（damping 14 / stiffness 120）+ 金色下划线（`COLORS.hl`，14→34 帧展开 360px）停留 → 12f 扫出，总时长 54f。无 opacity 淡出，扫出即退场。章节字是叠加 Sequence（`from = timeline.chapterSlots.fromFrame`），**章节帧位与时间轴零耦合**——不消耗场景时长，场景数增减只平移挂靠点。

**配色表（`constants.ts` COLORS）**：

| key | 值 | 用途 |
|---|---|---|
| `bg` | `#0E1220` | 主背景 |
| `bg2` | `#171C30` | 次背景 |
| `card` | `#1A2138` | 卡片底 |
| `cardBorder` | `#2A3352` | 卡片描边 |
| `accent` | `#5B8DFF` | 强调色 |
| `text` | `#F2F5FF` | 正文 |
| `sub` | `#9AA3C0` | 次级文字 |
| `hl` | `#FFD54A` | 高亮/金下划线 |
| `ok` | `#4ADE80` | 正确项 |
| `warn` | `#F87171` | 警示项 |
| `white` | `#FFFFFF` | 章节字白 |
| `ink` | `#0E1220` | 幕5 白底深字 |
| `chapterBg` | `#4F6EF7` | 章节转场蓝块 |

**固定台词（幕1/幕5，`gen_tts_template.py` FIXED_LINES，与画面/字幕绑死，任何一单都【不覆写、不重合成】`public/audio/fixed/`）**：

- 幕1：act1「你好啊同学，本题由Whats your future为你解答，坐好发车」5.201s
- 幕5：act5「这就是本道题的解法，感谢你选择Whats Your Future，祝你一战上岸！」5.901s；幕5 画面白底打字机第一行「WhatsYourFuture出品」、**第二行「祝你一战上岸」**（`Act5Outro.tsx`）

**TTS 单 take 策略（Ruling-13，`gen_tts_template.py`）**：每句 1 take + 完整性校验（尾部 RMS 剖面：末 80ms vs 前 240/480ms），完整即采用；截断删产物重试，上限 `MAX_ATTEMPTS = 3`——**这是失败兜底，不是择优**（spec §12 的 3-take 烧满择优已作废）。音色 `Cherry`、模型 `qwen-tts`、请求间隔 ≥3s；重跑必须删 `public/audio` 整批重来，**禁单句补录**（防跨批次音色漂移）。Key 走 `DASHSCOPE_API_KEY` 环境变量（脚本另有本地回落文件，见脚本头注释）。

## 8. 渲染引擎约束

- `remotion.config.ts` 指向本机 Edge：`Config.setBrowserExecutable("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe")`——即**禁止下载 Chrome Headless Shell**（离线/无下载环境渲染）。同文件还钉死 `VideoImageFormat("jpeg")` 与 `OverwriteOutput(true)`。
- **语速恒 1.0**：`audio_meta.rate = 1.0`、`gen_tts_template.py` 同值钉死；时间轴公式不做任何变速补偿。
- `package.json` 脚本：`npm run studio`（`remotion studio --no-open`）、`npm run render:169` / `render:916`（preview 输出）、`npm test`（vitest run）。
- 契约校验失败 = bundle 加载即 throw（`src/engine/data.ts`），渲染直接中止，不产废片。

## 9. v0.2 变更记录（2026-08-30，tag template-v0.2）

**渲染输出变更（唯一）：**
- `src/acts/components/ProblemPanel.tsx` Line 组件：容器加 `flexWrap:"wrap"+rowGap:8`，math 段 span 加 `whiteSpace:"nowrap"+flexShrink:0`（KaTeX 原子化）——修复 math-dense 题面一行段数过多时 flex 挤压 KaTeX 导致的行内公式崩坏（分式拆行/基线下沉/元素骑跨，E2E R2 实证 2/3 题系统性触发）。golden 短行修前/修后逐像素零漂移（MD5 四方对拍一致）。commit `9f73682`。

**已知限制（v0.3 排期兜底）：**
- 单段超长 math（约 >50 个简单字符）在原子化下会整体横向溢出卡片（有界横溢，非挤压崩坏）；v0.3 方案=math span `maxWidth:100%`+overflow 兜底，或服务端契约做 formula 长度校验。当前由 QA 审帧拦截溢出帧兜底（几何溢出类 FAIL 会触发驳回重生成）。

**QA 工具链同步更新（渲染输出零变化，Ruling-15 定性=QA 工具可修）：**
- `scripts/pick_frames.mjs`：章节标题采样帧 +34（CHAPTER_REVEAL_F，避开扫入动画中段假阴性，commit `b0c470c`）。
- `scripts/qa_stills.mjs`（新增，commit `a22dad6`）：单进程 bundle 一次逐帧 renderStill，替代逐帧 `npx remotion still`（各付 ~35s 冷启动）；29 帧 59-70s。
- `scripts/qa_glm.py`：审帧并发化（ThreadPoolExecutor，默认 4，`QA_GLM_CONCURRENCY` 可调，commit `a234a18`）+ 逐帧错误归因落盘（`ERROR <frame>\t<摘要>` 行，commit `2d39d6a`）。判定语义/FAIL 标准/report 格式/exit 语义零变化。

## 10. v0.3 变更记录（2026-08-30，动态自适应排版）

**背景**：v0.2 的 nowrap 修复把「挤压崩坏」换成「超长横溢/裁切」，且通法步骤过多时列表超出画面下边界被裁切（E2E R2 job1 六轮同因）——生成侧随机重试救不了，模板必须具备确定性自适应能力（Task 15a，Ruling-16 v0.3 重封版）。

**两类「动态放下」机制（全部纯函数确定性：无 DOM 测量、无 Math.random、无时钟、无异步；零新依赖）：**

1. **ProblemPanel 行级宽度自适应**（`src/acts/components/ProblemPanel.tsx`）：按字符估算整行需求宽——CJK（含全角标点/「」）≈1.0em、ASCII/数字/半角标点≈0.55em、KaTeX math 段≈0.6em×TeX 源字符数（`src/engine/fit.ts` 共享估算函数）；超出该行可用宽度预算（`problemFull.w` 1400 − 面板 padding 2×40/2×24 − 面板 border 2×3 − 行 padding 2×18 − 高亮条 6 = 1272px）→ 整行 fontSize 按 budget/needed 等比缩小，**floor 0.6**。v0.2 的 nowrap+flexShrink:0 与 flexWrap 保留为前提；未触发行恒为 1（逐行独立判定）。
2. **列表高度自适应**（`GeneralList.tsx` 通法列表 / `ChecklistCard.tsx` 检查清单）：估算总高（Σ 每步估行数×行高 1.4 + marginTop + chip 区）与可用高度预算（`main.h` 860 − CardShell padding 80 − border 4 − chip≈44 − chip marginBottom 26 ≈ 706px；ChecklistCard 另扣结论卡 220 偏移）比较，超出 → 整列表 fontSize/margin 按 budget/needed 等比缩小（字号缩小时估行数同步复算，`fitScale` 收敛式求解 needed(s)=budget 不动点），**floor 0.55**。lineHeight 用无单位系数/normal，随 fontSize 自动等比。
3. **floor 兜底行为**：缩到下限仍放不下的极端内容 → clamp 在 floor 照常渲染（残余溢出由 QA 审帧几何 FAIL 兜底拦截、驳回重生成）。单测见 `src/engine/fit.test.ts`（15 例，含 floor 兜底与确定性）。

**零漂移门槛（已验证）**：golden 三帧（f313 题面 / f4224 清单 / f4968 通法）改前改后 MD5 逐字节一致（f313=`c0b5d9b1b244e9cd8ef0c69c6f5c5797` 与 v0.2 四方对拍同值）——未触发内容 scale 恒 1，自适应零影响。

**压力 fixture**（`scripts/fixtures/stress-content.json` + `stress-audio_meta.json`，结构与 golden 同构，仅测试用、绝不覆写 golden）：长题目行、50 字符 TeX 长公式、generalMethod 9 步。渲染实测：L1 长文字行缩至 0.77、L2 长公式行缩至 0.661 单行完整、L3 未触发保持原大；9 步列表缩至 0.61 全部入画。（数字勘误 2026-08-31 评审独立复测：L2 实为 50 字符/0.661，渲染行为不变。）

**已知限制**：① 系数为目验校准的经验值，KaTeX 段按 TeX 源字符数估算系统性偏高（偏保守安全侧）；② floor 兜底态在极端内容下仍可能残余溢出/贴边，依赖 QA 审帧拦截；③ 行级/列表级估算不含图片等非文本元素（模板当前无此类元素）。
