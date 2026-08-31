package com.wyf.factory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.stations.ContentMerger;
import com.wyf.factory.stations.CoordinatorStation;
import com.wyf.factory.stations.ExtractResult;
import com.wyf.factory.stations.Material;
import com.wyf.factory.stations.MaterialShardStation;
import com.wyf.factory.stations.MaterialShardStationTest;
import com.wyf.factory.stations.ProblemSliceStation;
import com.wyf.factory.stations.SceneShardStation;
import com.wyf.factory.stations.ShardGenException;
import com.wyf.factory.stations.Skeleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GEN 分片流水线契约（T18）：全链打通（4 分片分组/合并完整性）/驳回路由映射表
 * （含「解析不出→全片」保守回退）/分片 GlmException 走 ShardGenException 通道
 * （编排器既有 retryOrFail 衔接）/轮内缓存复用与缺片补跑。
 * 全 mock 工位 + 真信号量/真合并器，零 API 成本。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenShardPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock CoordinatorStation coordinator;
    @Mock ProblemSliceStation problemSlice;
    @Mock MaterialShardStation materialShard;
    @Mock SceneShardStation sceneShard;

    private GenShardPipeline pipeline;
    private GenShardPipeline.RoundState state;

    private static final ExtractResult EXTRACT = MaterialShardStationTest.EXTRACT;

    /** 小骨架：act2 两场 + act3 三场（step-card 分派 1..3）+ act4 一场 → 场景片 3 片。
     *  （T18.1 计划不变量自洽：counts.steps=3 与 3 张 step-card 恰好覆盖。） */
    private static final Skeleton SMALL = new Skeleton("计算题",
            new Skeleton.Counts(2, 3, 1, 3),
            List.of("L1", "L2", "L3"),
            List.of(new Skeleton.ScenePlan("s01", 2, "problem-card", null),
                    new Skeleton.ScenePlan("s02", 2, "knowledge-card", null),
                    new Skeleton.ScenePlan("s03", 3, "step-card", 1),
                    new Skeleton.ScenePlan("s04", 3, "step-card", 2),
                    new Skeleton.ScenePlan("s05", 3, "step-card", 3),
                    new Skeleton.ScenePlan("s06", 4, "general-list", null)),
            List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）")));

    /** golden 同构骨架：17 场（act2 4 / act3 10 / act4 3）→ 场景片 4 片（act3 拆两片均分）。 */
    private static final Skeleton GOLDEN_LIKE = goldenLikeSkeleton();

    private static final Material MATERIAL = new Material(
            List.of(new Material.Knowledge("k", "f", "p", "t"), new Material.Knowledge("k", "f", "p", "t")),
            List.of(new Material.Step("L1", "s", "d", "n"), new Material.Step("L2", "s", "d", "n"),
                    new Material.Step("L3", "s", "d", "n")),
            List.of(new Material.Pitfall("c", "w")),
            List.of(new Material.MethodItem("st", "tr"), new Material.MethodItem("st", "tr"),
                    new Material.MethodItem("st", "tr")));

    private static Skeleton goldenLikeSkeleton() {
        List<Skeleton.ScenePlan> scenes = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            String id = String.format("s%02d", i);
            if (i == 1) {
                scenes.add(new Skeleton.ScenePlan(id, 2, "problem-card", null));
            } else if (i <= 4) {
                scenes.add(new Skeleton.ScenePlan(id, 2, "knowledge-card", null));
            } else if (i <= 14) {
                // golden 同构（T18.1 计划级 stepRef 分派）：s05:1 s06 popup:1 s07:2 s08:3
                // s09 popup:3 s10:4 s11:5；s12/s13 pitfall、s14 checklist 不带 stepRef
                String component = switch (i) {
                    case 6, 9 -> "derivation-popup";
                    case 12, 13 -> "pitfall-card";
                    case 14 -> "checklist-card";
                    default -> "step-card";
                };
                Integer stepRef = switch (i) {
                    case 5, 6 -> 1;
                    case 7 -> 2;
                    case 8, 9 -> 3;
                    case 10 -> 4;
                    case 11 -> 5;
                    default -> null;
                };
                scenes.add(new Skeleton.ScenePlan(id, 3, component, stepRef));
            } else {
                scenes.add(new Skeleton.ScenePlan(id, 4, "general-list", null));
            }
        }
        return new Skeleton("计算题", new Skeleton.Counts(3, 5, 2, 3),
                List.of("L1", "L2", "L2", "L3", "L3"), scenes,
                List.of(new Skeleton.GlossaryTerm("判别式", "判别式")));
    }

    @BeforeEach
    void setUp() {
        pipeline = new GenShardPipeline(coordinator, problemSlice, materialShard, sceneShard,
                new ContentMerger(), new ResourceSemaphores(new com.wyf.factory.config.AppProperties()));
        state = new GenShardPipeline.RoundState();
        when(coordinator.generate(any(), anyList())).thenReturn(SMALL);
        when(problemSlice.format(any(), anyList())).thenReturn(echoProblem());
        when(materialShard.generate(any(), any(), anyList())).thenReturn(MATERIAL);
        // 场景片按本片 plan 逐场产出（保证合并器轻校验可过）
        when(sceneShard.generate(any(), any(), anyList(), anyList(), anyList()))
                .thenAnswer(inv -> scenesFor(inv.getArgument(2)));
    }

    private static List<ContentJson.Line> echoProblem() {
        return List.of(new ContentJson.Line("L1", List.of(new ContentJson.Seg("text", "已知函数 "))),
                new ContentJson.Line("L2", List.of(new ContentJson.Seg("text", "若 "))),
                new ContentJson.Line("L3", List.of(new ContentJson.Seg("text", "求实数 "))));
    }

    /** plan → 合法 scenes 切片（props 按组件给最小合法值；stepRef 钉死照抄计划，T18.1）。 */
    private static List<ContentJson.Scene> scenesFor(List<Skeleton.ScenePlan> plan) {
        List<ContentJson.Scene> scenes = new ArrayList<>();
        for (Skeleton.ScenePlan p : plan) {
            Map<String, Object> props = switch (p.component()) {
                case "problem-card" -> Map.of();
                case "knowledge-card" -> Map.of("knowledgeRef", 1);
                case "step-card" -> Map.of("stepRef", p.stepRef());
                case "derivation-popup" -> Map.of("stepRef", p.stepRef(), "formula", "f'(x)=3x^{2}+2ax+1");
                case "pitfall-card" -> Map.of("pitfallRef", 1);
                case "checklist-card" -> Map.of("pitfallRefs", List.of(1));
                default -> Map.of("itemRef", 1);
            };
            scenes.add(new ContentJson.Scene(p.id(), p.act(), p.component(), "口播：" + p.id(), props));
        }
        return scenes;
    }

    // ---- 1. 全链打通：分组 + 合并完整性 ----

    @Test
    @DisplayName("全链打通：P0→P1∥P2→4 场景片（act3>6 拆两片均分）→ 合并 content.json 完整且过轻校验")
    void fullRun_goldenLikeGroups_mergedContentComplete() {
        when(coordinator.generate(any(), anyList())).thenReturn(GOLDEN_LIKE);

        ContentJson content = pipeline.generate(EXTRACT, List.of(), state);

        // 分片划分：act2 一片（4 场）/ act3 两片（5+5）/ act4 一片（3 场）
        // （并行执行，捕获顺序不定 → 整组 anyOrder 断言，片内顺序仍严格）
        org.mockito.ArgumentCaptor<List<Skeleton.ScenePlan>> plans =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(sceneShard, times(4)).generate(any(), any(), plans.capture(), anyList(), anyList());
        assertThat(plans.getAllValues()).containsExactlyInAnyOrder(
                plansOf("s01", "s02", "s03", "s04"),
                plansOf("s05", "s06", "s07", "s08", "s09"),
                plansOf("s10", "s11", "s12", "s13", "s14"),
                plansOf("s15", "s16", "s17"));

        // 合并完整性：meta/problem/四段/scenes 全段位到位，scenes 顺序 = 骨架 plan 顺序
        assertThat(content.meta().aspect()).isEqualTo("16:9");
        assertThat(content.meta().problemType()).isEqualTo("计算题");
        assertThat(content.problem().lines()).hasSize(3);
        assertThat(content.knowledge()).hasSize(2);
        assertThat(content.steps()).hasSize(3);
        assertThat(content.pitfalls()).hasSize(1);
        assertThat(content.generalMethod()).hasSize(3);
        assertThat(content.scenes()).hasSize(17);
        assertThat(content.scenes()).extracting(ContentJson.Scene::id)
                .containsExactlyElementsOf(GOLDEN_LIKE.scenes().stream().map(Skeleton.ScenePlan::id).toList());
        // 骨架入缓存（轮内复用的前提）
        assertThat(state.skeleton()).isEqualTo(GOLDEN_LIKE);
    }

    // ---- 2. 驳回路由映射表（纯函数） ----

    @Test
    @DisplayName("路由映射表：素材段→P2；场景 id→对应场景片；scenes[i]→按下标归属；V2→P1")
    void routeTable_mappedShards() {
        assertThat(route("V1/条数: knowledge 条数 5 超出范围 2-4").summary()).isEqualTo("P2");
        assertThat(route("V1/散文LaTeX: steps[2].statement 含 LaTeX 标记").summary()).isEqualTo("P2");
        assertThat(route("V3: steps[1].usesAnchor='L9' 不存在于 problem.lines").summary()).isEqualTo("P2");
        assertThat(route("V1/ttsText: s04 ttsText 为空").summary()).isEqualTo("P3:act3-a");
        assertThat(route("FAIL s03 帧 12 卡片文字出缘").summary()).isEqualTo("P3:act3-a");
        assertThat(route("V1/散文LaTeX: scenes[1].ttsText 含 LaTeX 标记").summary()).isEqualTo("P3:act2");
        assertThat(route("V3: s06 itemRef=9 越界（generalMethod 共 3 条）").summary()).isEqualTo("P3:act4");
        assertThat(route("V2: L1 段 2 不一致：content='x' vs extracted='y'").summary()).isEqualTo("P1");
    }

    @Test
    @DisplayName("路由映射表：「解析不出归属」→ 全部分片重做（保守回退，含 P0）；计划外场景 id 同")
    void routeTable_unparseable_redoAll() {
        for (String error : List.of(
                "V1/meta: aspect='4:3' 应为 16:9",
                "V1/幕覆盖: 缺 act3 场景（至少 1 场）",
                "V4 语义审核驳回（模型未给出具体理由）",
                "QA 审帧判负（审帧器未给出具体 FAIL 行）",
                "FAIL s99 帧外星场景折行")) {
            GenShardPipeline.RoutePlan plan = pipeline.routeErrors(List.of(error), SMALL);
            assertThat(plan.redoAll()).as("「%s」应全片重做", error).isTrue();
            assertThat(plan.summary()).isEqualTo("全部");
            assertThat(plan.errorsFor(GenShardPipeline.P0)).containsExactly(error);   // 全片回退收全量清单
        }
    }

    @Test
    @DisplayName("路由映射表：V1/popup紧跟（计划级相邻性错，带计划内场景 id）→ 全片重做（场景相邻性由骨架计划决定，场景片改不动，T18 评审 M-1）")
    void routeTable_planLevelPopupAdjacency_redoAll() {
        GenShardPipeline.RoutePlan plan = pipeline.routeErrors(
                List.of("V1/popup紧跟: s03 derivation-popup 未紧跟同 stepRef 的 step-card"), SMALL);
        assertThat(plan.redoAll()).as("计划级相邻性错误不得路由给改不动它的场景片").isTrue();
        assertThat(plan.summary()).isEqualTo("全部");
        assertThat(plan.errorsFor(GenShardPipeline.P0)).containsExactly(
                "V1/popup紧跟: s03 derivation-popup 未紧跟同 stepRef 的 step-card");
    }

    @Test
    @DisplayName("路由映射表：结论卡错误 = 场景片 + P2（结论内容是 steps 末条 derivation，P2 字段）")
    void routeTable_conclusionCard_p2PlusSceneShard() {
        GenShardPipeline.RoutePlan plan = pipeline.routeErrors(List.of("FAIL s04 帧 40 结论卡公式等号后折行"), SMALL);
        assertThat(plan.redoAll()).isFalse();
        assertThat(plan.summary()).isEqualTo("P3:act3-a+P2");
        assertThat(plan.errorsFor("P3:act3-a")).containsExactly("FAIL s04 帧 40 结论卡公式等号后折行");
        assertThat(plan.errorsFor("P2")).containsExactly("FAIL s04 帧 40 结论卡公式等号后折行");
        assertThat(plan.errorsFor(GenShardPipeline.P1)).isEmpty();
    }

    @Test
    @DisplayName("路由映射表：多条错误归属并集；分片只收自己的错误子集")
    void routeTable_multipleErrors_unionWithSubsets() {
        GenShardPipeline.RoutePlan plan = pipeline.routeErrors(List.of(
                "V3: steps[0].usesAnchor='L9' 不存在于 problem.lines",
                "V2: L2 段 1 不一致"), SMALL);
        assertThat(plan.summary()).isEqualTo("P2+P1");
        assertThat(plan.errorsFor(GenShardPipeline.P2)).containsExactly("V3: steps[0].usesAnchor='L9' 不存在于 problem.lines");
        assertThat(plan.errorsFor(GenShardPipeline.P1)).containsExactly("V2: L2 段 1 不一致");
        assertThat(plan.errorsFor(GenShardPipeline.P0)).isEmpty();
    }

    @Test
    @DisplayName("路由映射表（T20a 预算规则）：题干宽→P1；列表高/字数超限→P2（报错措辞自带路由令牌）")
    void routeTable_v1BudgetMessages_routeP1P2() {
        assertThat(route("V1/题干宽: problem.lines[2] 估宽 2246px 超出题干面板预算（缩放 0.566 低于下限 0.6，渲染必溢出）")
                .summary()).isEqualTo("P1");
        assertThat(route("V1/列表高: generalMethod[2]（通法列表 itemRef=3）估算高度 1494.0px 超出可用高度 705.6px（缩放 0.472 低于下限 0.55，渲染必溢出）")
                .summary()).isEqualTo("P2");
        assertThat(route("V1/字数超限: pitfalls[0].claim 长度 21 码点超出上限 20")
                .summary()).isEqualTo("P2");
    }

    // ---- 3. 轮内只补错片（缓存复用） ----

    @Test
    @DisplayName("轮内只补错片：steps 错误 → P2 重做；P0/P1 缓存不重调；场景片随素材失效重做（下游消费一致性）")
    void routedP2Error_materialAndScenesRedone_othersCached() {
        pipeline.generate(EXTRACT, List.of(), state);   // 首轮全跑

        ContentJson second = pipeline.generate(EXTRACT,
                List.of("V3: steps[1].usesAnchor='L9' 不存在于 problem.lines"), state);

        assertThat(second.meta().problemType()).isEqualTo("计算题");
        verify(coordinator, times(1)).generate(any(), anyList());      // 骨架缓存复用
        verify(problemSlice, times(1)).format(any(), anyList());       // 题干片缓存复用
        verify(materialShard, times(2)).generate(any(), any(), anyList());   // P2 重做
        verify(sceneShard, times(6)).generate(any(), any(), anyList(), anyList(), anyList());  // 3 片 × 2 轮
    }

    @Test
    @DisplayName("轮内只补错片：单场景片错误 → 只重做该片，P0/P1/P2 与其余场景片全部缓存")
    void routedSceneError_onlyThatSceneShardRedone() {
        pipeline.generate(EXTRACT, List.of(), state);

        pipeline.generate(EXTRACT, List.of("FAIL s04 帧 40 卡片出缘"), state);

        verify(coordinator, times(1)).generate(any(), anyList());
        verify(problemSlice, times(1)).format(any(), anyList());
        verify(materialShard, times(1)).generate(any(), any(), anyList());
        // act2/act4 各 1 次，act3-a 重做 2 次
        verify(sceneShard, times(4)).generate(any(), any(), anyList(), anyList(), anyList());
    }

    @Test
    @DisplayName("解析不出归属 → 全部分片重做：新骨架落地后旧缓存全部作废（P0 也重跑）")
    void unparseableError_allShardsRedone() {
        pipeline.generate(EXTRACT, List.of(), state);

        pipeline.generate(EXTRACT, List.of("V4 语义审核驳回（模型未给出具体理由）"), state);

        verify(coordinator, times(2)).generate(any(), anyList());
        verify(problemSlice, times(2)).format(any(), anyList());
        verify(materialShard, times(2)).generate(any(), any(), anyList());
        verify(sceneShard, times(6)).generate(any(), any(), anyList(), anyList(), anyList());
    }

    // ---- 4. 分片失败 → ShardGenException（既有 retryOrFail 通道的输入形状） ----

    @Test
    @DisplayName("分片 GlmException：包装分片名 + 结构化 problems（落库 source=P2 的依据），成功片已入缓存")
    void shardGlmFailure_wrapsShardNameAndProblems_cachesSuccesses() {
        List<String> problems = List.of("素材 steps 条数 2 与骨架计划 3 不一致（条数以骨架为准）");
        when(materialShard.generate(any(), any(), anyList()))
                .thenThrow(new GlmException(problems, true))
                .thenReturn(MATERIAL);

        assertThatThrownBy(() -> pipeline.generate(EXTRACT, List.of(), state))
                .isInstanceOf(ShardGenException.class)
                .hasMessageContaining("P2 分片失败")
                .extracting("shard", "problems", "retryable")
                .containsExactly("P2", problems, true);
        // 失败轮里成功片已缓存：P1 不必重跑
        verify(problemSlice, times(1)).format(any(), anyList());

        // 补跑轮：P1 用缓存，P2 补跑成功，场景片首次产出 → 合并完整
        ContentJson content = pipeline.generate(EXTRACT, List.of(), state);
        verify(problemSlice, times(1)).format(any(), anyList());       // 仍 1 次（缓存）
        verify(materialShard, times(2)).generate(any(), any(), anyList());
        verify(sceneShard, times(3)).generate(any(), any(), anyList(), anyList(), anyList());   // 首轮未跑
        assertThat(content.scenes()).hasSize(6);
    }

    @Test
    @DisplayName("合并器防御网：分片输出内部不一致 → ShardGenException(MERGE) 携带差异清单")
    void mergeInconsistency_shardMergeException() {
        when(sceneShard.generate(any(), any(), anyList(), anyList(), anyList()))
                .thenAnswer(inv -> {
                    List<ContentJson.Scene> scenes = scenesFor(inv.getArgument(2));
                    scenes.set(0, new ContentJson.Scene(scenes.get(0).id(), scenes.get(0).act(),
                            scenes.get(0).component(), "  ", scenes.get(0).props()));   // ttsText 空白
                    return scenes;
                });

        assertThatThrownBy(() -> pipeline.generate(EXTRACT, List.of(), state))
                .isInstanceOf(ShardGenException.class)
                .hasMessageContaining("MERGE 分片校验失败")
                .hasMessageContaining("scenes[0] 缺必需字段 ttsText")
                .extracting("shard")
                .isEqualTo("MERGE");
    }

    // ---- 5. 观测摘要 ----

    @Test
    @DisplayName("describeRoute：驳回观测摘要——无骨架=首轮全跑，有骨架=分片名拼接")
    void describeRoute_summary() {
        assertThat(pipeline.describeRoute(List.of("V2: x"), state)).isEqualTo("首轮全跑");

        pipeline.generate(EXTRACT, List.of(), state);
        assertThat(pipeline.describeRoute(List.of("V3: steps[0].usesAnchor='L9'"), state)).isEqualTo("P2");
        assertThat(pipeline.describeRoute(List.of("解析不出"), state)).isEqualTo("全部");
    }

    private GenShardPipeline.RoutePlan route(String error) {
        return pipeline.routeErrors(List.of(error), SMALL);
    }

    /** 从 GOLDEN_LIKE 骨架取指定 id 的场景计划（构造期望分片组用，片内顺序 = 骨架顺序）。 */
    private static List<Skeleton.ScenePlan> plansOf(String... ids) {
        return GOLDEN_LIKE.scenes().stream()
                .filter(p -> java.util.Arrays.asList(ids).contains(p.id()))
                .toList();
    }
}
