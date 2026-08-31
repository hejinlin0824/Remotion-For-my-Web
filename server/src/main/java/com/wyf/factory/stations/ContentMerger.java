package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.content.ContentJson;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 合并器（T18 分片生成，纯 Java 无 GLM）：骨架 + P1 题干片 + P2 素材片 + P3..Pn 场景片
 * → 完整 ContentJson。
 *
 * <p>合并后既有工位级轻校验照跑（原 ScriptStation.parse 的校验规则迁自
 * {@link StationChecks}）：meta/problem/四段条数与字段/scenes 全查——分片级校验已保证
 * 合法，此处是防御网，理论不可达；可达即内部不一致 → 抛
 * {@link ShardGenException}("MERGE") 交编排器既有预算通道。</p>
 *
 * <p>同层防御网（T18.1）：合并后 scenes 序列与 skeleton.scenes() 逐场核对
 * （id/act/component/stepRef 全等、顺序一致、条数一致）——上游已把 stepRef 钉死，
 * 输出≠计划只可能是内部不一致；核对失败同走 MERGE 差异清单通道。</p>
 */
@Component
public class ContentMerger {

    /** 落库/回传清单用的合并器分片名 */
    public static final String MERGE_SHARD = "MERGE";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 骨架 + 各片 → ContentJson。meta.aspect 固定 "16:9"（契约值），problemType 取骨架；
     * scenes 顺序 = sceneSlices 顺序（分片按幕分组有序，片内场景与骨架 plan 同序）。
     */
    public ContentJson merge(Skeleton skeleton, List<ContentJson.Line> problem, Material material,
                             List<List<ContentJson.Scene>> sceneSlices) {
        List<ContentJson.Scene> scenes = new ArrayList<>();
        for (List<ContentJson.Scene> slice : sceneSlices) {
            scenes.addAll(slice);
        }
        ContentJson content = new ContentJson(
                new ContentJson.Meta("16:9", skeleton.problemType()),
                new ContentJson.Problem(List.copyOf(problem)),
                material.knowledge(), material.steps(), material.pitfalls(), material.generalMethod(),
                List.copyOf(scenes));
        List<String> problems = validate(content);
        checkAgainstPlan(scenes, skeleton, problems);
        if (!problems.isEmpty()) {
            throw new ShardGenException(MERGE_SHARD, problems, null);
        }
        return content;
    }

    /**
     * 输出=计划核对（T18.1 防御网，理论不可达）：合并后 scenes 与 skeleton.scenes()
     * 逐场核对 id/act/component/stepRef 全等、顺序一致、条数一致；违反记差异走既有
     * MERGE 通道。id 错位时跳过该场其余字段比较（逐字段只会级联误报）。
     */
    private static void checkAgainstPlan(List<ContentJson.Scene> scenes, Skeleton skeleton,
                                         List<String> problems) {
        List<Skeleton.ScenePlan> plan = skeleton.scenes() == null ? List.of() : skeleton.scenes();
        if (scenes.size() != plan.size()) {
            problems.add("合并 scenes 条数 " + scenes.size() + " 与骨架计划 " + plan.size()
                    + " 不一致（输出必须等于计划）");
            return;
        }
        for (int i = 0; i < plan.size(); i++) {
            Skeleton.ScenePlan expected = plan.get(i);
            ContentJson.Scene actual = scenes.get(i);
            if (!expected.id().equals(actual.id())) {
                problems.add("合并 scenes[" + i + "] id 与骨架计划不一致：输出='" + actual.id()
                        + "' 计划='" + expected.id() + "'");
                continue;
            }
            if (actual.act() != expected.act()) {
                problems.add("合并 scenes[" + i + "](" + expected.id() + ") act 与骨架计划不一致：输出="
                        + actual.act() + " 计划=" + expected.act());
            }
            if (!expected.component().equals(actual.component())) {
                problems.add("合并 scenes[" + i + "](" + expected.id() + ") component 与骨架计划不一致：输出='"
                        + actual.component() + "' 计划='" + expected.component() + "'");
            }
            Object outRef = actual.props() == null ? null : actual.props().get("stepRef");
            Integer planRef = expected.stepRef();
            if (planRef == null) {
                if (outRef != null) {
                    problems.add("合并 scenes[" + i + "](" + expected.id() + ") 计划无 stepRef，输出 props.stepRef="
                            + outRef + " 多余（输出必须等于计划）");
                }
            } else if (!(outRef instanceof Number number) || number.intValue() != planRef) {
                problems.add("合并 scenes[" + i + "](" + expected.id() + ") props.stepRef=" + outRef
                        + " 与骨架计划 stepRef=" + planRef + " 不一致（输出必须等于计划）");
            }
        }
    }

    /** 既有工位级轻校验照跑：规则与原 ScriptStation.parse 完全一致（shapes + 条数 + 字段）。 */
    List<String> validate(ContentJson content) {
        JsonNode root;
        try {
            root = mapper.readTree(content.toJson());
        } catch (IOException e) {
            return List.of("合并结果序列化失败：" + e.getMessage());
        }
        List<String> problems = new ArrayList<>();
        StationChecks.validateMeta(root.path("meta"), problems);
        StationChecks.validateProblem(root.path("problem"), problems);
        StationChecks.count(root, "knowledge", StationChecks.KNOWLEDGE_MIN, StationChecks.KNOWLEDGE_MAX, problems);
        StationChecks.count(root, "steps", StationChecks.STEPS_MIN, StationChecks.STEPS_MAX, problems);
        StationChecks.count(root, "pitfalls", StationChecks.PITFALLS_MIN, StationChecks.PITFALLS_MAX, problems);
        StationChecks.count(root, "generalMethod", StationChecks.GENERAL_METHOD_MIN, StationChecks.GENERAL_METHOD_MAX, problems);
        StationChecks.items(root, "knowledge", StationChecks.KNOWLEDGE_FIELDS, problems);
        StationChecks.items(root, "steps", StationChecks.STEP_FIELDS, problems);
        StationChecks.items(root, "pitfalls", StationChecks.PITFALL_FIELDS, problems);
        StationChecks.items(root, "generalMethod", StationChecks.METHOD_FIELDS, problems);
        StationChecks.validateScenes(root.path("scenes"), problems);
        return problems;
    }
}
