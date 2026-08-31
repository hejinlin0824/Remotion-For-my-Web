package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.JdkHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真调 GEN-P3..Pn 场景片的慢测试（T18 分片生成后继任原 ScriptStationSlowIT）：只在有真实 key 时
 * 手动 `mvn -Pslow test -Dtest=SceneShardStationSlowIT` 跑（Global Constraint 9，默认 mvn test 排除）。
 *
 * <p>输入 = 同一示例题的审题 fixture + P2 真调素材（同一链路）+ 本片场景计划
 * （act3 前段：step-card/derivation-popup 各一，golden 同构）。断言：scenes 与 plan 逐场一致
 * （id/act/component）、popup formula 与该步 derivation 逐字符一致、act 非降。</p>
 *
 * <p>端点说明（沿 ExtractStationSlowIT）：env `GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4`
 * 覆盖生产默认 v4；key 只进进程环境，绝不打日志。</p>
 *
 * <p>fixture 捕获：`-Dfixture.capture=true` 把 P2+P3 真调链的 scenes 切片写入
 * fixtures/script/script-case.json 同形状文件（scenes 数组；单元回放用，默认不写）。</p>
 */
@Tag("slow")
class SceneShardStationSlowIT {

    private static final Path EXTRACT_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "extract", "text-case.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("真调 P3 场景片 → scenes 与 plan 逐场一致、popup formula 照抄 derivation、act 非降")
    void generate_realCall_matchesPlanAndCopiesFormula() throws Exception {
        ExtractResult extract = extractFixture();
        AppProperties props = props();
        Material material = new MaterialShardStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props))
                .generate(extract, MaterialShardStationSlowITSupport.materialSkeleton());

        List<Skeleton.ScenePlan> plan = List.of(
                new Skeleton.ScenePlan("s05", 3, "step-card"),
                new Skeleton.ScenePlan("s06", 3, "derivation-popup"),
                new Skeleton.ScenePlan("s07", 3, "step-card"));
        List<ContentJson.Scene> scenes = new SceneShardStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props))
                .generate(extract, material, plan, MaterialShardStationSlowITSupport.glossary());

        assertThat(scenes).extracting(ContentJson.Scene::id).containsExactly("s05", "s06", "s07");
        assertThat(scenes).extracting(ContentJson.Scene::act).containsOnly(3);
        assertThat(scenes).extracting(ContentJson.Scene::component)
                .containsExactly("step-card", "derivation-popup", "step-card");
        // popup formula 必须逐字符照抄该步 derivation（口播/画面一致的机制锚点）
        ContentJson.Scene popup = scenes.get(1);
        String derivation = material.steps().get(0).derivation();
        assertThat((String) popup.props().get("formula")).isEqualTo(derivation);
        assertThat(scenes).allSatisfy(scene -> assertThat(scene.ttsText()).isNotBlank());
    }

    @Test
    @EnabledIfSystemProperty(named = "fixture.capture", matches = "true")
    @DisplayName("捕获 fixture（-Dfixture.capture=true）：P2+P3 真调链 scenes 切片写入 fixtures/script/")
    void captureFixture() throws Exception {
        ExtractResult extract = extractFixture();
        AppProperties props = props();
        Material material = new MaterialShardStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props))
                .generate(extract, MaterialShardStationSlowITSupport.materialSkeleton());
        List<Skeleton.ScenePlan> plan = List.of(
                new Skeleton.ScenePlan("s05", 3, "step-card"),
                new Skeleton.ScenePlan("s06", 3, "derivation-popup"),
                new Skeleton.ScenePlan("s07", 3, "step-card"));
        List<ContentJson.Scene> scenes = new SceneShardStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props))
                .generate(extract, material, plan, MaterialShardStationSlowITSupport.glossary());

        StringBuilder sb = new StringBuilder("{\"scenes\":[\n");
        for (int i = 0; i < scenes.size(); i++) {
            sb.append(mapper.writeValueAsString(scenes.get(i)));
            if (i < scenes.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]}");
        Path scriptFixture = Path.of("src", "test", "resources", "fixtures", "script", "script-case.json");
        Files.createDirectories(scriptFixture.getParent());
        Files.writeString(scriptFixture, sb.toString(), StandardCharsets.UTF_8);
        assertThat(scenes).isNotEmpty();
    }

    private ExtractResult extractFixture() throws Exception {
        assertThat(Files.isRegularFile(EXTRACT_FIXTURE)).as("审题 fixture 存在").isTrue();
        return mapper.readValue(Files.readString(EXTRACT_FIXTURE, StandardCharsets.UTF_8), ExtractResult.class);
    }

    /** 生产默认配置；env GLM_BASE_URL 可覆盖端点（见类注释）。 */
    private static AppProperties props() {
        AppProperties props = new AppProperties();
        String override = System.getenv("GLM_BASE_URL");
        if (override != null && !override.isBlank()) {
            props.getGlm().setBaseUrl(override.strip());
        }
        return props;
    }
}

/** 慢 IT 共用骨架/术语表（golden 同构 counts 3/5/2/3，锚点 L1/L2/L2/L3/L3）。 */
final class MaterialShardStationSlowITSupport {

    private MaterialShardStationSlowITSupport() {
    }

    static Skeleton materialSkeleton() {
        List<Skeleton.ScenePlan> scenes = new ArrayList<>();
        scenes.add(new Skeleton.ScenePlan("s01", 2, "problem-card"));
        scenes.add(new Skeleton.ScenePlan("s05", 3, "step-card"));
        scenes.add(new Skeleton.ScenePlan("s15", 4, "general-list"));
        return new Skeleton("计算题", new Skeleton.Counts(3, 5, 2, 3),
                List.of("L1", "L2", "L2", "L3", "L3"), scenes,
                glossary());
    }

    static List<Skeleton.GlossaryTerm> glossary() {
        return List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）"));
    }
}
