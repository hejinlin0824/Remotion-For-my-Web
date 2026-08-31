package com.wyf.factory.stations;

import com.wyf.factory.content.ContentJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 合并器单元测试（T18.1）：骨架 + 各片 → ContentJson。
 * 新增「输出=计划」核对（防御网，理论不可达）：合并后 scenes 与 skeleton.scenes() 逐场
 * 全等（id/act/component/stepRef、顺序、条数），违反抛 ShardGenException(MERGE)；
 * 变体 = 改 id / 改 stepRef / 乱序 / 非 step 场景多带 stepRef。
 */
class ContentMergerTest {

    /** 自洽骨架（T18.1 计划不变量：step-card 分派 1..3 恰一次，其余组件无 stepRef）。 */
    private static final Skeleton SKELETON = new Skeleton("计算题",
            new Skeleton.Counts(2, 3, 1, 3),
            List.of("L1", "L2", "L3"),
            List.of(new Skeleton.ScenePlan("s01", 2, "problem-card", null),
                    new Skeleton.ScenePlan("s02", 2, "knowledge-card", null),
                    new Skeleton.ScenePlan("s03", 3, "step-card", 1),
                    new Skeleton.ScenePlan("s04", 3, "step-card", 2),
                    new Skeleton.ScenePlan("s05", 3, "step-card", 3),
                    new Skeleton.ScenePlan("s06", 4, "general-list", null)),
            List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）")));

    private static final List<ContentJson.Line> PROBLEM = List.of(
            new ContentJson.Line("L1", List.of(new ContentJson.Seg("text", "已知函数 "))),
            new ContentJson.Line("L2", List.of(new ContentJson.Seg("text", "若 "))),
            new ContentJson.Line("L3", List.of(new ContentJson.Seg("text", "求实数 "))));

    private static final Material MATERIAL = new Material(
            List.of(new Material.Knowledge("k1", "f", "p", "t"), new Material.Knowledge("k2", "f", "p", "t")),
            List.of(new Material.Step("L1", "s1", "d1", "n"), new Material.Step("L2", "s2", "d2", "n"),
                    new Material.Step("L3", "s3", "d3", "n")),
            List.of(new Material.Pitfall("c", "w")),
            List.of(new Material.MethodItem("st", "tr"), new Material.MethodItem("st", "tr"),
                    new Material.MethodItem("st", "tr")));

    private final ContentMerger merger = new ContentMerger();

    /** plan → 与计划全等的 scenes 切片（三分片：act2 / act3 / act4）。 */
    private static List<List<ContentJson.Scene>> slicesMatchingPlan() {
        Map<Skeleton.ScenePlan, Map<String, Object>> props = Map.of(
                planOf("s01"), Map.of(),
                planOf("s02"), Map.of("knowledgeRef", 1),
                planOf("s03"), Map.of("stepRef", 1),
                planOf("s04"), Map.of("stepRef", 2),
                planOf("s05"), Map.of("stepRef", 3),
                planOf("s06"), Map.of("itemRef", 1));
        List<ContentJson.Scene> all = new ArrayList<>();
        for (Skeleton.ScenePlan p : SKELETON.scenes()) {
            all.add(new ContentJson.Scene(p.id(), p.act(), p.component(), "口播：" + p.id(), props.get(p)));
        }
        return new ArrayList<>(List.of(
                all.subList(0, 2),
                all.subList(2, 5),
                all.subList(5, 6)));
    }

    private static Skeleton.ScenePlan planOf(String id) {
        return SKELETON.scenes().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("输出=计划：合并成功且 scenes 逐场全等（id/act/component/stepRef、顺序、条数）")
    void merge_scenesEqualToPlan_ok() {
        ContentJson content = merger.merge(SKELETON, PROBLEM, MATERIAL, slicesMatchingPlan());

        assertThat(content.scenes()).extracting(ContentJson.Scene::id)
                .containsExactly("s01", "s02", "s03", "s04", "s05", "s06");
        assertThat(content.scenes().get(2).props()).isEqualTo(Map.of("stepRef", 1));
        assertThat(content.scenes().get(4).props()).isEqualTo(Map.of("stepRef", 3));
        assertThat(content.meta().problemType()).isEqualTo("计算题");
    }

    @Test
    @DisplayName("输出≠计划·改 id：scenes[2] id='s09' ≠ 计划 's03' → ShardGenException(MERGE) 且消息含双值")
    void merge_idDeviated_mergeException() {
        List<List<ContentJson.Scene>> slices = slicesMatchingPlan();
        List<ContentJson.Scene> act3 = new ArrayList<>(slices.get(1));
        ContentJson.Scene s03 = act3.get(0);
        act3.set(0, new ContentJson.Scene("s09", s03.act(), s03.component(), s03.ttsText(), s03.props()));
        slices.set(1, act3);

        assertThatThrownBy(() -> merger.merge(SKELETON, PROBLEM, MATERIAL, slices))
                .isInstanceOf(ShardGenException.class)
                .hasMessageContaining("MERGE 分片校验失败")
                .hasMessageContaining("合并 scenes[2] id 与骨架计划不一致：输出='s09' 计划='s03'")
                .extracting("shard", "retryable")
                .containsExactly("MERGE", true);
    }

    @Test
    @DisplayName("输出≠计划·改 stepRef：props.stepRef=2 ≠ 计划 stepRef=1 → ShardGenException(MERGE)")
    void merge_stepRefDeviated_mergeException() {
        List<List<ContentJson.Scene>> slices = slicesMatchingPlan();
        List<ContentJson.Scene> act3 = new ArrayList<>(slices.get(1));
        ContentJson.Scene s03 = act3.get(0);
        act3.set(0, new ContentJson.Scene(s03.id(), s03.act(), s03.component(), s03.ttsText(),
                Map.of("stepRef", 2)));
        slices.set(1, act3);

        assertThatThrownBy(() -> merger.merge(SKELETON, PROBLEM, MATERIAL, slices))
                .isInstanceOf(ShardGenException.class)
                .hasMessageContaining("合并 scenes[2](s03) props.stepRef=2 与骨架计划 stepRef=1 不一致")
                .extracting("shard")
                .isEqualTo("MERGE");
    }

    @Test
    @DisplayName("输出≠计划·乱序：act3 片内 s03/s04 对调 → 逐场 id 双差异 → ShardGenException(MERGE)")
    void merge_scenesReordered_mergeException() {
        List<List<ContentJson.Scene>> slices = slicesMatchingPlan();
        List<ContentJson.Scene> act3 = new ArrayList<>(slices.get(1));
        ContentJson.Scene first = act3.get(0);
        act3.set(0, act3.get(1));
        act3.set(1, first);
        slices.set(1, act3);

        assertThatThrownBy(() -> merger.merge(SKELETON, PROBLEM, MATERIAL, slices))
                .isInstanceOf(ShardGenException.class)
                .hasMessageContaining("合并 scenes[2] id 与骨架计划不一致：输出='s04' 计划='s03'")
                .hasMessageContaining("合并 scenes[3] id 与骨架计划不一致：输出='s03' 计划='s04'")
                .extracting("shard")
                .isEqualTo("MERGE");
    }

    @Test
    @DisplayName("输出≠计划·多带：非 step 场景（knowledge-card）输出带 props.stepRef → ShardGenException(MERGE)")
    void merge_extraStepRefOnNonStep_mergeException() {
        List<List<ContentJson.Scene>> slices = slicesMatchingPlan();
        List<ContentJson.Scene> act2 = new ArrayList<>(slices.get(0));
        ContentJson.Scene s02 = act2.get(1);
        act2.set(1, new ContentJson.Scene(s02.id(), s02.act(), s02.component(), s02.ttsText(),
                Map.of("knowledgeRef", 1, "stepRef", 1)));
        slices.set(0, act2);

        assertThatThrownBy(() -> merger.merge(SKELETON, PROBLEM, MATERIAL, slices))
                .isInstanceOf(ShardGenException.class)
                .hasMessageContaining("合并 scenes[1](s02) 计划无 stepRef，输出 props.stepRef=1 多余")
                .extracting("shard")
                .isEqualTo("MERGE");
    }
}
