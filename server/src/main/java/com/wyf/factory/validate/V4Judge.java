package com.wyf.factory.validate;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.stations.Material;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * V4 语义审校（LLM judge）：完整 content.json 交 GLM 审五项（步骤推理/计算正确无跳跃 /
 * usesAnchor 条件对应 / ttsText 与画面语义匹配 / pitfalls 真易错 / generalMethod 可迁移）。
 * 输出首行必须 PASS 或 REJECT；REJECT 后续每行一条理由。
 *
 * <p>内容长度预警（终审 §7 第 4 条的轻量替代）：derivation/note/formula >400 字符
 * 只提示不阻断——进 {@link ValidationResult#softErrors()}，不影响 pass 判定。</p>
 */
@Component
public class V4Judge implements Validator {

    /** 渲染上溢预警阈值（字符），> 阈值才预警。 */
    static final int LENGTH_WARN = 400;
    /** 乱格式回复进异常消息的截断长度 */
    private static final int REPLY_SNIPPET = 200;

    static final String SYSTEM_PROMPT = """
            你是考研阅卷专家，负责终审一份考研讲题视频的 content.json（完整 JSON 见用户消息）。
            content.json 结构：meta（画幅/题型）、problem.lines（题干行，id=L1 递增，段 type=text/math）、
            knowledge（知识点）、steps（解题步骤，usesAnchor 指向题干行 id）、pitfalls（易错点）、
            generalMethod（通用方法论）、scenes（镜头序列：act2 知识引入 / act3 解题 / act4 方法迁移，
            component ∈ {problem-card, knowledge-card, step-card, derivation-popup, pitfall-card,
            checklist-card, general-list}，ttsText 为口播文案）。
            请逐项审查以下五点，任何一点不成立即 REJECT：
            1. 解题步骤推理/计算正确、推导无跳跃（steps.derivation 与公式、题干条件一致）；
            2. 每个步骤的 usesAnchor 与该步实际使用的题干条件对应正确；
            3. 每场的 ttsText 讲解与该场 component 画面语义匹配（不讲画面上没有的内容）；
            4. pitfalls 确实是该题的易错点，而非泛泛的通用提醒；
            5. generalMethod 是可迁移到同类题的通用方法论，不绑定本题具体数字。
            输出格式（必须严格遵守）：
            首行必须且只能是 PASS 或 REJECT；
            REJECT 时后续每行写一条理由，以 "-" 开头；PASS 时不得输出任何其他内容。""";

    private final GlmClient glm;

    public V4Judge(GlmClient glm) {
        this.glm = glm;
    }

    @Override
    public ValidationResult validate(ValidationContext ctx) {
        // 长度预警先算：LLM 挂了也该抛 LLM 的异常，预警只在成功路径附带
        List<String> soft = lengthWarnings(ctx.content());
        String reply = glm.chat(SYSTEM_PROMPT, ctx.content().toJson());
        String trimmed = reply.strip();
        int newline = trimmed.indexOf('\n');
        String firstLine = (newline < 0 ? trimmed : trimmed.substring(0, newline)).trim();
        if ("PASS".equals(firstLine)) {
            return new ValidationResult(true, List.of(), soft);
        }
        if ("REJECT".equals(firstLine)) {
            List<String> reasons = newline < 0 ? List.of() : trimmed.substring(newline + 1).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            return new ValidationResult(false, reasons, soft);
        }
        // 模型没守输出格式：按瞬态处理，交编排器重试
        throw new GlmException("V4Judge 输出未守格式（首行必须 PASS/REJECT）：" + snippet(reply), true);
    }

    /** 内容长度预警：derivation/note/formula > 400 字符，提示渲染可能上溢（不阻断）。 */
    private static List<String> lengthWarnings(ContentJson content) {
        List<String> warnings = new ArrayList<>();
        List<ContentJson.Scene> scenes = content.scenes() == null ? List.of() : content.scenes();
        for (ContentJson.Scene scene : scenes) {
            if ("derivation-popup".equals(scene.component()) && scene.props() != null
                    && scene.props().get("formula") instanceof String formula
                    && formula.length() > LENGTH_WARN) {
                warnings.add("V4: %s formula 过长(>400 字符)，渲染可能上溢".formatted(scene.id()));
            }
        }
        List<Material.Step> steps = content.steps() == null ? List.of() : content.steps();
        for (int i = 0; i < steps.size(); i++) {
            Material.Step step = steps.get(i);
            String tag = sceneIdForStep(scenes, i + 1);
            if (step.derivation() != null && step.derivation().length() > LENGTH_WARN) {
                warnings.add("V4: %s derivation 过长(>400 字符)，渲染可能上溢".formatted(tag));
            }
            if (step.note() != null && step.note().length() > LENGTH_WARN) {
                warnings.add("V4: %s note 过长(>400 字符)，渲染可能上溢".formatted(tag));
            }
        }
        return warnings;
    }

    /** 步骤定位：优先用引用该步的首个 step-card/popup 场景 id，找不到回落 steps[i]。 */
    private static String sceneIdForStep(List<ContentJson.Scene> scenes, long stepRef) {
        for (ContentJson.Scene scene : scenes) {
            boolean related = ("step-card".equals(scene.component()) || "derivation-popup".equals(scene.component()))
                    && scene.props() != null
                    && scene.props().get("stepRef") instanceof Number number
                    && number.longValue() == stepRef;
            if (related && scene.id() != null) {
                return scene.id();
            }
        }
        return "steps[%d]".formatted(stepRef - 1);
    }

    private static String snippet(String reply) {
        return reply.length() > REPLY_SNIPPET ? reply.substring(0, REPLY_SNIPPET) : reply;
    }
}
