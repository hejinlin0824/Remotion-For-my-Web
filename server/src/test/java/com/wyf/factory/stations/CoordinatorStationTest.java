package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * GEN-P0 协调者工位单元测试（T18）：mock GlmClient（零 API 成本）。
 * 骨架校验 = 锚点行存在性 / scene id 唯一 / 条数在 StationChecks 既有 MIN/MAX / 幕结构
 * / 计划级 stepRef 不变量（T18.1：R1 归属、R2 step-card 序列恰为 1..steps、R3 popup 紧跟）；
 * 违反抛 retryable GlmException，差异清单即回传重试清单。
 */
class CoordinatorStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 MaterialShardStationTest 同一示例题的审题产物。 */
    static final ExtractResult EXTRACT = MaterialShardStationTest.EXTRACT;

    /**
     * 合法骨架（counts 3/5/2/3，锚点 L1/L2/L2/L3/L3，12 场；T18.1 计划不变量自洽：
     * step-card 分派 stepRef 1..5 恰一次、popup 紧跟同 stepRef 的 step-card、其余组件不带 stepRef）。
     */
    static final String VALID_JSON = """
            {"problemType":"计算题",
             "counts":{"knowledge":3,"steps":5,"pitfalls":2,"generalMethod":3},
             "anchors":["L1","L2","L2","L3","L3"],
             "scenes":[
              {"id":"s01","act":2,"component":"problem-card"},
              {"id":"s02","act":2,"component":"knowledge-card"},
              {"id":"s03","act":2,"component":"knowledge-card"},
              {"id":"s04","act":3,"component":"step-card","stepRef":1},
              {"id":"s05","act":3,"component":"derivation-popup","stepRef":1},
              {"id":"s06","act":3,"component":"step-card","stepRef":2},
              {"id":"s07","act":3,"component":"step-card","stepRef":3},
              {"id":"s08","act":3,"component":"step-card","stepRef":4},
              {"id":"s09","act":3,"component":"step-card","stepRef":5},
              {"id":"s10","act":3,"component":"pitfall-card"},
              {"id":"s11","act":3,"component":"checklist-card"},
              {"id":"s12","act":4,"component":"general-list"}],
             "glossary":[{"term":"判别式","standard":"判别式（记号 Δ）"}]}
            """;

    private final GlmClient glm = mock(GlmClient.class);
    private final CoordinatorStation station = new CoordinatorStation(glm);

    @Test
    @DisplayName("合法骨架 → Skeleton 绑定（题型/条数/锚点/场景清单/stepRef 分派/术语表逐字段）")
    void validJson_mapsToSkeleton() {
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(VALID_JSON);

        Skeleton skeleton = station.generate(EXTRACT);

        assertThat(skeleton.problemType()).isEqualTo("计算题");
        assertThat(skeleton.counts().knowledge()).isEqualTo(3);
        assertThat(skeleton.counts().steps()).isEqualTo(5);
        assertThat(skeleton.counts().pitfalls()).isEqualTo(2);
        assertThat(skeleton.counts().generalMethod()).isEqualTo(3);
        assertThat(skeleton.anchors()).containsExactly("L1", "L2", "L2", "L3", "L3");
        assertThat(skeleton.scenes()).hasSize(12);
        assertThat(skeleton.scenes().get(0).id()).isEqualTo("s01");
        assertThat(skeleton.scenes().get(0).component()).isEqualTo("problem-card");
        // 计划级 stepRef 分派逐场绑定：step-card/popup 携带，非 step 场景为 null
        assertThat(skeleton.scenes().get(3).stepRef()).isEqualTo(1);
        assertThat(skeleton.scenes().get(4).stepRef()).isEqualTo(1);
        assertThat(skeleton.scenes().get(8).stepRef()).isEqualTo(5);
        assertThat(skeleton.scenes().get(1).stepRef()).isNull();
        assertThat(skeleton.scenes().get(11).stepRef()).isNull();
        assertThat(skeleton.glossary()).hasSize(1);
        assertThat(skeleton.glossary().get(0).term()).isEqualTo("判别式");
    }

    @Test
    @DisplayName("user 载荷 = 题干 JSON，system = Prompts.COORDINATOR；errors 重载追加清单")
    void payload_andRetrySuffix() {
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(VALID_JSON);

        station.generate(EXTRACT, List.of("骨架 anchors[2]='L9' 不存在于题干行 id（锚点行存在性）"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(glm).chat(eq(Prompts.COORDINATOR), payload.capture());
        assertThat(payload.getValue())
                .startsWith(MAPPER.valueToTree(EXTRACT).toString())
                .contains("上一轮校验失败清单（必须全部修正）：")
                .contains("\n- 骨架 anchors[2]='L9' 不存在于题干行 id（锚点行存在性）");
    }

    @Test
    @DisplayName("锚点行存在性：anchors[2]='L9' 不在题干行 → retryable 且消息逐条")
    void anchorNotInLines_retryable() {
        String broken = VALID_JSON.replace("\"L2\",\"L2\"", "\"L2\",\"L9\"");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 anchors[2]='L9' 不存在于题干行 id")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("锚点与 steps 不等长：anchors 4 项 vs steps=5 → retryable")
    void anchorsSizeMismatch_retryable() {
        String broken = VALID_JSON.replace(
                "\"anchors\":[\"L1\",\"L2\",\"L2\",\"L3\",\"L3\"]",
                "\"anchors\":[\"L1\",\"L2\",\"L2\",\"L3\"]");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 anchors 条数 4 与 counts.steps=5 不等长");
    }

    @Test
    @DisplayName("scene id 重复 → retryable 且消息含重复 id")
    void duplicateSceneIds_retryable() {
        String broken = VALID_JSON.replace("{\"id\":\"s09\",\"act\":3,\"component\":\"step-card\",\"stepRef\":5}",
                "{\"id\":\"s06\",\"act\":3,\"component\":\"step-card\",\"stepRef\":2}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 scenes 场景 id 重复：s06");
    }

    @Test
    @DisplayName("条数越界：counts.knowledge=5 超出 2-4 → retryable")
    void countOutOfRange_retryable() {
        String broken = VALID_JSON.replace("\"knowledge\":3", "\"knowledge\":5");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 counts.knowledge 条数 5 超出范围 2-4");
    }

    @Test
    @DisplayName("组件越幕：act2 出现 step-card → retryable；缺 act3/act4 → retryable")
    void actWhitelistAndCoverage_retryable() {
        String broken = VALID_JSON.replace("{\"id\":\"s02\",\"act\":2,\"component\":\"knowledge-card\"}",
                "{\"id\":\"s02\",\"act\":2,\"component\":\"step-card\"}");
        String noAct4 = VALID_JSON.replace("{\"id\":\"s12\",\"act\":4,\"component\":\"general-list\"}",
                "{\"id\":\"s12\",\"act\":2,\"component\":\"general-list\"}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken, noAct4);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("组件 'step-card' 不允许出现在 act2");
        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 scenes 缺 act4 场景（至少 1 场）");
    }

    @Test
    @DisplayName("非 JSON / glossary 缺失 → retryable")
    void badShape_retryable() {
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn("我不是JSON")
                .thenReturn("""
                        {"problemType":"计算题","counts":{"knowledge":2,"steps":3,"pitfalls":1,"generalMethod":3},
                         "anchors":["L1","L2","L3"],"scenes":[{"id":"s01","act":2,"component":"problem-card"},
                         {"id":"s02","act":3,"component":"step-card","stepRef":1},
                         {"id":"s03","act":4,"component":"general-list"}]}""");

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架输出不是 JSON");
        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 glossary 缺失或不是非空数组");
    }

    // ---- T18.1 计划级 stepRef 不变量（R1 归属 / R2 序列 / R3 popup 紧跟） ----

    @Test
    @DisplayName("R1 归属·缺失：step-card 缺 stepRef → retryable 且消息含场景 id")
    void stepRefMissingOnStepCard_retryable() {
        String broken = VALID_JSON.replace("{\"id\":\"s04\",\"act\":3,\"component\":\"step-card\",\"stepRef\":1}",
                "{\"id\":\"s04\",\"act\":3,\"component\":\"step-card\"}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("(s04) step-card 缺 stepRef 或不是整数")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("R1 归属·越界：step-card stepRef=6 超出 1..steps=5 → retryable")
    void stepRefOutOfRangeOnStepCard_retryable() {
        String broken = VALID_JSON.replace("{\"id\":\"s04\",\"act\":3,\"component\":\"step-card\",\"stepRef\":1}",
                "{\"id\":\"s04\",\"act\":3,\"component\":\"step-card\",\"stepRef\":6}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("step-card stepRef=6 超出 1..5");
    }

    @Test
    @DisplayName("R1 归属·越界：derivation-popup stepRef=9 超出 1..steps=5 → retryable（不级联 R3）")
    void stepRefOutOfRangeOnPopup_retryable() {
        String broken = VALID_JSON.replace("\"component\":\"derivation-popup\",\"stepRef\":1",
                "\"component\":\"derivation-popup\",\"stepRef\":9");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("derivation-popup stepRef=9 超出 1..5")
                .hasMessageNotContaining("未紧跟同 stepRef 的 step-card");
    }

    @Test
    @DisplayName("R2 序列：step-card stepRef 序列中途重开〔1,2,3,4,1 形态〕→ retryable 且消息给出实际序列")
    void stepRefSequenceRestarts_retryable() {
        String broken = VALID_JSON.replace("{\"id\":\"s09\",\"act\":3,\"component\":\"step-card\",\"stepRef\":5}",
                "{\"id\":\"s09\",\"act\":3,\"component\":\"step-card\",\"stepRef\":1}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("stepRef 序列 [1, 2, 3, 4, 1] 应为 1..5")
                .hasMessageContaining("缺步/重步/乱序均不允许");
    }

    @Test
    @DisplayName("R3 popup 紧跟：popup stepRef 与前一张 step-card 不同 → retryable（镜像 V1 规则7）")
    void popupNotFollowingSameStepRef_retryable() {
        String broken = VALID_JSON.replace("\"component\":\"derivation-popup\",\"stepRef\":1",
                "\"component\":\"derivation-popup\",\"stepRef\":2");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("(s05) derivation-popup 未紧跟同 stepRef 的 step-card");
    }

    @Test
    @DisplayName("R1 归属·多余：非 step 场景（knowledge-card）带 stepRef → retryable（防歧义）")
    void stepRefOnNonStepComponent_retryable() {
        String broken = VALID_JSON.replace("{\"id\":\"s02\",\"act\":2,\"component\":\"knowledge-card\"}",
                "{\"id\":\"s02\",\"act\":2,\"component\":\"knowledge-card\",\"stepRef\":2}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("(s02) knowledge-card 不得携带 stepRef");
    }

    @Test
    @DisplayName("counts.steps 非法时 R1 范围/R2 序列跳过（problems 已有条目，不级联误报）")
    void invalidStepsCount_skipsRangeAndSequenceChecks() {
        String broken = VALID_JSON.replace("\"counts\":{\"knowledge\":3,\"steps\":5,\"pitfalls\":2,\"generalMethod\":3}",
                "\"counts\":{\"knowledge\":3,\"pitfalls\":2,\"generalMethod\":3}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 counts.steps 缺失或不是整数")
                .hasMessageNotContaining("超出 1..")
                .hasMessageNotContaining("stepRef 序列");
    }
}
