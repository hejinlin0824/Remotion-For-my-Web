# Phase 2 Java 工厂服务实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Boot 3 单模块微服务：输入一道考研数学题（文本/截图），无人值守产出五幕讲题视频 mp4。

**Architecture:** 状态机驱动的流水线（QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→RENDERING→QA→DONE/FAILED），H2 文件库存任务，@Scheduled 轮询领单，信号量隔离四种外部资源；LLM 内容由 GLM 三工位产生，经 V1-V4 校验链，TTS 单 take 管线产音频，模板副本覆写后 npx remotion 渲染，GLM 审帧 QA。

**Tech Stack:** Java 21 (Temurin @ D:\Java_opensdk_jv21)、Spring Boot 3、Maven 3.9.16 单模块、H2 文件库、java.net.http.HttpClient（不引额外 HTTP 库）、jUnit5 + Mockito + MockMvc。

**Spec:** `docs/superpowers/specs/2026-08-28-remotion-java-design.md`（§9-15 为本计划范围；冲突时 spec 与台账 Ruling 谁新听谁——本计划 Global Constraints 已裁定）。Phase 1 产物 = 封版模板 `template/`（tag template-v0.1），契约权威 = `template/README.md`。

## Global Constraints（每个任务默认全部成立）

1. **封版模板只读**（Ruling-14/15）：`template/` 下任何文件服务不得修改；服务只做复制到 workspace 后覆写副本的三个位置：`src/data/content.json`、`src/data/audio_meta.json`、`public/audio/lines/line_NN.wav`。
2. **16:9 唯一画幅**（Ruling-12）：API `aspect` 只接受 `"16:9"`，其他值 → 400；渲染只用 `Lecture169` composition。模板内 Lecture916 休眠不调用。
3. **TTS 单 take**（Ruling-13，覆盖 spec §12"≤3 take 择优"）：每句 1 take + 完整性校验，截断句重合成（每句最多 3 次尝试，异常计一次）；某句 3 次全败 → 整批废弃重录一次（禁单句补录，跨批音色漂移）→ 再败 FAILED。
4. **API key 不入代码库、不打日志**：GLM key 解析顺序 `ZHIPU_API_KEY` → `ZHIPUAI_API_KEY` → `GLM_API_KEY` → `secrets.local.yml` 的 `glm.api-key`；DashScope key `DASHSCOPE_API_KEY` → `secrets.local.yml` 的 `tts.api-key`。**服务代码不得内置 settings.json 等文件回落**（T12 footgun 教训）；key 值绝不出现在日志/异常/报告。
5. **渲染浏览器**：本机 Edge（`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`），模板 remotion.config.ts 已配置；**禁止下载 Chrome Headless Shell**。
6. **Windows/Git Bash 环境**：所有 shell 命令 `cd /e/codebase/remotion_java && ...`（cwd 每次调用重置）；构建用 `JAVA_HOME=D:\Java_opensdk_jv21`；`mvn` 已装（3.9.16）。
7. **子进程环境**：spawn npx/node/python 时透传环境并追加 `NO_PROXY='*'`（Windows 注册表死代理坑，sim-001 实证）；Node 24 已装。
8. **无 ffprobe**：WAV 时长 = 文件字节数 ÷ byte_rate（头 44 字节标准 PCM；DashScope 头部 data-size 是 INT32_MAX 垃圾值不可信）。
9. **测试分层**（spec §15）：单元/契约测试零 API 成本（fixture 回放 + MockWebServer 或 fake）；真调 API/真渲染的测试打 `@Tag("slow")`，默认 `mvn test` 不跑，`mvn test -Pslow` 才跑。
10. **固定资产复用**：`template/public/audio/fixed/act1.wav`(5.201s)/`act5.wav`(5.901s) 是模板资产，每单直接用，不重合成；台词与时长写入 audio_meta 的 fixed 字段。
11. **章节标题不迁移**：三章标题（知识点回顾/本题解法/以后怎么做）是内容无关的模板常量（engine/constants.ts CHAPTER_TITLES），保持烘焙；sim-001 曾建议迁 content.json.meta，裁定不需要（每条视频标题相同）。
12. **每任务一提交**，`feat(server): ...` / `test(server): ...` 规范；提交前该任务测试全绿。

## 与 spec 的偏差（控制器裁定，晚间报告需向用户披露）

| # | 偏差 | 理由 |
|---|------|------|
| 1 | 渲染超时 **30 分钟**（spec §11 写 15 分钟） | sim-001 实证：1080p 渲染 ≈31min@并发4、552s@单任务；15min 在并发下必误杀。取 30min。 |
| 2 | aspect 非 16:9 → **400 拒绝**（spec D5 原本 9:16 可选） | Ruling-12 用户裁定放弃 9:16。 |
| 3 | TTS 单 take（spec §12 三 take 择优） | Ruling-13。 |
| 4 | QA 复用模板脚本子进程（`node scripts/pick_frames.mjs` + `npx remotion still` + `python scripts/qa_glm.py`），不 Java 重写 | 脚本是封版模板资产（Ruling-2 双实现防漂移哲学）；qa_glm exit code + report.md 解析。 |
| 5 | GLM key 无 settings.json 回落（sim 管线有） | T12 已定该回落是凭据外发 footgun。 |

## 文件结构

```
server/
├── pom.xml
└── src/
    ├── main/java/com/wyf/factory/
    │   ├── FactoryApplication.java          # @SpringBootApplication
    │   ├── config/AppProperties.java        # 路径/并发/超时配置绑定
    │   ├── config/Secrets.java              # key 解析（env→secrets.local.yml）
    │   ├── domain/Job.java                  # 实体+状态机字段
    │   ├── domain/JobStatus.java            # QUEUED..DONE/FAILED 枚举
    │   ├── domain/StageHistoryEntry.java    # 阶段历史 JSON 结构
    │   ├── repo/JobRepository.java          # JpaRepository + 乐观锁领单查询
    │   ├── api/JobsController.java          # REST §13
    │   ├── api/dto/*.java                   # 请求/响应 DTO
    │   ├── pipeline/JobOrchestrator.java    # 轮询领单+阶段推进+断点续跑+取消
    │   ├── pipeline/ResourceSemaphores.java # GLM(2)/TTS(1)/RENDER(2)/QA(1)
    │   ├── pipeline/CallbackClient.java     # 终态回调
    │   ├── glm/GlmClient.java              # HTTP 客户端（chat+vision）
    │   ├── glm/GlmException.java
    │   ├── stations/ExtractStation.java     # EXTRACTING
    │   ├── stations/MaterialStation.java    # GENERATING-a
    │   ├── stations/ScriptStation.java      # GENERATING-b
    │   ├── stations/Prompts.java            # 三工位+judge 的 prompt 模板
    │   ├── validate/Validator.java          # V1-V4 接口 + ValidationResult
    │   ├── validate/V1Structural.java
    │   ├── validate/V2Fidelity.java
    │   ├── validate/V3Refs.java
    │   ├── validate/V4Judge.java
    │   ├── content/ContentJson.java         # content.json 模型（Map 导航+Jackson 绑定）
    │   ├── tts/DashScopeTts.java            # HTTP 客户端 + 单 take 重试
    │   ├── tts/WavDuration.java             # 字节数÷byte_rate
    │   ├── tts/RmsCheck.java                # 完整性判据
    │   ├── tts/TtsPipeline.java             # 批量合成+整批重录+audio_meta 生成
    │   ├── render/WorkspaceManager.java     # 副本复制+三处覆写
    │   ├── render/RenderWorker.java         # npx remotion render 子进程
    │   ├── render/QaFrameCheck.java         # pick_frames+still+qa_glm 子进程
    │   └── render/TimelineCalc.java         # 时间轴公式（pick_frames.mjs 镜像）
    └── main/resources/application.yml
    └── test/java/com/wyf/factory/...        # 与 main 镜像 + fixtures/
```

---

### Task 1: 服务器脚手架 + 构建/toolchain 就绪

**Files:**
- Create: `server/pom.xml`, `server/src/main/java/com/wyf/factory/FactoryApplication.java`, `server/src/main/java/com/wyf/factory/config/AppProperties.java`, `server/src/main/resources/application.yml`, `.gitignore`（根，追加 `server/target/`、`data/`、`secrets.local.yml`——根 .gitignore Phase 1 已预铺，核对补缺）

**Interfaces (Produces):** `FactoryApplication` 可启动；`AppProperties` 绑定 `app.*` 配置（后续所有任务消费）；`mvn` 命令模式确立。

- [ ] **Step 1: pom.xml**。要点：`spring-boot-starter-parent` 3.3.x；java 21 via `maven.compiler`；依赖仅 `spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`com.h2database:h2`、`spring-boot-starter-test`(test)、`spring-mock-mvc` 就在 starter-test 里。**不引** Lombok/HTTP 客户库（用 JDK HttpClient）。`maven-surefire-plugin` 配 `<excludedGroups>slow</excludedGroups>`，profile `slow` 时 `<groups>slow</groups>`。

```xml
<properties>
  <java.version>21</java.version>
  <maven.compiler.release>21</maven.compiler.release>
</properties>
```

- [ ] **Step 2: application.yml**

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/jobs;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
  jpa:
    hibernate.ddl-auto: update
  jackson:
    default-property-inclusion: non_null
server:
  port: 8080
app:
  template-dir: ../template          # 相对 server/ 运行目录；绝对路径可在 secrets.local.yml 覆盖
  workspace-dir: ../workspace
  artifacts-dir: ../artifacts
  render:
    timeout-minutes: 30
    concurrency: 2
  glm:
    base-url: https://open.bigmodel.cn/api/paas/v4
    model: glm-4.5-flash             # 占位实现层参数，模型名以 Secrets/prompts 层配置为准
    concurrency: 2
    timeout-seconds: 120
  tts:
    interval-ms: 3000
    cooldown-ms: 15000
    max-attempts-per-line: 3
  qa:
    max-rounds: 5
  retry:
    content-max: 3
```

注：GLM 模型名走 `secrets.local.yml`/env `GLM_MODEL` 可覆盖，默认值实现者按 `template/scripts/qa_glm.py` 里实际请求的 model 字段对齐（读该文件前 30 行即可拿到真名）。

- [ ] **Step 3: AppProperties** `@ConfigurationProperties(prefix="app")`，字段与 yml 一一对应（嵌套 static class Render/Glm/Tts/Qa/Retry），`@EnableConfigurationProperties` 挂启动类。

- [ ] **Step 4: 冒烟验证**

```bash
cd /e/codebase/remotion_java/server && JAVA_HOME="D:\Java_opensdk_jv21" mvn -q compile
cd /e/codebase/remotion_java/server && JAVA_HOME="D:\Java_opensdk_jv21" timeout 30 mvn -q spring-boot:run &  # 起来后 curl localhost:8080/actuator/health 或直接看日志 Started
```
（无 actuator 依赖就验证日志出现 `Started FactoryApplication`；H2 文件在 `server/data/` 生成。）

- [ ] **Step 5: Commit** `feat(server): 脚手架——Spring Boot 3 + H2 + 配置绑定，slow 测试分组就位`

---

### Task 2: Job 领域模型 + 状态机 + 仓库

**Files:**
- Create: `domain/Job.java`, `domain/JobStatus.java`, `domain/StageHistoryEntry.java`, `repo/JobRepository.java`
- Test: `domain/JobStatusTest.java`, `repo/JobRepositoryTest.java`（`@DataJpaTest`）

**Interfaces (Produces):**
- `Job` 字段：`String id`(UUID)、`JobStatus status`、`String stage`、`String inputType`("TEXT"|"IMAGE")、`String inputText`、`byte[] imageBase64`( lob, 可空 )、`String aspect`、`String voice`、`String callbackUrl`、`int extractRetries/genRetries/reviewRetries/ttsRetries/qaRounds`、`String lastError`、`String errorMessage`、`String artifactsDir`、`List<StageHistoryEntry> stageHistory`(@Convert JSON)、`LocalDateTime createdAt/updatedAt`、`@Version long version`（乐观锁）。
- `JobStatus`: `QUEUED, EXTRACTING, GENERATING, REVIEWING, SPEAKING, RENDERING, QA, DONE, FAILED, CANCELLED`。
- `JobRepository` 方法：`Optional<Job> claimNextQueued()` 实现 = `@Query` 找最旧 QUEUED + 加乐观锁更新（简单实现：`findFirstByStatusOrderByCreatedAtAsc` 后由编排器 CAS 更新 status 抢占，撞 `OptimisticLockException` 视为别人抢走）。

- [ ] **Step 1: 失败测试**——状态机合法迁移表驱动测试：

```java
// QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→RENDERING→QA→DONE 为唯一正向链
// QA→RENDERING 回退（QA 轮次重渲染）；REVIEWING→GENERATING 回退（驳回重生成）
// 任意→FAILED；QUEUED→CANCELLED；SPEAKING 之前任意→CANCELLED
static boolean canTransit(JobStatus from, JobStatus to) { ... } // 实现 + 测试断言合法/非法表
```

- [ ] **Step 2: 跑测试确认失败** → **Step 3: 实现 JobStatus.canTransit + Job 实体 + Repository**（JPA 注解标准写法；stageHistory 用 `AttributeConverter` 序列化为 JSON 字符串存 CLOB）→ **Step 4: `mvn test` 绿**（@DataJpaTest 存取一条 Job、stageHistory 往返、乐观锁并发抢占验证：两线程 claim 同一 QUEUED 只成功一次）→ **Step 5: Commit** `feat(server): Job 领域+状态机+仓库（乐观锁领单）`

---

### Task 3: REST API

**Files:**
- Create: `api/JobsController.java`, `api/dto/CreateJobRequest.java`, `api/dto/BatchJobRequest.java`, `api/dto/JobView.java`, `api/GlobalExceptionHandler.java`
- Test: `api/JobsControllerTest.java`（@WebMvcTest + mock service 层——先定义 `JobService` 于本任务内：入队/查询/取消的薄封装）

**Interfaces:**
- Consumes: Task 2 的 Job/Repository。
- Produces: `POST /api/v1/jobs` body `{inputType:"TEXT"|"IMAGE", text?, imageBase64?, aspect?="16:9", voice?="Cherry", callbackUrl?}` → `202 {jobId}`；校验失败（TEXT 无 text / IMAGE 无 imageBase64 / aspect≠"16:9" / inputType 非法）→ `400 {error}`。`POST /api/v1/jobs/batch` `{items:[×N]}` → `202 {jobIds}`。`GET /api/v1/jobs/{id}` → `200 JobView`（id/status/stage/stageHistory/各 retry 计数/error/artifacts 清单/createdAt/updatedAt）| `404`。`GET /api/v1/jobs?status=&page=&size=` → 分页。`DELETE /api/v1/jobs/{id}` → `202`（置取消标记；DONE/FAILED 幂等 200）。`GET /api/v1/jobs/{id}/video` → `200 video/mp4` 流（artifacts 下 final.mp4；无则 404）。

- [ ] **Step 1: 失败的 MockMvc 测试**（六端点各 1 正 1 反：400 校验矩阵、404、202、分页、video 404）→ **Step 2: 跑挂** → **Step 3: 实现 Controller+DTO+Service+ExceptionHandler**（@Valid 手写校验即可，不引 validation 依赖；video 用 `ResourceHttpRequestHandler`/`InputStreamResource`）→ **Step 4: `mvn test` 绿** → **Step 5: Commit** `feat(server): REST API v1（入队/批量/查询/取消/视频流）`

---

### Task 4: GLM 客户端 + 密钥解析

**Files:**
- Create: `config/Secrets.java`, `glm/GlmClient.java`, `glm/GlmException.java`
- Test: `glm/GlmClientTest.java`（JDK HttpClient 不可 Mock——抽象出 `HttpTransport` 函数接口，测试用 fake；另加 `@Tag("slow")` 真调测试 1 条）

**Interfaces:**
- Produces:
  - `Secrets.glmKey()` / `Secrets.ttsKey()`：env → `secrets.local.yml`（YAML 手工两行解析即可：找 `glm.api-key:` / `tts.api-key:` 行取值，不引 snakeyaml；文件不存在返回 null）。缺失时抛 `GlmException("GLM key 未配置…")`（**不含 key 值**）。
  - `GlmClient.chat(String systemPrompt, String userPayload, boolean expectJson)` → `String`（响应文本）。内部：POST `{base-url}/chat/completions`，body `{model, messages:[{role:system|user}], temperature:0.2, max_tokens:8192}`（具体字段名以 qa_glm.py 现行请求为准——**实现者必读 template/scripts/qa_glm.py 全文**，URL/model/认证头逐字对齐）；`Authorization: Bearer <key>`。429/5xx → 指数退避 2s/4s/8s 重试 3 次；超时 `app.glm.timeout-seconds`。视觉：`chat(String systemPrompt, String imageBase64, String mime)` 走该 API 的 image_url base64 形态（同样以 qa_glm.py / dashscope 惯例对齐，qa_glm 若无视觉样例则按 OpenAI 兼容 `{"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}}` 形态）。并发信号量由调用方持有（Task 11），客户端不管。
  - `GlmException extends RuntimeException`，字段 `boolean retryable`。

- [ ] **Step 1: 失败测试**——key 解析优先级（env mock/fallback 文件两态）；fake transport 断言请求 URL/头/体形状；429 退避后重试成功路径；3 次全 429 抛 retryable。→ **Step 2: 跑挂** → **Step 3: 实现** → **Step 4: 单测绿**（slow 真调测试：`"回复两个字：好的"` 期待包含"好"，@Tag("slow")）→ **Step 5: Commit** `feat(server): GLM 客户端+密钥解析（env→secrets.local.yml，退避重试）`

---

### Task 5: EXTRACTING 审题工位

**Files:**
- Create: `stations/ExtractStation.java`, `stations/Prompts.java`（先建类，只含审题 prompt）
- Test: `stations/ExtractStationTest.java` + `fixtures/extract/*.json`（录 2 份真实 GLM 回放：文本题/截图题——slow 真调跑一次后把响应存 fixture）

**Interfaces:**
- Consumes: GlmClient。
- Produces: `ExtractResult { String problemType; List<Line> lines; }`，`Line { String id; List<Seg> segments; }`，`Seg { String type; /*text|math*/ String value; }` —— 该结构逐字对齐 template/README.md §3 problem 段与 spec §6。
- 规则（spec §9.1）：文本路径=规整化分段（把输入按数学式切 text/math 段）；图片路径=GLM 视觉提取；输出 JSON `{"problemType":"...","lines":[...]}`；读不出题/非数学题 → `FatalExtractException`（致命，不重试）。
- Prompt 骨架（Prompts.extract，全文写入实现，含 1 个 few-shot 输出示例——从 template/src/data/content.json 的 problem 段复制）：
  system: "你是考研数学审题员。把用户给的题目转为 JSON。只输出 JSON，不要 markdown 代码块。problemType ∈ {基础题,计算题,证明题,应用题}。lines[].id 从 L1 递增；segments[].type ∈ {text,math}，数学式（含符号、上标下标、分数）一律切进 math 段并用 LaTeX 表示，文字叙述用 text 段。题目读不出或不是数学题时输出 {\"error\":\"...\"}"

- [ ] **Step 1: 失败测试**——fake GLM 返回合法 JSON → 解析成功；返回 `{"error":..}` → Fatal；返回非 JSON → 可重试 GlmException；fixture 回放 2 份。→ **Step 2: 跑挂** → **Step 3: 实现**（Jackson 读树后手映射到 record，容忍 ```json 包裹）→ **Step 4: 绿** → **Step 5: slow 真调一次把两份响应存 fixtures/** → **Step 6: Commit** `feat(server): EXTRACTING 审题工位（文本/截图→problem.lines）`

---

### Task 6: GENERATING 素材+剧本双工位

**Files:**
- Create: `stations/MaterialStation.java`, `stations/ScriptStation.java`, `content/ContentJson.java`
- Modify: `stations/Prompts.java`（加 material/script 两 prompt）
- Test: `stations/MaterialStationTest.java`, `stations/ScriptStationTest.java` + fixtures

**Interfaces:**
- Consumes: GlmClient、ExtractResult。
- Produces:
  - `MaterialStation.generate(ExtractResult)` → `Material { List<Knowledge> knowledge; List<Step> steps; List<Pitfall> pitfalls; List<MethodItem> generalMethod; }`（字段逐字对齐 template/README.md §3：knowledge{claim,formula,premise,trap}、steps{usesAnchor,statement,derivation,note}、pitfalls{claim,why}、generalMethod{step,trick}）。
  - `ScriptStation.assemble(ExtractResult, Material)` → `ContentJson`（完整 content.json：meta/problem/knowledge/steps/pitfalls/generalMethod/scenes——scenes 由剧本工位 GLM 输出）。
  - `ContentJson`：Jackson 绑定 POJO + `toJson()` 紧凑序列化（写入副本用）。**字段名与 template/src/data/content.json（golden）逐字一致——实现者必读该文件**。
- Prompt 要点（全文写入 Prompts，含 few-shot = golden content.json 全文）：素材工位输入题干 JSON → 输出四段素材 JSON；剧本工位输入题干+素材 → 输出 scenes[]（组件白名单、ttsText 口语化、stepRef/usesAnchor 挂靠），**prompt 中内嵌 template/README.md §3 的组件矩阵与条数范围**（实现者从 README 复制进 prompt 常量）。重试接口：`generate(..., List<String> errors)` 把上一轮校验错误清单附在请求尾部。

- [ ] **Step 1: 失败测试**——fixture 回放合法输出→绑定成功；缺字段/越界条数→抛可重试异常（错误消息含具体差异条目，供回传）；`ContentJson.toJson()` 与 golden 结构对拍（序列化再反序列化相等）。→ **Step 2: 跑挂** → **Step 3: 实现** → **Step 4: 绿** → **Step 5: slow 真调各 1 次存 fixture** → **Step 6: Commit** `feat(server): GENERATING 素材+剧本工位（golden few-shot + 错误清单回传重试）`

---

### Task 7: 校验链 V1-V4

**Files:**
- Create: `validate/Validator.java`, `validate/V1Structural.java`, `validate/V2Fidelity.java`, `validate/V3Refs.java`, `validate/V4Judge.java`
- Test: `validate/V1StructuralTest.java`, `V2FidelityTest.java`, `V3RefsTest.java`, `V4JudgeTest.java` + fixtures（golden content.json 必须全绿；另造 10+ 违规变体各打中一条规则）

**Interfaces:**
- Consumes: ContentJson、ExtractResult、GlmClient(V4)。
- Produces: `interface Validator { ValidationResult validate(ValidationContext ctx); }`，`ValidationResult { boolean pass; List<String> errors; }`。
- **V1 结构**（引擎 contract.ts 的 Java 超集 + 条数硬校验 + 终审 §7 结构规则包，逐条实现）：
  1. meta.aspect=="16:9"、problemType 枚举；2. act ∈ {2,3,4}；3. component ∈ 7 白名单；4. act2 首场 problem-card 且 act2 ≥1 个 knowledge-card；5. act3/act4 各 ≥1 场；6. 条数范围 knowledge 2-4 / steps 3-10 / pitfalls 1-3 / generalMethod 3-6；7. **结构规则包**（终审 §7）：derivation-popup 必须紧跟同 stepRef 的 step-card、同 stepRef 的 step-card 至多一张；steps 按序全引用（结论条正确性）；checklist-card 至多一场；act4 itemRef 严格连续递增 1..generalMethod.length；8. scenes[].props 引用字段存在性（stepRef ≤ steps.length、usesAnchor ∈ lines ids、pitfallRef ≤ pitfalls.length）。
  9. audio_meta 一致性字段在 Task 8 写入时由 TtsPipeline 保证常量（fps=30/breathSec=0.18/act5TailSec=2.0），V1 校验其值恒等。
- **V2 题干保真**：contentJson.problem vs ExtractResult.lines——归一化比较（全空白去除、全角半角归一、标点归一（，,。.？！?!）；math 段 LaTeX 去空格比对）逐段逐字。
- **V3 引用合法**：与 V1-8 重叠部分以 V3 为准实现（V1 只查枚举与计数，V3 查指向）。
- **V4 语义**：GlmClient 单次调用，输入 content.json 全文 + 评审 prompt（步骤跳跃/条件对应/讲解与组件匹配/解题正确性），输出首行必须 `PASS` 或 `REJECT`；REJECT 时后续行=逐条理由（回传重生成）。JSON 模式关，纯文本。
- 驳回编排（ReviewService 或在 Orchestrator 内联即可）：V1-V4 顺序执行，全部错误合并 → 素材/剧本工位重生成（≤app.retry.content-max 次），V2 失败回剧本工位、V4 失败按理由回素材或剧本（简化：全部回剧本工位，带上素材）。

- [ ] **Step 1: 失败测试**——golden 全绿；每条规则 1 个违规 fixture 打中（ fixture 从 golden 程序化变体或手写小 JSON）；V2 归一化用例（全角/空白/标点容差 + 真差异必抓）；V4 fake GLM PASS/REJECT 两路。→ **Step 2: 跑挂** → **Step 3: 实现** → **Step 4: 绿** → **Step 5: Commit** `feat(server): 校验链 V1-V4（结构超集+保真+引用+LLM-judge）`

---

### Task 8: TTS 管线

**Files:**
- Create: `tts/DashScopeTts.java`, `tts/WavDuration.java`, `tts/RmsCheck.java`, `tts/TtsPipeline.java`
- Test: `tts/WavDurationTest.java`, `tts/RmsCheckTest.java`, `tts/TtsPipelineTest.java`（fake transport）；fixture 用 `template/public/audio/lines/line_01.wav`（完整样本）+ 测试资源里手工构造截断样本（完整 wav 砍尾 40% 生成）

**Interfaces:**
- Consumes: Secrets.ttsKey()、ContentJson（scenes[].ttsText）、AppProperties.tts。
- Produces: `TtsPipeline.synthesizeAll(ContentJson, Path linesDir)` → `AudioMeta`（写出 `audio_meta.json` 到调用方给定路径）。`AudioMeta` 结构**逐字对齐 template/src/data/audio_meta.json**（实现者必读该文件确认字段名；fixed 两句 act1/act5 直接读 template/public/audio/fixed 的既有 wav 字节数算时长）。`TimelineCalc`（Task 9 文件但本任务就建）：总帧数公式与 template/scripts/pick_frames.mjs 逐行镜像——`round(秒*30)` 逐场累加、幕间 0.18s 气口（act5 起点抹气口）、尾停 2s；**测试断言 golden 17 场输入 → totalFrames=5334**（音频时长取 audio_meta.json golden 值）。
- DashScope 请求形状：**实现者必读 template/scripts/gen_tts_template.py 全文**，URL/model/voice/参数逐字移植（qwen-tts、voice=Cherry、rate=1.0）；响应 wav 字节落盘。
- 完整性判据（RmsCheck，逐字移植 spec §12 = gen_tts_template.py 现行实现）：`last80ms RMS < 100` OR（`last80/prev240 < 0.35` 且 `last80/prev480 < 0.35` 且 `时长 ≥ 本轮最长 take 的 92%`）——实现者从 gen_tts_template.py 抄公式与常数，单测用真 wav/截断 wav 双样本断言判定方向。
- 重试编排（Ruling-13）：每句 1 take → RmsCheck 不过 → 重合成（同句计数 ≤3，异常计一次）→ 3 败整批废弃重录 ≤1 次 → 再败抛 Fatal。句间 sleep ≥ app.tts.interval-ms；429 → 指数退避 + 15s 冷却（冷却期内后续句全部顺延）。

- [ ] **Step 1: 失败测试**——WavDuration（golden wav 字节数÷byte_rate 与 audio_meta golden 值 ±1ms）；RmsCheck 双样本；fake transport 全流程：3 句合成全过→audio_meta.json 写出且 totalFrames 与 TimelineCalc 一致；1 句连续截断→重试→整批重录路径各触发一次。→ **Step 2: 跑挂** → **Step 3: 实现** → **Step 4: 绿** → **Step 5: slow 真调 1 句**（"测试"两字，落 target/ 不入库）→ **Step 6: Commit** `feat(server): TTS 管线（单 take+截断重试+整批重录+audio_meta+时间轴镜像）`

---

### Task 9: 渲染 Worker（工作区+子进程+QA 审帧）

**Files:**
- Create: `render/WorkspaceManager.java`, `render/RenderWorker.java`, `render/QaFrameCheck.java`
- Test: `render/WorkspaceManagerTest.java`, `render/QaFrameCheckTest.java`（进程调用抽象成 `ProcessRunner` 接口 fake；真渲染 @Tag("slow")）

**Interfaces:**
- Consumes: ContentJson、AudioMeta、AppProperties。
- Produces:
  - `WorkspaceManager.create(long jobId)` → Path：递归复制 template→`workspace/{jobId}/`（排除 out/、node_modules/——副本用根 node_modules 不行，**副本内 npm 依赖怎么解决：复制时 junction `workspace/{jobId}/node_modules` → `template/node_modules`（sim-001 先例，`cmd //c mklink /J`）**）；覆写 `src/data/content.json`、`src/data/audio_meta.json`、写 `public/audio/lines/line_NN.wav`（NN 两位从 01，scenes 数组序）；`workspacePath(jobId)`、`cleanup(jobId)`（先 `cmd //c rmdir` junction 再删树——**绝不 rm -rf 穿 junction**，sim-001 教训）。
  - `RenderWorker.render(Path ws)` → Path mp4：spawn `cmd //c "npx remotion render Lecture169 out/final.mp4"`（cwd=ws，超时 app.render.timeout-minutes 强杀进程树=destroyForcibly + 子孙（用 `taskkill /T /F`），环境透传+`NO_PROXY=*`）；产出拷到 `artifacts/{jobId}/final.mp4`。
  - `QaFrameCheck.check(Path ws, Path reportDir)` → `QaResult {boolean pass; List<String> fails; int framesChecked;}`：①spawn `node scripts/pick_frames.mjs`（cwd=ws）解析 stdout 帧行得帧清单 ②每帧 spawn `npx remotion still Lecture169 out/qa/<name>.png --frame=<N>` ③spawn `python scripts/qa_glm.py`（cwd=ws，env 带已解析 key 变量，**绝不打印**）④exit!=0 或解析 report.md FAIL 行 → pass=false。
- 帧行名含组件后缀（如 `s-s05-step-card`），解析用前缀匹配（T11 教训）。

- [ ] **Step 1: 失败测试**——WorkspaceManager：临时目录假装 template（最小假文件树）复制排除规则、junction 建拆、覆写三处内容断言；RenderWorker：fake runner 断言命令行/超时参数（不真渲）；QaFrameCheck：fake runner 三步编排与 FAIL 解析。→ **Step 2: 跑挂** → **Step 3: 实现** → **Step 4: 绿** → **Step 5: slow 真渲一次**（用 template 原样 golden 副本渲 `--frames=0-90` 短片段即可验证管线，不全渲）→ **Step 6: Commit** `feat(server): 渲染 Worker（副本覆写/junction/子进程/QA 审帧链）`

---

### Task 10: 编排器（状态机推进+断点续跑+并发+回调）

**Files:**
- Create: `pipeline/JobOrchestrator.java`, `pipeline/ResourceSemaphores.java`, `pipeline/CallbackClient.java`
- Test: `pipeline/JobOrchestratorTest.java`（全 fake 依赖注入：假 Glm/Tts/Render/Qa bean）

**Interfaces:**
- Consumes: 前面全部。
- Produces: `@Scheduled(fixedDelay=2000) poll()`：查 QUEUED 最旧一条 → CAS 抢占 → 提交独立线程跑 `process(job)`；`process` 按 status 从断点阶段续跑（**断点续跑判定**：workspace/{jobId}/src/data/content.json 存在→跳过 EXTRACTING/GENERATING；audio_meta.json 存在→跳过 SPEAKING；artifacts/{jobId}/final.mp4 存在→跳过 RENDERING；内容级阶段看 DB 计数器）。
- 信号量：`ResourceSemaphores` 四个 `java.util.concurrent.Semaphore`：glm(2)/tts(1)/render(2)/qa(1)，对应阶段内 acquire/release；TTS 串行由信号量 1 + 句间 interval 双保险。
- 失败分类：可重试（Glm retryable/校验驳回/TTS 截断/QA FAIL）→ 状态机内重试至上限；超限或致命 → FAILED（errorMessage + artifactsDir 保留 workspace）。
- 取消：DELETE 置 CANCELLED 标记位（status 仍记录原值→用 `cancelRequested` 布尔列）；阶段间检查点（每阶段开始前）发现即停，SPEAKING 前可取消，RENDERING 中不打断、完成后不入库 artifacts。
- 回调：终态时 callbackUrl 非空 → POST `{jobId,status,videoUrl?,error?}`，超时 10s，重试 3 次退避，失败仅日志。
- DONE 后：workspace 删除（留 artifacts）。

- [ ] **Step 1: 失败测试**——全链 happy path 状态序列断言（QUEUED→…→DONE 顺序与 stageHistory 记录）；GLM 重试 3 败→FAILED；QA FAIL 2 轮后过→QA→RENDERING 回退再前进；断点续跑（预置 workspace 产物→对应阶段跳过）；取消在 GENERATING 前生效；DONE 后 workspace 清理；回调 fake 收到 POST。→ **Step 2: 跑挂** → **Step 3: 实现** → **Step 4: 绿** → **Step 5: Commit** `feat(server): 编排器（断点续跑/信号量/取消/回调/清理）`

---

### Task 11: golden 冒烟集成测试（slow）

**Files:**
- Create: `integration/GoldenSmokeIT.java`（@Tag("slow")，`@Disabled` 默认——手动本地跑，CI 不依赖）

**内容:** 示例题文本（f(x)=x³+ax²+x 在 R 单调递增求 a）走真 GLM→真校验→真 TTS→真渲染（1080p 全片）→真 QA；断言 DONE、artifacts/final.mp4 存在且时长>60s。运行前打印预计耗时（≈40min）。

- [ ] **Step 1: 实现测试类**（fixture 题 JSON 内嵌）→ **Step 2: `mvn -Pslow test -Dtest=GoldenSmokeIT` 真跑一次**（这是第一条全链路实证；失败修到过；全程记录各阶段耗时入报告）→ **Step 3: Commit** `test(server): golden 全链路冒烟（slow）`

---

### Task 12: Phase 2 验收 E2E（用户晚间验证的核心）

**内容（spec §16 Phase 2 验收）:**
1. 文本题一条全链路无人值守成片（若 Task 11 已是文本题则复用结果，换一道新题更稳——用 sim-001 的 Z 变换题，qa/test.png 的文字版）。
2. 截图题一条：`template/out/qa/test.png`（2023 信号与系统题）走 IMAGE 路径。
3. 批量 3 题并发：上述 2 题 + 1 道新文本题（控制器出题：拉格朗日中值定理证明题）同时入队，断言 3 条 DONE、render 并发 ≤2 生效、互不串扰（各 artifacts 独立）。

- [ ] **Step 1: 服务起本地实例**（`mvn spring-boot:run` 后台）→ **Step 2: curl 走 REST 全流程**（POST/轮询 GET/取 video）→ **Step 3: 三题并行跑完**（预计 60-90min 墙钟）→ **Step 4: 结果与耗时记录** `docs/superpowers/plans/2026-08-29-phase2-e2e-report.md` → **Step 5: Commit** `docs: Phase 2 E2E 验收报告`

---

## Self-Review 记录

- **Spec 覆盖**：§9 三工位→T5/T6；§10 校验链→T7；§11 状态机/队列/断点/取消/批量→T2/T3/T10；§12 TTS→T8；§13 REST→T3；§14 错误处理→T10 + 各客户端重试；§15 测试策略→各任务测试 + T11 slow；§16 Phase 2 验收→T12。§4 信号量→T10；§3 环境→Global Constraints。D1-D10 除 D5(9:16 弃) 全部落入。
- **台账锚点吸收**：sim-001 五条实证（720p 不适用——服务只产 1080p 母版；两段式校验→T7 V1-9 与 T8 写入保证；NO_PROXY→GC7；章节标题不迁→GC11；耗时→偏差 1）；终审 §7 六项（结构规则包→T7；meta 恒等→T7-9；TTS 退避→T8；V4 内容长度→**未入 v1**，理由：golden+prompt 约束已兜，QA V6 审越界兜底，留待真实失败数据再定上限——记录为已知缺口）；章节标题（GC11）；README↔实现对齐→T12 后补 README server 段（T12 报告含）。
- **已知缺口（诚实记录）**：V4 内容长度上限（formula/note 字数）未实现，依赖 V6 兜底；回调无签名校验（v1 无鉴权定位）；H2 无备份策略（文件库定位）。
