package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GEN-P3..Pn 场景片工位单元测试（T18）：mock GlmClient。
 * 骨架绑定校验 = scenes 与 plan 逐场一致（id/顺序/act/component，不得增删改）+ 必备字段
 * + popup formula 非空；fixture 回放 = 真调剧本响应的 scenes 切片。
 */
class SceneShardStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExtractResult EXTRACT = MaterialShardStationTest.EXTRACT;
    private static final Material MATERIAL = new Material(
            List.of(new Material.Knowledge("可导函数单调递增等价于导数恒非负", "f'(x)\\ge 0", "f(x) 在区间内可导", "写成 f'(x)>0 会漏临界情形")),
            List.of(new Material.Step("L1", "对 f(x) 求导", "f'(x)=3x^{2}+2ax+1", "三次函数导数是二次函数"),
                    new Material.Step("L2", "单调递增翻译成导数恒非负", "f'(x)\\ge 0", "关键转化"),
                    new Material.Step("L3", "解不等式写结论", "a\\in[-\\sqrt{3},\\sqrt{3}]", "端点是闭的")),
            List.of(new Material.Pitfall("把条件写成 f'(x)>0", "漏掉判别式等于零的临界情形")),
            List.of(new Material.MethodItem("识别：可导函数+区间单调", "立刻联想导数恒定号"),
                    new Material.MethodItem("转化：单调⇔导数恒≥0", "含参二次上判别式"),
                    new Material.MethodItem("求解并回验", "取等情形代回验证")));

    /** 本片计划（act3 后半：两 step-card + 一 popup + 一 pitfall + 一 checklist）。 */
    private static final List<Skeleton.ScenePlan> PLAN = List.of(
            new Skeleton.ScenePlan("s03", 3, "step-card"),
            new Skeleton.ScenePlan("s04", 3, "derivation-popup"),
            new Skeleton.ScenePlan("s05", 3, "pitfall-card"),
            new Skeleton.ScenePlan("s06", 3, "checklist-card"));

    private static final List<Skeleton.GlossaryTerm> GLOSSARY =
            List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）"));

    /** 与 plan 完全一致的 scenes 切片（popup formula 照抄该步 derivation）。 */
    private static final String VALID_JSON = """
            {"scenes":[
              {"id":"s03","act":3,"component":"step-card","ttsText":"进入解法。第一步，对 f(x) 求导。导数是一个二次函数。","props":{"stepRef":1}},
              {"id":"s04","act":3,"component":"derivation-popup","ttsText":"求导这里用幂函数法则，三次项降为二次。","props":{"stepRef":1,"formula":"f'(x)=3x^{2}+2ax+1"}},
              {"id":"s05","act":3,"component":"pitfall-card","ttsText":"第一个易错点：把条件写成导数严格大于零。判别式等于零的临界情形就被丢了。","props":{"pitfallRef":1}},
              {"id":"s06","act":3,"component":"checklist-card","ttsText":"做完对一下清单：条件是不是恒成立？端点开闭验过了吗？","props":{"pitfallRefs":[1]}}]}
            """;

    private final GlmClient glm = mock(GlmClient.class);
    private final SceneShardStation station = new SceneShardStation(glm);

    @Test
    @DisplayName("合法切片 → scenes 绑定（id/act/component/props 逐场）")
    void validJson_mapsToScenes() {
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(VALID_JSON);

        List<ContentJson.Scene> scenes = station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY);

        assertThat(scenes).hasSize(4);
        assertThat(scenes.get(0).id()).isEqualTo("s03");
        assertThat(scenes.get(0).props()).isEqualTo(java.util.Map.of("stepRef", 1));
        assertThat(scenes.get(1).component()).isEqualTo("derivation-popup");
        assertThat(scenes.get(1).props().get("formula")).isEqualTo("f'(x)=3x^{2}+2ax+1");
        assertThat(scenes.get(3).props().get("pitfallRefs")).isEqualTo(List.of(1));
    }

    @Test
    @DisplayName("user 载荷 = {problemType, problem, material, plan, glossary}；errors 追加清单")
    void payload_carriesProblemMaterialPlan() throws Exception {
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(VALID_JSON);

        station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY, List.of("场景片 scenes[1] 缺 props.formula（必须照抄该步 derivation）"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(glm).chat(eq(Prompts.SCENE), payload.capture());
        ObjectNode parsed = (ObjectNode) MAPPER.readTree(payload.getValue());
        assertThat(parsed.path("problemType").asText()).isEqualTo("计算题");
        assertThat(parsed.path("problem").isArray()).isTrue();
        assertThat(parsed.path("material").path("steps").isArray()).isTrue();
        assertThat(parsed.path("plan")).hasSize(4);
        assertThat(parsed.path("plan").get(1).path("component").asText()).isEqualTo("derivation-popup");
        assertThat(parsed.path("glossary").get(0).path("standard").asText()).contains("Δ");
        assertThat(payload.getValue())
                .contains("上一轮校验失败清单（必须全部修正）：")
                .contains("\n- 场景片 scenes[1] 缺 props.formula（必须照抄该步 derivation）");
    }

    @Test
    @DisplayName("计划绑定：增删场景（条数不符）→ retryable")
    void sceneCountMismatch_retryable() {
        String dropped = VALID_JSON.replaceFirst(",\\s*\\{\"id\":\"s06\".*\\]\\}", "]}");
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(dropped);

        assertThatThrownBy(() -> station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("场景片 scenes 条数 3 与骨架计划 4 不一致（只输出 plan 列出的场景，不得增删）");
    }

    @Test
    @DisplayName("计划绑定：改 id/act/component 任一 → retryable 且消息含计划值")
    void planFieldDeviated_retryable() {
        String deviated = VALID_JSON
                .replace("\"id\":\"s04\",\"act\":3,\"component\":\"derivation-popup\"",
                        "\"id\":\"s09\",\"act\":2,\"component\":\"step-card\"");
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(deviated);

        assertThatThrownBy(() -> station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("场景片 scenes[1] id 与骨架计划不一致：输出='s09' 计划='s04'")
                .hasMessageContaining("场景片 scenes[1] act 与骨架计划不一致：输出=2 计划=3")
                .hasMessageContaining("场景片 scenes[1] component 与骨架计划不一致：输出='step-card' 计划='derivation-popup'");
    }

    @Test
    @DisplayName("popup 缺 props.formula → retryable（画面公式必须照抄 derivation）")
    void popupMissingFormula_retryable() {
        String missing = VALID_JSON.replace(",\"formula\":\"f'(x)=3x^{2}+2ax+1\"", "");
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(missing);

        assertThatThrownBy(() -> station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("场景片 scenes[1] derivation-popup 缺 props.formula");
    }

    @Test
    @DisplayName("ttsText 缺失/空白 → retryable；非 JSON → retryable")
    void missingTtsText_andNonJson_retryable() {
        String blank = VALID_JSON.replace("\"ttsText\":\"进入解法。第一步，对 f(x) 求导。导数是一个二次函数。\"", "\"ttsText\":\"  \"");
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(blank).thenReturn("not-json");

        assertThatThrownBy(() -> station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("场景片 scenes[0] 缺必需字段 ttsText");
        assertThatThrownBy(() -> station.generate(EXTRACT, MATERIAL, PLAN, GLOSSARY))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("场景片输出不是 JSON");
    }

    @Test
    @DisplayName("fixture 回放：真实剧本响应（GLM 真调捕获）scenes 切片 → 按其场景计划全解析")
    void fixture_replays() throws Exception {
        String raw = fixture("script/script-case.json");
        com.fasterxml.jackson.databind.JsonNode fixtureScenes = MAPPER.readTree(raw).path("scenes");
        List<Skeleton.ScenePlan> plan = new java.util.ArrayList<>();
        for (int i = 0; i < fixtureScenes.size(); i++) {
            com.fasterxml.jackson.databind.JsonNode s = fixtureScenes.get(i);
            plan.add(new Skeleton.ScenePlan(s.path("id").asText(), s.path("act").asInt(), s.path("component").asText()));
        }
        when(glm.chat(eq(Prompts.SCENE), anyString())).thenReturn(raw);

        List<ContentJson.Scene> scenes = station.generate(EXTRACT, MATERIAL, plan, GLOSSARY);

        assertThat(scenes).hasSize(plan.size());
        assertThat(scenes).extracting(ContentJson.Scene::id)
                .containsExactlyElementsOf(plan.stream().map(Skeleton.ScenePlan::id).toList());
        assertThat(scenes).allSatisfy(scene -> {
            assertThat(scene.ttsText()).isNotBlank();
            assertThat(scene.props()).isNotNull();
        });
    }

    private static String fixture(String name) throws Exception {
        try (var in = SceneShardStationTest.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            assertThat(in).as("fixture %s 存在", name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
