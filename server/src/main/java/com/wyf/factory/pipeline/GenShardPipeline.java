package com.wyf.factory.pipeline;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.stations.ContentMerger;
import com.wyf.factory.stations.CoordinatorStation;
import com.wyf.factory.stations.ExtractResult;
import com.wyf.factory.stations.Material;
import com.wyf.factory.stations.MaterialShardStation;
import com.wyf.factory.stations.ProblemSliceStation;
import com.wyf.factory.stations.SceneShardStation;
import com.wyf.factory.stations.ShardGenException;
import com.wyf.factory.stations.Skeleton;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GEN 分片流水线（T18，替代原「素材全量 → 剧本全量」两次大调用；T30 素材片一拆二）：
 *
 * <pre>
 * P0 协调者（1 次调用，输出小）：骨架 = 题型 + 条数计划 + 锚点指派 + 场景清单 + 术语表
 * P1 题干片 ∥ P2a 素材·核心片（glm=2 两片并行）
 * P2b 素材·周边片（串行在 P2a 成之后：拿 P2a 成品 steps 作上下文写坑点/通法）
 * P3..Pn 场景片（scenes 按幕分组：act2 一片 / act3 ≤6 场一片否则两片 / act4 一片，并行）
 * 合并器（纯 Java 无 GLM）：骨架 + 各片 → ContentJson → 既有工位级轻校验照跑
 * </pre>
 *
 * <p>每个分片只做一小块（上下文专注、thinking 预算宽裕）；锚点由 P0 单源指派、
 * 分片只领不造（锚点误用类错误单源化）；全部 GLM 调用走 {@code semaphores.withGlm}。
 * T30 拆分动因：事故 14b37e5b P2 素材片（全链最大单请求 5-6min）四连败于 GLM 长请求
 * 不稳窗口（两次 ~623s 客户端超时+两次 ~340s 服务端掐线，安全包络 ≈ ≤4-5min/请求）——
 * 拆成 P2a(~2min)+P2b(1-2min) 两个小请求全部落回包络内；P2a+P2b 产出在 Java 侧装配回
 * 完整 Material，下游零改动。</p>
 *
 * <p><b>分片级驳回路由</b>：V 驳回/QA 判负错误串解析归属（{@link #routeErrors}）——
 * steps[i]（含条数变体）→P2a、knowledge[i]/pitfalls[i]/generalMethod[i]（含条数变体）→P2b、
 * 场景 id（s01）或 scenes[i]→对应场景片、V2/题干→P1、含「结论」（结论卡=steps 末条
 * derivation，P2a 字段）→P2a、QA 排版词（{@link #QA_LAYOUT_WORDS}，事故 001db856）→P3
 * 全部分片（P0/P1/P2a/P2b 不重做）；解析不出归属 → 全部分片重做（保守回退，含 P0）。
 * 轮内只重做路由命中的分片，其余分片沿用 {@link RoundState} 缓存；预算语义不变
 * （content-max=3、genDeadline、reviewErrors 通道照旧）。</p>
 *
 * <p><b>缓存一致性（级联失效）</b>：场景片消费素材片产出（popup formula 照抄 derivation、
 * 卡片正文按素材渲染、ttsText 讲素材内容）→ P2a/P2b 任一重做时场景片全部随 invalidated
 * 重做（素材变了旧分镜即陈稿，口播/画面不符恰是本任务要治的错类）；P2a 重做 ⇒ P2b 必随
 * 重做（坑点/通法是看着旧 steps 写的，新 steps 落地旧周边即陈稿）；P2b 重做 P2a 缓存保留；
 * P1 重做不连带（题干只上画面，场景不朗读题干）。</p>
 *
 * <p><b>断点续跑</b>：{@link RoundState} 纯进程内存（GEN 中间分片产物不落盘），
 * 重启 = GEN 整段重跑（已知限制）。</p>
 *
 * <p><b>日志</b>：每个分片 GLM 调用成败/耗时/分片名必记日志（key 零打印，载荷不入日志）。</p>
 */
@Component
public class GenShardPipeline {

    private static final Logger log = LoggerFactory.getLogger(GenShardPipeline.class);

    /** 分片名（job_review_errors.source 落库值）：P0 骨架 / P1 题干 / P2a 素材核心 / P2b 素材周边 / P3:* 场景片。 */
    public static final String P0 = "P0";
    public static final String P1 = "P1";
    /** 素材·核心片（T30 拆分，steps 独占；历史 P2 落库行保持原样，仅新数据改用 P2a/P2b 标签）。 */
    public static final String P2A = "P2a";
    /** 素材·周边片（T30 拆分，knowledge/pitfalls/generalMethod 三段）。 */
    public static final String P2B = "P2b";
    public static final String P3_ACT2 = "P3:act2";
    public static final String P3_ACT3_A = "P3:act3-a";
    public static final String P3_ACT3_B = "P3:act3-b";
    public static final String P3_ACT4 = "P3:act4";

    /** act3 场景数 ≤ 阈值一片扛，> 阈值两片均分（每片上下文更专注） */
    static final int ACT3_SPLIT_THRESHOLD = 6;
    /** 分片并行线程池规模（并发上限实际由 glm=2 信号量约束；4 片任务不互相等待） */
    static final int SHARD_POOL_SIZE = 4;
    /** 场景 id（s01 形态）识别，用于错误串 → 场景片归属 */
    private static final Pattern SCENE_ID = Pattern.compile("\\bs\\d{2}\\b");
    /** scenes[下标] 形态识别（V1 散文检查等按合并后下标注） */
    private static final Pattern SCENE_INDEX = Pattern.compile("scenes\\[(\\d+)]");

    /**
     * QA 排版类驳回词表（事故 001db856 驱动，词表随事故生长）：QA FAIL 行含任一词即判
     * <b>排版病</b>（溢出/截字类画面缺陷）。排版病与骨架无关——内容已过 V1-V4 与 QA 数学核
     * （事故原文「大字结论行溢出截字」是四性质题，QA 数学核已判内容正确），保守全量重做会把
     * P0 拖回重排，GLM 对条数的正确内容判断反而被旧规格表反复打回（4 连败 knowledge=4 FAILED
     * 16min41s）。故排版类驳回只路由 <b>场景片级重做</b>（P3 全部分片；P0/P1/P2a/P2b 不重做）。
     * 携带场景 id/素材段/题干令牌的排版消息仍按既有映射优先（更细归属不落本词表分支）。
     */
    static final List<String> QA_LAYOUT_WORDS = List.of(
            "溢出", "截字", "截断", "裁字", "断行", "换行错位", "超出");

    private final CoordinatorStation coordinator;
    private final ProblemSliceStation problemSlice;
    private final MaterialShardStation materialShard;
    private final SceneShardStation sceneShard;
    private final ContentMerger merger;
    private final ResourceSemaphores semaphores;

    /** 分片并行线程池：懒创建（测试直接 new 不起 Spring 上下文也能跑），daemon 不阻 JVM 退出。 */
    private ExecutorService shardPool;

    public GenShardPipeline(CoordinatorStation coordinator, ProblemSliceStation problemSlice,
                            MaterialShardStation materialShard, SceneShardStation sceneShard,
                            ContentMerger merger, ResourceSemaphores semaphores) {
        this.coordinator = coordinator;
        this.problemSlice = problemSlice;
        this.materialShard = materialShard;
        this.sceneShard = sceneShard;
        this.merger = merger;
        this.semaphores = semaphores;
    }

    @PreDestroy
    void shutdown() {
        if (shardPool != null) {
            shardPool.shutdownNow();
            shardPool = null;
        }
    }

    // ------------------------------------------------------------------ 轮内分片缓存

    /**
     * 单任务轮内分片产物缓存（JobOrchestrator.Ctx 持有，纯进程内存不落盘）：
     * 驳回重生成轮内只补路由命中的分片，其余沿用缓存。重启即丢（GEN 整段重跑）。
     */
    public static final class RoundState {
        private Skeleton skeleton;
        private List<ContentJson.Line> problem;
        private List<Material.Step> coreSteps;   // P2a 产物（steps）
        private MaterialShardStation.Rest rest;  // P2b 产物（knowledge/pitfalls/generalMethod）
        private final Map<String, List<ContentJson.Scene>> sceneSlices = new LinkedHashMap<>();

        public Skeleton skeleton() {
            return skeleton;
        }

        /** 新骨架落地 → 下游全部作废（旧分片产物与新计划不再绑定）。 */
        void invalidateDownstream() {
            problem = null;
            coreSteps = null;
            rest = null;
            sceneSlices.clear();
        }
    }

    // ------------------------------------------------------------------ 主入口

    /**
     * 跑一轮分片生成：路由 → 按需重做分片（其余用缓存）→ 合并。
     *
     * @param extract 审题产物（P0/P1/P2a/P2b/P3 的公共输入）
     * @param errors  上一轮回传错误清单（首轮为空；非空时按 {@link #routeErrors} 归属）
     * @param state   轮内分片缓存（跨驳回轮复用；重启即丢）
     * @return 合并完成的 content.json（已过既有工位级轻校验）
     * @throws ShardGenException 任一分片失败（含分片名与可落库清单；成功片已入缓存）
     */
    public ContentJson generate(ExtractResult extract, List<String> errors, RoundState state) {
        List<String> errs = errors == null ? List.of() : errors;
        RoutePlan routed = state.skeleton == null ? RoutePlan.redoAll(errs) : routeErrors(errs, state.skeleton);
        log.info("GEN 分片路由：{}（错误 {} 条，骨架 {}）", routed.summary(), errs.size(),
                state.skeleton == null ? "缺（首轮全跑）" : "已缓存");

        if (routed.includes(P0)) {
            state.skeleton = runShard(P0, routed, () -> coordinator.generate(extract, routed.errorsFor(P0)));
            state.invalidateDownstream();   // 新骨架 → 旧分片产物全部作废
        }
        List<SceneShard> shards = groupScenes(state.skeleton.scenes());

        // 下游消费一致性（T18 偏差披露，T30 扩展到两条素材子片）：场景片消费素材片产出（popup
        // formula 照抄 derivation、卡片正文按素材渲染、ttsText 讲素材内容）——素材重做则旧分镜
        // 即陈稿（口播/画面不符恰是本任务待治错类），场景片全部随 P2a/P2b 重做（P0/P1 缓存保留）；
        // P2a 重做 ⇒ P2b 必随重做（坑点/通法看着旧 steps 写的，新 steps 落地旧周边即陈稿）。
        final RoutePlan route;
        if (!routed.redoAll() && (routed.includes(P2A) || routed.includes(P2B))) {
            route = routed.invalidateConsumers(shards);
            log.info("GEN 分片路由：素材片重做 → 下游随 invalidated 重做（{}）", route.summary());
        } else {
            route = routed;
        }

        // P1 ∥ P2a（两片只依赖骨架/题干，glm=2 恰好并行）；缓存缺片（上轮失败未产出）强制补跑
        List<Throwable> failures = new ArrayList<>();
        CompletableFuture<List<ContentJson.Line>> futureP1 = route.includes(P1) || state.problem == null
                ? supplyAsync(() -> runShard(P1, route, () -> problemSlice.format(extract, route.errorsFor(P1))))
                : CompletableFuture.completedFuture(state.problem);
        CompletableFuture<List<Material.Step>> futureP2a = route.includes(P2A) || state.coreSteps == null
                ? supplyAsync(() -> runShard(P2A, route,
                        () -> materialShard.generateCore(extract, state.skeleton, route.errorsFor(P2A))))
                : CompletableFuture.completedFuture(state.coreSteps);
        List<ContentJson.Line> problem = await(futureP1, value -> state.problem = value, failures);
        List<Material.Step> steps = await(futureP2a, value -> state.coreSteps = value, failures);
        if (!failures.isEmpty()) {
            throw asRuntime(failures.get(0));   // 成功片已入缓存，下轮只补失败片
        }

        // P2b 串行在 P2a 之后（拿 steps 成品作上下文写坑点/通法，防上游自造「叙事与解法失配」）
        MaterialShardStation.Rest rest;
        if (route.includes(P2B) || state.rest == null) {
            rest = runShard(P2B, route,
                    () -> materialShard.generateRest(extract, state.skeleton, steps, route.errorsFor(P2B)));
            state.rest = rest;
        } else {
            rest = state.rest;
        }

        // P2a+P2b 产出 Java 侧装配回完整 Material（Material record 零变化，下游零改动）
        Material material = new Material(rest.knowledge(), steps, rest.pitfalls(), rest.generalMethod());

        // P3..Pn 场景片：素材片出稿后才能分镜；按幕分组并行（glm=2 排队两片并行）
        List<CompletableFuture<List<ContentJson.Scene>>> futures = new ArrayList<>();
        for (SceneShard shard : shards) {
            boolean need = route.includes(shard.key()) || state.sceneSlices.get(shard.key()) == null;
            futures.add(need
                    ? supplyAsync(() -> runShard(shard.key(), route, () -> sceneShard.generate(extract,
                            material, shard.scenes(), state.skeleton.glossary(), route.errorsFor(shard.key()))))
                    : CompletableFuture.completedFuture(state.sceneSlices.get(shard.key())));
        }
        List<List<ContentJson.Scene>> slices = new ArrayList<>();
        for (int i = 0; i < shards.size(); i++) {
            SceneShard shard = shards.get(i);
            slices.add(await(futures.get(i), value -> state.sceneSlices.put(shard.key(), value), failures));
        }
        if (!failures.isEmpty()) {
            throw asRuntime(failures.get(0));
        }
        return merger.merge(state.skeleton, problem, material, slices);
    }

    // ------------------------------------------------------------------ 分片驳回路由

    /**
     * 错误清单 → 分片归属（纯函数，编排器驳回时用于观测标注、生成时用于轮内补片）。
     * 映射表（T30 素材段令牌一拆二）：steps[i]（含条数变体）→P2a、knowledge[i]/pitfalls[i]/
     * generalMethod[i]（含条数变体）→P2b；场景 id（s01）或 scenes[i]（合并后下标）→对应场景片；
     * V2/题干→P1；含「结论」→P2a（结论卡=steps 末条 derivation，P2a 字段）；
     * QA 排版词（{@link #QA_LAYOUT_WORDS}，无更细归属时）→P3 全部分片（事故 001db856）；
     * 解析不出 → redoAll（保守回退，含 P0）。
     */
    public RoutePlan routeErrors(List<String> errors, Skeleton skeleton) {
        List<SceneShard> shards = groupScenes(skeleton.scenes());
        Map<String, List<String>> byShard = new LinkedHashMap<>();
        for (String error : errors) {
            List<String> targets = targetsOf(error, skeleton, shards);
            if (targets == null) {
                log.info("GEN 分片路由：错误「{}」解析不出归属 → 全部分片重做（保守回退）", abbreviate(error));
                return RoutePlan.redoAll(errors);
            }
            for (String target : targets) {
                byShard.computeIfAbsent(target, key -> new ArrayList<>()).add(error);
            }
        }
        return new RoutePlan(false, byShard, errors);
    }

    /** 驳回观测标注：本轮回传清单的路由摘要（"P2a+P3:act3-a"；无骨架=首轮返回 "首轮全跑"）。 */
    public String describeRoute(List<String> errors, RoundState state) {
        if (state.skeleton == null) {
            return "首轮全跑";
        }
        return routeErrors(errors == null ? List.of() : errors, state.skeleton).summary();
    }

    /** 单条错误 → 目标分片列表；null = 解析不出归属（全片重做）。 */
    private static List<String> targetsOf(String error, Skeleton skeleton, List<SceneShard> shards) {
        // ⓪ 计划级错误（T18 评审 M-1）：场景相邻性由骨架计划决定，场景片在计划绑定下改不动，
        //   路由给它只会烧轮 → 返回 null 走全片重做（P0 重排计划，invalidateDownstream 作废旧稿）。
        //   仅 popupFormula（缺 formula）属场景片可修的场景内容错，不走此分支。
        if (error.contains("popup紧跟")) {
            return null;
        }
        // ① 场景 id（s01）：null 哨兵 = 计划外场景 id → 全片；非空 = 对应场景片
        List<String> sceneTargets = sceneIdTargets(error, skeleton, shards);
        if (sceneTargets == null) {
            return null;
        }
        if (!sceneTargets.isEmpty()) {
            // 结论卡内容 = steps 末条 derivation（P2a 字段，场景片改不动）→ 追加 P2a
            return error.contains("结论") ? withCore(sceneTargets) : sceneTargets;
        }
        // ② scenes[i]（合并后下标，V1 散文检查口径）→ 对应场景片
        Matcher index = SCENE_INDEX.matcher(error);
        if (index.find()) {
            int i = Integer.parseInt(index.group(1));
            List<Skeleton.ScenePlan> plan = skeleton.scenes();
            if (plan == null || i < 0 || i >= plan.size()) {
                return null;
            }
            SceneShard owner = ownerOf(plan.get(i).id(), shards);
            return owner == null ? null : List.of(owner.key());
        }
        // ③ 素材段（含条数变体）→ P2a（steps 核心）/ P2b（周边三段），T30 拆分
        if (error.contains("steps[")) {
            return List.of(P2A);
        }
        if (error.contains("knowledge[") || error.contains("pitfalls[") || error.contains("generalMethod[")) {
            return List.of(P2B);
        }
        if (error.contains("条数")) {
            if (containsAny(error, "knowledge", "pitfalls", "generalMethod")) {
                return List.of(P2B);
            }
            if (error.contains("steps")) {
                return List.of(P2A);
            }
        }
        // ④ 题干保真（V2）→ P1
        if (error.contains("V2") || error.contains("题干")) {
            return List.of(P1);
        }
        // ⑤ QA 排版类驳回（T24a，事故 001db856）：自由散文 FAIL 行含排版词（{@link #QA_LAYOUT_WORDS}）
        //   → 排版病只重做场景片（P3 全部分片；P0/P1/P2a/P2b 不重做——内容已过 V1-V4+QA 数学核，
        //   骨架与排版无关，全量重做只会烧轮并诱发条数规格连败）。置于所有既有映射之后兜底：
        //   带场景 id/素材段/题干令牌的消息在 ①-④ 已有更细归属，不落本分支，行为零变化。
        //   评审 M-1 栅栏：仅 QA 来源（FAIL 行按构造恒带 FAIL 字样）生效，V4 REJECT 裸散文
        //   即使碰巧含词表字（如「超出」）也不入本分支，回 ⑥ 保守全量。
        //   无场景片可路由（骨架零场景）→ null 走保守全量。
        if (error.contains("FAIL") && QA_LAYOUT_WORDS.stream().anyMatch(error::contains)) {
            return shards.isEmpty() ? null : shards.stream().map(SceneShard::key).toList();
        }
        // ⑥ 解析不出归属（meta/幕覆盖/自由文本等）→ 全部分片重做
        return null;
    }

    /** 场景 id（s01 形态）→ 场景片归属；计划外 id 返回 null 哨兵（全片重做）。 */
    private static List<String> sceneIdTargets(String error, Skeleton skeleton, List<SceneShard> shards) {
        Matcher matcher = SCENE_ID.matcher(error);
        List<String> targets = new ArrayList<>();
        while (matcher.find()) {
            String id = matcher.group();
            SceneShard owner = ownerOf(id, shards);
            if (owner == null) {
                return null;
            }
            if (!targets.contains(owner.key())) {
                targets.add(owner.key());
            }
        }
        return targets;
    }

    private static List<String> withCore(List<String> targets) {
        List<String> merged = new ArrayList<>(targets);
        if (!merged.contains(P2A)) {
            merged.add(P2A);
        }
        return merged;
    }

    private static SceneShard ownerOf(String sceneId, List<SceneShard> shards) {
        for (SceneShard shard : shards) {
            for (Skeleton.ScenePlan plan : shard.scenes()) {
                if (plan.id().equals(sceneId)) {
                    return shard;
                }
            }
        }
        return null;
    }

    private static boolean containsAny(String error, String... tokens) {
        for (String token : tokens) {
            if (error.contains(token)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 场景片分组

    /** 一个场景片：分片键 + 本片场景计划（键跨轮稳定，缓存据此复用）。 */
    public record SceneShard(String key, List<Skeleton.ScenePlan> scenes) {
    }

    /**
     * 场景按幕分组（brief：幕2 一片/幕3 一至两片/幕4+5 一片；scenes 只有 act2/3/4）：
     * act2 一片、act3 ≤{@link #ACT3_SPLIT_THRESHOLD} 一片否则两片均分、act4 一片。
     * 纯函数：同一骨架计划恒得同一分组（缓存键稳定性）。
     */
    public static List<SceneShard> groupScenes(List<Skeleton.ScenePlan> scenes) {
        List<Skeleton.ScenePlan> act2 = new ArrayList<>();
        List<Skeleton.ScenePlan> act3 = new ArrayList<>();
        List<Skeleton.ScenePlan> act4 = new ArrayList<>();
        for (Skeleton.ScenePlan scene : scenes) {
            switch (scene.act()) {
                case 2 -> act2.add(scene);
                case 3 -> act3.add(scene);
                case 4 -> act4.add(scene);
                default -> throw new IllegalArgumentException("骨架场景 act 非法：" + scene.act());
            }
        }
        List<SceneShard> shards = new ArrayList<>();
        if (!act2.isEmpty()) {
            shards.add(new SceneShard(P3_ACT2, List.copyOf(act2)));
        }
        if (act3.size() > ACT3_SPLIT_THRESHOLD) {
            int half = (act3.size() + 1) / 2;
            shards.add(new SceneShard(P3_ACT3_A, List.copyOf(act3.subList(0, half))));
            shards.add(new SceneShard(P3_ACT3_B, List.copyOf(act3.subList(half, act3.size()))));
        } else if (!act3.isEmpty()) {
            shards.add(new SceneShard(P3_ACT3_A, List.copyOf(act3)));
        }
        if (!act4.isEmpty()) {
            shards.add(new SceneShard(P3_ACT4, List.copyOf(act4)));
        }
        return shards;
    }

    // ------------------------------------------------------------------ 路由计划

    /** 一轮的分片重做计划：redoAll=true 时全部分片重做（含 P0），否则只重做 byShard 命中片。 */
    public record RoutePlan(boolean redoAll, Map<String, List<String>> byShard, List<String> allErrors) {

        static RoutePlan redoAll(List<String> errors) {
            return new RoutePlan(true, Map.of(), errors);
        }

        boolean includes(String shard) {
            return redoAll || byShard.containsKey(shard);
        }

        /** 该分片本轮回传的错误子集（redoAll 时收全量清单）。 */
        List<String> errorsFor(String shard) {
            return redoAll ? allErrors : byShard.getOrDefault(shard, List.of());
        }

        /** 观测摘要：重做分片名拼接；全片重做 = "全部"。 */
        public String summary() {
            return redoAll ? "全部" : String.join("+", byShard.keySet());
        }

        /**
         * 下游消费失效（T30 级联）：P2a 重做 ⇒ P2b 必随重做（坑点/通法是看着旧 steps 写的，
         * 新 steps 落地旧周边即陈稿）⇒ 场景片全部随重做（knowledge/pitfalls/generalMethod 三段
         * 与 steps 是场景渲染/口播的直接输入）；P2b 重做 ⇒ 场景片随重做，P2a 缓存保留。
         * 计划不变，仅把下游键并入重做集，错误子集沿用触发素材重做的那批错误（下游与修正后
         * 的素材对齐）。
         */
        RoutePlan invalidateConsumers(List<SceneShard> shards) {
            if (redoAll) {
                return this;
            }
            Map<String, List<String>> merged = new LinkedHashMap<>(byShard);
            if (byShard.containsKey(P2A)) {
                merged.putIfAbsent(P2B, byShard.get(P2A));
            }
            List<String> materialErrors = merged.getOrDefault(P2B, List.of());
            for (SceneShard shard : shards) {
                merged.putIfAbsent(shard.key(), materialErrors);
            }
            return new RoutePlan(false, merged, allErrors);
        }
    }

    // ------------------------------------------------------------------ 执行与日志

    /** 闸内跑分片：成败/耗时/分片名必记日志（key 零打印）；GlmException 包装分片名上抛。 */
    private <T> T runShard(String shard, RoutePlan route, Supplier<T> call) {
        long start = System.currentTimeMillis();
        log.info("GEN 分片 {} GLM 调用开始（回传错误 {} 条）", shard, route.errorsFor(shard).size());
        try {
            T result = semaphores.withGlm(call::get);
            log.info("GEN 分片 {} GLM 调用成功，耗时 {} ms", shard, System.currentTimeMillis() - start);
            return result;
        } catch (GlmException e) {
            log.warn("GEN 分片 {} GLM 调用失败（耗时 {} ms，retryable={}）：{}", shard,
                    System.currentTimeMillis() - start, e.isRetryable(), e.getMessage());
            throw new ShardGenException(shard, e);
        } catch (RuntimeException e) {
            log.warn("GEN 分片 {} 未预期异常（耗时 {} ms）：{}", shard, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    /** 等待一片完成：成功入缓存返回值；失败记入 failures 返回 null（兄弟片照跑照缓存）。 */
    private <T> T await(CompletableFuture<T> future, Consumer<T> cache, List<Throwable> failures) {
        try {
            T value = future.join();
            cache.accept(value);
            return value;
        } catch (CompletionException e) {
            failures.add(e.getCause() != null ? e.getCause() : e);
            return null;
        }
    }

    /** 首个失败上抛：ShardGenException 原样，其余 RuntimeException 原样（真 bug 交编排器兜底）。 */
    private static RuntimeException asRuntime(Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, pool());
    }

    private synchronized ExecutorService pool() {
        if (shardPool == null) {
            AtomicInteger seq = new AtomicInteger();
            shardPool = Executors.newFixedThreadPool(SHARD_POOL_SIZE, task -> {
                Thread thread = new Thread(task, "gen-shard-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return shardPool;
    }

    private static String abbreviate(String s) {
        String stripped = s.strip();
        return stripped.length() <= 60 ? stripped : stripped.substring(0, 60) + "…";
    }
}
