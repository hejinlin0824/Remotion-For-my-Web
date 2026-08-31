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
 * 骨架校验 = 锚点行存在性 / scene id 唯一 / 条数在 StationChecks 既有 MIN/MAX / 幕结构；
 * 违反抛 retryable GlmException，差异清单即回传重试清单。
 */
class CoordinatorStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 MaterialShardStationTest 同一示例题的审题产物。 */
    static final ExtractResult EXTRACT = MaterialShardStationTest.EXTRACT;

    /** 合法骨架（golden 同构：counts 3/5/2/3，锚点 L1/L2/L2/L3/L3，17 场）。 */
    static final String VALID_JSON = """
            {"problemType":"计算题",
             "counts":{"knowledge":3,"steps":5,"pitfalls":2,"generalMethod":3},
             "anchors":["L1","L2","L2","L3","L3"],
             "scenes":[
              {"id":"s01","act":2,"component":"problem-card"},
              {"id":"s02","act":2,"component":"knowledge-card"},
              {"id":"s03","act":2,"component":"knowledge-card"},
              {"id":"s05","act":3,"component":"step-card"},
              {"id":"s06","act":3,"component":"derivation-popup"},
              {"id":"s11","act":3,"component":"step-card"},
              {"id":"s12","act":3,"component":"pitfall-card"},
              {"id":"s14","act":3,"component":"checklist-card"},
              {"id":"s15","act":4,"component":"general-list"}],
             "glossary":[{"term":"判别式","standard":"判别式（记号 Δ）"}]}
            """;

    private final GlmClient glm = mock(GlmClient.class);
    private final CoordinatorStation station = new CoordinatorStation(glm);

    @Test
    @DisplayName("合法骨架 → Skeleton 绑定（题型/条数/锚点/场景清单/术语表逐字段）")
    void validJson_mapsToSkeleton() {
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(VALID_JSON);

        Skeleton skeleton = station.generate(EXTRACT);

        assertThat(skeleton.problemType()).isEqualTo("计算题");
        assertThat(skeleton.counts().knowledge()).isEqualTo(3);
        assertThat(skeleton.counts().steps()).isEqualTo(5);
        assertThat(skeleton.counts().pitfalls()).isEqualTo(2);
        assertThat(skeleton.counts().generalMethod()).isEqualTo(3);
        assertThat(skeleton.anchors()).containsExactly("L1", "L2", "L2", "L3", "L3");
        assertThat(skeleton.scenes()).hasSize(9);
        assertThat(skeleton.scenes().get(0).id()).isEqualTo("s01");
        assertThat(skeleton.scenes().get(0).component()).isEqualTo("problem-card");
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
        String broken = VALID_JSON.replace("{\"id\":\"s11\",\"act\":3,\"component\":\"step-card\"}",
                "{\"id\":\"s05\",\"act\":3,\"component\":\"step-card\"}");
        when(glm.chat(eq(Prompts.COORDINATOR), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 scenes 场景 id 重复：s05");
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
        String noAct4 = VALID_JSON.replace("{\"id\":\"s15\",\"act\":4,\"component\":\"general-list\"}",
                "{\"id\":\"s15\",\"act\":2,\"component\":\"general-list\"}");
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
                         {"id":"s02","act":3,"component":"step-card"},{"id":"s03","act":4,"component":"general-list"}]}""");

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架输出不是 JSON");
        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("骨架 glossary 缺失或不是非空数组");
    }
}
