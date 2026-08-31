package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * GEN-P3..Pn 场景片工位（T18 分片生成第 3..n 片）：题干 + 素材 + 本片场景计划 +
 * 术语表 → scenes 切片（只输出 plan 列出的场景）。
 *
 * <p>user 载荷 = {@code {"problemType":...,"problem":{lines},"material":{四段},
 * "plan":[{id,act,component}],"glossary":[...]}}；errors 重载把上一轮失败清单逐条
 * （"- " 前缀）追加在载荷尾部（通道不变）。每片只做一幕的一小段，thinking 预算宽裕、
 * 口播与画面公式近距离对齐（上下文专注收益）。</p>
 *
 * <p>骨架绑定校验：scenes 与 plan 逐场一致（id/顺序/act/component 不得增删改）、
 * 每场必备 ttsText（非空）/props、derivation-popup 的 props.formula 非空 →
 * 违反抛 retryable {@link GlmException}，清单即回传重试清单。</p>
 */
@Component
public class SceneShardStation {

    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public SceneShardStation(GlmClient glm) {
        this.glm = glm;
    }

    /** 题干 + 素材 + 本片计划 → scenes 切片。 */
    public List<ContentJson.Scene> generate(ExtractResult extract, Material material,
                                            List<Skeleton.ScenePlan> plan, List<Skeleton.GlossaryTerm> glossary) {
        return generate(extract, material, plan, glossary, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public List<ContentJson.Scene> generate(ExtractResult extract, Material material,
                                            List<Skeleton.ScenePlan> plan, List<Skeleton.GlossaryTerm> glossary,
                                            List<String> errors) {
        return parse(glm.chat(Prompts.SCENE, payload(extract, material, plan, glossary)
                + StationChecks.retrySuffix(errors)), plan);
    }

    private String payload(ExtractResult extract, Material material,
                           List<Skeleton.ScenePlan> plan, List<Skeleton.GlossaryTerm> glossary) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("problemType", extract.problemType());
            root.set("problem", mapper.valueToTree(extract.lines()));
            root.set("material", mapper.valueToTree(material));
            root.set("plan", mapper.valueToTree(plan));
            root.set("glossary", mapper.valueToTree(glossary));
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new GlmException("题干/素材/骨架序列化失败", false, e);
        }
    }

    private List<ContentJson.Scene> parse(String raw, List<Skeleton.ScenePlan> plan) {
        JsonNode root;
        try {
            root = mapper.readTree(StationChecks.stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("场景片输出不是 JSON：原始响应=" + StationChecks.snippet(raw, RAW_SNIPPET), true, e);
        }
        List<String> problems = new ArrayList<>();
        checkPlanBinding(root.path("scenes"), plan, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);
        }
        try {
            ContentJson.Scene[] scenes = mapper.treeToValue(root.path("scenes"), ContentJson.Scene[].class);
            return List.of(scenes);
        } catch (IOException e) {
            throw new GlmException("场景片输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** 骨架绑定校验：与 plan 逐场一致（id/顺序/act/component）+ 每场必备字段 + popup formula 非空。 */
    private static void checkPlanBinding(JsonNode scenes, List<Skeleton.ScenePlan> plan, List<String> problems) {
        if (!scenes.isArray() || scenes.isEmpty()) {
            problems.add("场景片 scenes 缺失或不是非空数组");
            return;
        }
        if (scenes.size() != plan.size()) {
            problems.add("场景片 scenes 条数 " + scenes.size() + " 与骨架计划 " + plan.size()
                    + " 不一致（只输出 plan 列出的场景，不得增删）");
            return;
        }
        for (int i = 0; i < plan.size(); i++) {
            Skeleton.ScenePlan expected = plan.get(i);
            JsonNode scene = scenes.get(i);
            if (!scene.isObject()) {
                problems.add("场景片 scenes[" + i + "] 不是对象");
                continue;
            }
            String tag = "scenes[" + i + "]";
            JsonNode id = scene.path("id");
            if (!id.isTextual() || !expected.id().equals(id.asText())) {
                problems.add("场景片 " + tag + " id 与骨架计划不一致：输出='" + id.asText("")
                        + "' 计划='" + expected.id() + "'");
            }
            if (scene.path("act").isInt() && scene.path("act").asInt() != expected.act()) {
                problems.add("场景片 " + tag + " act 与骨架计划不一致：输出=" + scene.path("act").asInt()
                        + " 计划=" + expected.act());
            }
            JsonNode component = scene.path("component");
            if (!component.isTextual() || !expected.component().equals(component.asText())) {
                problems.add("场景片 " + tag + " component 与骨架计划不一致：输出='" + component.asText("")
                        + "' 计划='" + expected.component() + "'");
            }
            JsonNode ttsText = scene.path("ttsText");
            if (!ttsText.isTextual() || ttsText.asText().isBlank()) {
                problems.add("场景片 " + tag + " 缺必需字段 ttsText");
            }
            if (!scene.path("props").isObject()) {
                problems.add("场景片 " + tag + " 缺必需字段 props");
            }
            if ("derivation-popup".equals(expected.component())) {
                JsonNode formula = scene.path("props").path("formula");
                if (!formula.isTextual() || formula.asText().isBlank()) {
                    problems.add("场景片 " + tag + " derivation-popup 缺 props.formula（必须照抄该步 derivation）");
                }
            }
        }
    }
}
