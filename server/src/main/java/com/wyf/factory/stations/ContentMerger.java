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
        if (!problems.isEmpty()) {
            throw new ShardGenException(MERGE_SHARD, problems, null);
        }
        return content;
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
