package com.wyf.factory.validate;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * V1 预算校验（T20a，L3 预算封版）：内容侧模板排版预算镜像——模板放不下的内容，
 * 生成时以 V1 确定性规则秒级驳回，不等 TTS/渲染/QA 烧完才发现。
 *
 * <p><b>模板零接触</b>：预算常量在服务侧镜像，每条常量 javadoc 注明模板出处 file:line；
 * 不改 template/ 任何文件（不触发 Ruling-16 重封版）。已知代价：模板预算将来改动须
 * 手工同步两侧。</p>
 *
 * <p><b>四组规则</b>（错误消息以 {@code V1/} 开头且自带路由令牌：
 * 「题干」→P1；{@code generalMethod[i]}/{@code pitfalls[i]}/{@code steps[i]}→P2，
 * 场景 id（s01）→对应场景片，见 {@code GenShardPipeline.routeErrors}，
 * 驳回走分片级重做而非全片回退）：</p>
 * <ul>
 *   <li>R-宽度① 题干行宽：ProblemPanel 行级自适应，缩放 &lt; 0.6 → 违规</li>
 *   <li>R-宽度② 列表高度：GeneralList / ChecklistCard 高度自适应，缩放 &lt; 0.55 → 违规</li>
 *   <li>R-宽度③ 卡片公式宽度（T29）：steps[].derivation ≤60 码点、
 *       derivation-popup props.formula ≤31 码点，超出 → 违规</li>
 *   <li>R-字符① prompt 字数硬约束的确定性执行（散见于 stations/Prompts.java:123-125 的散文 → 机器检查）</li>
 * </ul>
 *
 * <p><b>估宽算法逐字镜像</b> template/src/engine/fit.ts：isCJK 码点区间
 * [0x2e80,0x9fff] ∪ [0xf900,0xfaff] ∪ [0xff00,0xffef] ×1.0em，其余 ×0.55em，
 * <b>按码点迭代</b>（Java codePoints，非 length()）；math 段宽 = 码点数 × 0.6em × fontSize。
 * 收敛语义（fit.ts fitScale）：需求对 scale 线性时一轮即达，scale = min(1, budget/needed)；
 * scale 低于 floor = 「缩到下限仍放不下」→ 渲染必溢出 → 违规（恰等 floor 不违规）。
 * 未超预算恒 scale=1（golden 零漂移前提）。</p>
 *
 * <p><b>数值口径</b>：题干行宽按 0.1px 整数化（×10 长整）累加，与 fit.ts 数学等价，
 * 杜绝浮点累积使 2120px 边界（scale=0.6）漂移；列表高度公式含 1.4 行高等非整数系数，
 * 与组件同式浮点计算（消息按 %.1f/%.3f 取整显示，远离判定边界）。字符口径 = 码点数。</p>
 */
public final class V1Budget {

    private V1Budget() {
    }

    // ---------------------------------------------------------------- 估宽基元（fit.ts 镜像）

    /** CJK 码点判定（template/src/engine/fit.ts:10-13 isCJK 逐字镜像）。 */
    static boolean isCjk(int cp) {
        return (cp >= 0x2e80 && cp <= 0x9fff) || (cp >= 0xf900 && cp <= 0xfaff)
                || (cp >= 0xff00 && cp <= 0xffef);
    }

    /** 文本估宽 px（fit.ts:16-22 estimateTextWidth 镜像：按码点，CJK 1.0em / 其余 0.55em）。 */
    static double estimateTextWidth(String text, double fontSize) {
        double units = 0;
        for (int cp : text.codePoints().toArray()) {
            units += isCjk(cp) ? 1.0 : 0.55;
        }
        return units * fontSize;
    }

    /** 估行数 ≥1 = ceil(估宽/可用宽)（fit.ts:30-33 estimateLineCount 镜像）。 */
    static int estimateLineCount(String text, double fontSize, double availWidth) {
        if (!(availWidth > 0)) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(estimateTextWidth(text, fontSize) / availWidth));
    }

    private static int codePoints(String text) {
        return (int) text.codePoints().count();
    }

    /** null 容错（缺字段由其它 V1/V3 规则报，此处按空串估宽不误杀）。 */
    private static String nz(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------- R-宽度① 题干行宽

    /**
     * 题干行宽预算 <b>1272px</b> = problemFull.w 1400（template/src/engine/layout.ts:14）
     * − 面板 padding 2×40（template/src/acts/components/ProblemPanel.tsx:51，非 compact）
     * − 面板 border 2×3（ProblemPanel.tsx:12）− 行 padding 2×18（ProblemPanel.tsx:13）
     * − 高亮条 6（ProblemPanel.tsx:14）；template/README.md:214（§10.1）同值。
     * 已知口径差：行间 gap（LINE_GAP=16×(段数−1)，ProblemPanel.tsx:15,26）按任务公式不计入。
     */
    private static final long PROBLEM_LINE_BUDGET = 1272;
    /** 行级缩放下限（ProblemPanel.tsx:16 FIT_FLOOR=0.6）：scale &lt; 0.6 → 违规。 */
    private static final double PROBLEM_FLOOR = 0.6;
    /** text 段基准字号（ProblemPanel.tsx:10 TEXT_FONT=50）。 */
    private static final double PROBLEM_TEXT_FONT = 50;
    /** math 段基准字号（ProblemPanel.tsx:11 MATH_FONT=46）。 */
    private static final double PROBLEM_MATH_FONT = 46;
    /** math 段每码点宽 0.6em×46 = 27.6px = 276×0.1px（fit.ts:25-27 estimateMathWidth 镜像）。 */
    private static final long MATH_TENTHS_PER_CP = 276;

    /** R-宽度①：problem.lines 逐行 W = Σ(text×50) + Σ(math 码点×0.6×46)；W&gt;1272 →
     *  scale=1272/W；scale &lt; 0.6（即 W&gt;2120）→ 违规，消息含「题干」令牌（→P1）。 */
    private static void checkProblemLineWidths(ContentJson content, List<String> errors) {
        List<ContentJson.Line> lines = content.problem() == null || content.problem().lines() == null
                ? List.of() : content.problem().lines();
        for (int i = 0; i < lines.size(); i++) {
            long tenths = lineTenths(lines.get(i));
            if (tenths <= PROBLEM_LINE_BUDGET * 10) {
                continue;   // 未超预算恒 scale=1（golden 零漂移前提）
            }
            double width = tenths / 10.0;
            double scale = PROBLEM_LINE_BUDGET / width;
            if (scale < PROBLEM_FLOOR) {
                // T23：尾缀修复指引（拆行/选项各占一行）只追加在消息末尾，「题干」路由令牌（→P1）保持消息头原状
                errors.add("V1/题干宽: problem.lines[%d] 估宽 %.0fpx 超出题干面板预算（缩放 %.3f 低于下限 0.6，渲染必溢出）；修复指引：把该行拆分为多行（选项各占一行）"
                        .formatted(i, width, scale));
            }
        }
    }

    /** 行宽 0.1px 整数化：text 段 CJK 500 / 其余 275（1.0/0.55 ×50），math 段 276 每码点。 */
    private static long lineTenths(ContentJson.Line line) {
        List<ContentJson.Seg> segments = line.segments() == null ? List.of() : line.segments();
        long tenths = 0;
        for (ContentJson.Seg seg : segments) {
            if (seg == null || seg.value() == null) {
                continue;
            }
            if ("math".equals(seg.type())) {
                tenths += MATH_TENTHS_PER_CP * codePoints(seg.value());
            } else {
                for (int cp : seg.value().codePoints().toArray()) {
                    tenths += isCjk(cp) ? 500 : 275;
                }
            }
        }
        return tenths;
    }

    // ---------------------------------------------------------------- R-宽度② 列表高度

    /** 卡片主区可用宽/高（GeneralList.tsx:55 / ChecklistCard.tsx:33 默认值 =
     *  template/src/engine/layout.ts:16 main.w/main.h，16:9）。 */
    private static final double LIST_AVAIL_W = 1140;
    private static final double LIST_AVAIL_H = 860;
    /** ChecklistCard 高度预算另扣结论卡偏移 220（template/src/acts/Act3Solution.tsx:68,89；
     *  template/README.md:215；brief 裁定 V1 恒扣——保守方向，高度预算 485.6≈486）。 */
    private static final double CHECKLIST_OFFSET = 220;
    /** 列表缩放下限（GeneralList.tsx:13 / ChecklistCard.tsx:12 LIST_FLOOR=0.55）。 */
    private static final double LIST_FLOOR = 0.55;
    /** 估高行高系数（GeneralList.tsx:14 / ChecklistCard.tsx:13 EST_LH=1.4）。 */
    private static final double EST_LH = 1.4;
    /** CardShell 共用：padding/border + chip 区（两组件同值，GeneralList.tsx:10-11 /
     *  ChecklistCard.tsx:10-11）。 */
    private static final double CARD_PAD_X = 48;
    private static final double CARD_PAD_Y = 40;
    private static final double CARD_BORDER = 2;
    private static final double CHIP_FONT = 26;
    private static final double CHIP_PAD_Y = 4;
    private static final double CHIP_MB = 26;

    /** GeneralList 专有（GeneralList.tsx:8-9,12）。 */
    private static final double TITLE_FONT = 34;
    private static final double TITLE_MT = 14;
    private static final double ROW_TEXT_FONT = 32;
    private static final double ROW_LABEL_FONT = 26;
    private static final double ROW_MT_LIST = 22;
    private static final double ITEM_BORDER_X = 8;
    private static final double ITEM_PADL = 20;
    private static final double ROW_GAP_X = 18;
    private static final double LABEL_PAD_X = 14;
    private static final double LABEL_PAD_Y = 2;

    /** ChecklistCard 专有（ChecklistCard.tsx:9）。 */
    private static final double CLAIM_FONT = 38;
    private static final double CHECK_FONT = 40;
    private static final double ROW_MT_CL = 20;

    /** 卡片高度预算（GeneralList.tsx:23 / ChecklistCard.tsx:21 budgetY 同式）：
     *  可用高 − border 2×2 − padding 2×40 − chip（26×1.4 + 2×4）− chip marginBottom 26。 */
    private static double listBudgetY(double availHeight) {
        return availHeight - CARD_BORDER * 2 - CARD_PAD_Y * 2
                - (CHIP_FONT * EST_LH + CHIP_PAD_Y * 2) - CHIP_MB;
    }

    /**
     * GeneralList needed(s)（GeneralList.tsx:22-34 listFitScale neededAt 同式镜像）：
     * 每项 = TITLE_MT×s + step 估行数×34×s×1.4 + ROW_MT×s
     *       + max(26×s×1.4 + 2×2, trick 估行数×32×s×1.4)；
     * textWidth = 1140−2×2−2×48−8−20 = 1012；labelW = 2（「套路」CardShell.tsx:17 ×26）
     * + 2×14；trickWidth = 1012−80−18 = 914。
     */
    private static double generalListNeeded(List<Material.MethodItem> items, double s) {
        double textWidth = LIST_AVAIL_W - CARD_BORDER * 2 - CARD_PAD_X * 2 - ITEM_BORDER_X - ITEM_PADL;
        double labelW = 2 * ROW_LABEL_FONT + LABEL_PAD_X * 2;
        double trickWidth = textWidth - labelW - ROW_GAP_X;
        double h = 0;
        for (Material.MethodItem item : items) {
            double titleH = estimateLineCount(nz(item.step()), TITLE_FONT * s, textWidth) * TITLE_FONT * s * EST_LH;
            double rowH = Math.max(ROW_LABEL_FONT * s * EST_LH + LABEL_PAD_Y * 2,
                    estimateLineCount(nz(item.trick()), ROW_TEXT_FONT * s, trickWidth) * ROW_TEXT_FONT * s * EST_LH);
            h += TITLE_MT * s + titleH + ROW_MT_LIST * s + rowH;
        }
        return h;
    }

    /**
     * ChecklistCard needed(s)（ChecklistCard.tsx:20-29 checklistFitScale neededAt 同式镜像）：
     * 每条 = ROW_MT×s + max(40×s×1.4, claim 估行数×38×s×1.4)；
     * claimWidth = 1140−2×2−2×48−estimateTextWidth("✓",40)−18 = 1000（✓ 非 CJK → 0.55×40=22）。
     */
    private static double checklistNeeded(List<Material.Pitfall> pitfallList, double s) {
        double claimWidth = LIST_AVAIL_W - CARD_BORDER * 2 - CARD_PAD_X * 2
                - estimateTextWidth("✓", CHECK_FONT) - ROW_GAP_X;
        double h = 0;
        for (Material.Pitfall pitfall : pitfallList) {
            double rowH = Math.max(CHECK_FONT * s * EST_LH,
                    estimateLineCount(nz(pitfall.claim()), CLAIM_FONT * s, claimWidth) * CLAIM_FONT * s * EST_LH);
            h += ROW_MT_CL * s + rowH;
        }
        return h;
    }

    /** R-宽度②：general-list（props.itemRef → generalMethod[0..N-1]，Act4Method.tsx:35
     *  slice(0, shown) 同前缀）与 checklist-card（props.pitfallRefs → pitfalls 各条）逐场景
     *  needed(1) → scale = budget/needed；scale &lt; 0.55 → 违规（消息含 P2 令牌）。 */
    private static void checkListHeights(ContentJson content, List<String> errors) {
        List<Material.MethodItem> generalMethod = content.generalMethod() == null
                ? List.of() : content.generalMethod();
        List<Material.Pitfall> pitfalls = content.pitfalls() == null ? List.of() : content.pitfalls();
        List<ContentJson.Scene> scenes = scenes(content);
        for (int i = 0; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            Map<String, Object> sceneProps = scene.props() == null ? Map.of() : scene.props();
            String component = scene.component();
            if ("general-list".equals(component)) {
                Long itemRef = ref(sceneProps.get("itemRef"));
                if (itemRef == null || itemRef < 1 || itemRef > generalMethod.size()) {
                    continue;   // 引用非法归 V3，此处不判
                }
                checkListScale("V1/列表高: generalMethod[%d]（通法列表 itemRef=%d）估算高度 %.1fpx "
                        + "超出可用高度 %.1fpx（缩放 %.3f 低于下限 0.55，渲染必溢出）",
                        itemRef - 1, itemRef, generalListNeeded(generalMethod.subList(0, itemRef.intValue()), 1.0),
                        listBudgetY(LIST_AVAIL_H), errors);
            } else if ("checklist-card".equals(component)) {
                List<Long> refs = pitfallRefs(sceneProps, pitfalls);
                if (refs == null) {
                    continue;   // 引用非法归 V3，此处不判
                }
                checkListScale("V1/列表高: pitfalls[%d]（检查清单 pitfallRefs=%s）估算高度 %.1fpx "
                        + "超出可用高度 %.1fpx（缩放 %.3f 低于下限 0.55，渲染必溢出）",
                        refs.get(0) - 1, refs, checklistNeeded(deref(pitfalls, refs), 1.0),
                        listBudgetY(LIST_AVAIL_H - CHECKLIST_OFFSET), errors);
            }
        }
    }

    /** 通用判定：needed &gt; budget → scale = budget/needed；scale &lt; LIST_FLOOR → 报错。 */
    private static void checkListScale(String format, Object indexArg, Object refsArg,
                                       double needed, double budget, List<String> errors) {
        if (needed <= budget) {
            return;   // 未超预算恒 scale=1（golden 零漂移前提）
        }
        double scale = budget / needed;
        if (scale < LIST_FLOOR) {
            errors.add(format.formatted(indexArg, refsArg, needed, budget, scale));
        }
    }

    /** pitfallRefs → 合法引用下标（1-based）；缺键/元素非法/越界 → null（V3 报，此处不判）。 */
    private static List<Long> pitfallRefs(Map<String, Object> sceneProps, List<Material.Pitfall> pitfalls) {
        if (!(sceneProps.get("pitfallRefs") instanceof List<?> raw) || raw.isEmpty()) {
            return null;
        }
        List<Long> refs = new ArrayList<>();
        for (Object element : raw) {
            Long r = ref(element);
            if (r == null || r < 1 || r > pitfalls.size()) {
                return null;
            }
            refs.add(r);
        }
        return refs;
    }

    private static List<Material.Pitfall> deref(List<Material.Pitfall> pitfalls, List<Long> refs) {
        return refs.stream().map(r -> pitfalls.get(r.intValue() - 1)).toList();
    }

    // ---------------------------------------------------------------- R-字符① 字数硬约束

    /** R-字符①（stations/Prompts.java:123-125 散文约束镜像）：generalMethod step≤24/trick≤40；
     *  pitfalls claim≤20/why≤40；结论卡（steps 末条 derivation）≤40 + 禁长分式
     *  （\frac 分子或分母 &gt;4 码点）。消息含对应令牌（→P2）。 */
    private static void checkCharacterLimits(ContentJson content, List<String> errors) {
        List<Material.MethodItem> generalMethod = content.generalMethod() == null
                ? List.of() : content.generalMethod();
        for (int i = 0; i < generalMethod.size(); i++) {
            Material.MethodItem item = generalMethod.get(i);
            checkLimit("generalMethod[%d].step".formatted(i), nz(item.step()), 24, "", errors);
            checkLimit("generalMethod[%d].trick".formatted(i), nz(item.trick()), 40, "", errors);
        }
        List<Material.Pitfall> pitfalls = content.pitfalls() == null ? List.of() : content.pitfalls();
        for (int i = 0; i < pitfalls.size(); i++) {
            Material.Pitfall pitfall = pitfalls.get(i);
            checkLimit("pitfalls[%d].claim".formatted(i), nz(pitfall.claim()), 20, "", errors);
            checkLimit("pitfalls[%d].why".formatted(i), nz(pitfall.why()), 40, "", errors);
        }
        List<Material.Step> steps = content.steps() == null ? List.of() : content.steps();
        if (!steps.isEmpty()) {
            String path = "steps[%d].derivation".formatted(steps.size() - 1);
            String derivation = nz(steps.get(steps.size() - 1).derivation());
            checkLimit(path, derivation, 40, "（结论卡）", errors);
            checkLongFraction(path, derivation, "（结论卡）", errors);
        }
    }

    /** 字数检查（字符口径 = 码点数）；恰等上限不违规。suffix 为消息尾注（如「（结论卡）」）。 */
    private static void checkLimit(String path, String value, int limit, String suffix, List<String> errors) {
        int length = codePoints(value);
        if (length > limit) {
            errors.add("V1/字数超限: %s 长度 %d 码点超出上限 %d%s".formatted(path, length, limit, suffix));
        }
    }

    /**
     * 长分式禁令：提取每个 {@code \frac{..}{..}} 的分子/分母串，任一 &gt;4 码点即违规。
     * 口径：嵌套 \frac 只查最外层（整块消费后跳过，内部不再单独计）；
     * 括号不闭合等畸形输入不判（渲染侧 KaTeX 兜底，缺字段类错误归其它规则）。
     */
    private static void checkLongFraction(String path, String value, String suffix, List<String> errors) {
        int i = 0;
        while ((i = value.indexOf("\\frac", i)) >= 0) {
            int numOpen = value.indexOf('{', i + 5);
            int numClose = numOpen < 0 ? -1 : matchBrace(value, numOpen);
            int denOpen = numClose < 0 ? -1 : numClose + 1 < value.length()
                    && value.charAt(numClose + 1) == '{' ? numClose + 1 : -1;
            int denClose = denOpen < 0 ? -1 : matchBrace(value, denOpen);
            if (numClose < 0 || denClose < 0) {
                return;   // 畸形（括号不闭合）不判
            }
            String numerator = value.substring(numOpen + 1, numClose);
            String denominator = value.substring(denOpen + 1, denClose);
            checkFractionPart(path, value, numerator, denominator, "分子", numOpen, numClose, suffix, errors);
            checkFractionPart(path, value, numerator, denominator, "分母", denOpen, denClose, suffix, errors);
            i = denClose + 1;   // 整块消费：嵌套只查最外层
        }
    }

    private static void checkFractionPart(String path, String whole, String numerator, String denominator,
                                          String side, int open, int close, String suffix, List<String> errors) {
        int length = codePoints(whole.substring(open + 1, close));
        if (length > 4) {
            errors.add("V1/字数超限: %s 长分式 \\frac{%s}{%s} %s %d 码点超出上限 4%s"
                    .formatted(path, numerator, denominator, side, length, suffix));
        }
    }

    /** 花括号配对：返回与 open 配对的 '}' 下标；不闭合返回 -1。 */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- R-宽度③ 卡片公式宽度

    /**
     * 步骤卡公式预算 <b>60 码点</b>（T29，事故 daf87d4c）。推导链（2026-09-02 模板亲勘复核，
     * 与 brief 数字对平）：
     * <ul>
     *   <li>template/src/engine/layout.ts:16 「16:9」main.w = <b>1140</b>
     *       （Act3Solution.tsx:60 卡片主区 = layout.main）</li>
     *   <li>CardShell.tsx:7 padding 2×48 + border 2×2 → 卡内 <b>1040</b>（同 R-宽度② 扣 border 口径）</li>
     *   <li>StepCard.tsx:14-15 公式盒 padding 2×28 → 盒内 <b>984</b>，Katex fontSize <b>54</b></li>
     *   <li>DerivationPopup.tsx:11 无内层盒，Katex fontSize <b>56</b> → 可用 <b>1040</b></li>
     * </ul>
     *
     * <p><b>镜像零松弛上限</b>（fit.ts:25 estimateMathWidth 口径：TeX 码点 × 0.6em × fontSize，
     * 系数系统性偏高=保守安全）：floor(984/(0.6×54)) = 30 / floor(1040/(0.6×56)) = 30。
     * <b>两处采纳值均偏离镜像值，缘由如下</b>：</p>
     * <ul>
     *   <li>derivation = <b>60</b>：brief 的 30 不可用——封版 golden few-shot 自身
     *       （template/src/data/content.json）derivation 码点数实测 18/45/38/60/26，三条超 30，
     *       30 上限首当其冲驳回 golden（违反「golden 零漂移」前提）。60 = golden 最大码点数
     *       （回归绿物理下限，V1BudgetTest.formulaWidthLimits_pinnedToGoldenFewShot 钉住），
     *       恰兜住事故实证病灶：该 job 超长链 62/67/85 码点全落闸内，实测装得下的 36/57/26 码点
     *       （593/819/693px）全放行。headless 实测镜像高估 1.2-2.9 倍（golden 60 码点链实宽
     *       799px &lt; 984px），粗网放过的边距误差由 QA 审帧兜底。</li>
     *   <li>popup formula = <b>31</b>（brief 命令值；golden popup 18/21 码点容纳无虞）。
     *       严格镜像为 30（扣 border 后 1040），取 31 系 1 码点松弛，远小于镜像系统性高估。</li>
     * </ul>
     *
     * <p><b>折行形态</b>：Katex.tsx:10 maxWidth:100% + overflow:hidden → 超宽公式在 KaTeX
     * span 边界<b>折行</b>（「≤」悬行尾、末项孤行）而非溢出，即事故画面形态——此规则把该病
     * 从昂贵 QA 挪到 V1 秒级驳回。</p>
     *
     * <p><b>行为依赖</b>：模板重封版若改布局/字号须同步重算（L3 预算镜像既有约定）。
     * 侦察结论：step-card 场景 props 只带 stepRef（ContentJson 契约），不携带公式副本——
     * 步骤卡公式宽度唯一数据入口就是 steps[].derivation；derivation-popup 的 formula 与
     * derivation 无逐字符绑定校验（golden s09 即短于 derivation 的关键主式先例），两闸各自独立。</p>
     */
    static final int STEP_DERIVATION_MAX_CODE_POINTS = 60;
    /** 推演弹卡公式预算 31 码点（推导链见 {@link #STEP_DERIVATION_MAX_CODE_POINTS} javadoc）。 */
    static final int POPUP_FORMULA_MAX_CODE_POINTS = 31;

    /** R-宽度③：steps[].derivation 与 derivation-popup props.formula 逐条码点数闸。 */
    private static void checkFormulaWidths(ContentJson content, List<String> errors) {
        List<Material.Step> steps = content.steps() == null ? List.of() : content.steps();
        for (int i = 0; i < steps.size(); i++) {
            int length = codePoints(nz(steps.get(i).derivation()));
            if (length > STEP_DERIVATION_MAX_CODE_POINTS) {
                // steps[i] 令牌 → P2（拆步/拆公式只有素材片做得到）
                errors.add(("V1/公式宽: steps[%d].derivation 第 %d 步公式 %d 码点超出 %d 上限"
                        + "（步骤卡公式盒装不下，KaTeX 将折行）；请拆成多步/多条公式")
                        .formatted(i, i + 1, length, STEP_DERIVATION_MAX_CODE_POINTS));
            }
        }
        List<ContentJson.Scene> sceneList = scenes(content);
        for (int i = 0; i < sceneList.size(); i++) {
            ContentJson.Scene scene = sceneList.get(i);
            if (!"derivation-popup".equals(scene.component())) {
                continue;
            }
            Object formula = scene.props() == null ? null : scene.props().get("formula");
            int length = codePoints(nz(formula == null ? null : String.valueOf(formula)));
            if (length > POPUP_FORMULA_MAX_CODE_POINTS) {
                // 场景 id 令牌 → 对应场景片（formula 可取该步关键主式缩短，golden s09 先例）
                errors.add(("V1/公式宽: scenes[%d] %s derivation-popup formula %d 码点超出 %d 上限"
                        + "（推演卡装不下，KaTeX 将折行）；请缩短为该步推导的关键主式")
                        .formatted(i, scene.id(), length, POPUP_FORMULA_MAX_CODE_POINTS));
            }
        }
    }

    // ---------------------------------------------------------------- 入口与 helpers

    /** 四组规则入口（V1Structural.validate() 尾部、checkProseNoLatex 之后调用）。 */
    public static void check(ContentJson content, List<String> errors) {
        checkProblemLineWidths(content, errors);
        checkListHeights(content, errors);
        checkFormulaWidths(content, errors);
        checkCharacterLimits(content, errors);
    }

    private static List<ContentJson.Scene> scenes(ContentJson content) {
        return content.scenes() == null ? List.of() : content.scenes();
    }

    /** 正整数引用（JSON number → 整数），非法返回 null（与 V1Structural.ref 同义）。 */
    private static Long ref(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d >= 1 && d == Math.rint(d)) {
                return (long) d;
            }
        }
        return null;
    }
}
