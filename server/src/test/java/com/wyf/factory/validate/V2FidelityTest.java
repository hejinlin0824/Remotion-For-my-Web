package com.wyf.factory.validate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.ExtractResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 题干保真测试：全角/空白/标点容差应通过，真差异（数字/大小写/文案）必须抓出。
 * golden（template/src/data/content.json）直接作 fixture，extracted 由 golden 变形。
 */
class V2FidelityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenFile;

    private final V2Fidelity validator = new V2Fidelity();

    @BeforeAll
    static void locateGolden() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("template/src/data/content.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("仓库根（含 template/src/data/content.json）").isNotNull();
        goldenFile = dir.resolve("template/src/data/content.json");
    }

    @Test
    @DisplayName("golden 全绿（extracted = golden.problem）")
    void goldenPasses() throws Exception {
        ContentJson golden = loadGolden();

        var result = validator.validate(new ValidationContext(golden, extractOf(golden)));

        assertThat(result.pass()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("normalize：全角→半角 + 去空白（含全角/不断行空格），连续标点不折叠")
    void normalizeTolerance() {
        assertThat(V2Fidelity.normalize("已知函数 f(x)　，求 a 。"))
                .isEqualTo(V2Fidelity.normalize("已知函数f(x),求a."));
        assertThat(V2Fidelity.normalize("（ＡＢ１２）")).isEqualTo("(AB12)");
        assertThat(V2Fidelity.normalize("！！？?")).isEqualTo("!!??");
        assertThat(V2Fidelity.normalize("a b c")).isEqualTo("abc");
        assertThat(V2Fidelity.normalize("“引号”‘单’")).isEqualTo("\"引号\"'单'");
    }

    @Test
    @DisplayName("容差通过：text 多余空白 + math 段内空格差异不影响判定")
    void whitespaceVariantsPass() throws Exception {
        ContentJson golden = loadGolden();
        // extracted：L1 text 换全角空格、L1/L2 math 加空格——归一化/去空白后应与 golden 一致
        ExtractResult extracted = withSegment(0, 0, "已知函数　");
        extracted = withSegment(extracted, 0, 1, "f(x) = x^{3} + ax^{2} + x");
        extracted = withSegment(extracted, 1, 3, "\\mathbb{R} ");

        var result = validator.validate(new ValidationContext(golden, extracted));

        assertThat(result.errors()).as("容差差异不应报错").isEmpty();
        assertThat(result.pass()).isTrue();
    }

    @Test
    @DisplayName("真差异抓出：text 段文案被改 → V2 段不一致")
    void textDifferenceCaught() throws Exception {
        ContentJson golden = loadGolden();
        ExtractResult extracted = withSegment(0, 0, "已知函数g(x) ");

        var result = validator.validate(new ValidationContext(golden, extracted));

        assertThat(result.pass()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.startsWith("V2: L1 段 1 不一致：") && e.contains("已知函数"));
    }

    @Test
    @DisplayName("真差异抓出：math 段数字被改（空格容差不放行真差异）→ V2")
    void mathDifferenceCaught() throws Exception {
        ContentJson golden = loadGolden();
        ExtractResult extracted = withSegment(0, 1, "f(x)=x^{4}+ax^{2}+x");

        var result = validator.validate(new ValidationContext(golden, extracted));

        assertThat(result.pass()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.startsWith("V2: L1 段 2 不一致：") && e.contains("x^{4}"));
    }

    @Test
    @DisplayName("math 大小写敏感：\\mathbb{R} → \\mathbb{r} 抓出")
    void mathCaseSensitive() throws Exception {
        ContentJson golden = loadGolden();
        ExtractResult extracted = withSegment(1, 3, "\\mathbb{r}");

        var result = validator.validate(new ValidationContext(golden, extracted));

        assertThat(result.pass()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.startsWith("V2: L2 段 4 不一致："));
    }

    @Test
    @DisplayName("行 id 不一致 → V2 id 错误")
    void idMismatchCaught() throws Exception {
        ContentJson golden = loadGolden();
        ExtractResult extracted = withLineId(0, "L9");

        var errors = errorsOf(golden, extracted);

        assertThat(errors).anyMatch(e -> e.startsWith("V2: 第 1 行 id 不一致：") && e.contains("L9"));
    }

    @Test
    @DisplayName("行数不一致 → V2 行数错误")
    void lineCountMismatchCaught() throws Exception {
        ContentJson golden = loadGolden();
        ExtractResult extracted = withoutLine(2);

        var errors = errorsOf(golden, extracted);

        assertThat(errors).anyMatch(e -> e.startsWith("V2: 行数不一致：") && e.contains("content=3") && e.contains("extracted=2"));
    }

    @Test
    @DisplayName("段数不一致 → V2 段数错误")
    void segmentCountMismatchCaught() throws Exception {
        ContentJson golden = loadGolden();
        ExtractResult extracted = withoutSegment(1, 4);

        var errors = errorsOf(golden, extracted);

        assertThat(errors).anyMatch(e -> e.startsWith("V2: L2 段数不一致："));
    }

    @Test
    @DisplayName("差异值截断 80 字符")
    void longDifferenceTruncated() throws Exception {
        ContentJson golden = loadGolden();
        String longValue = "字".repeat(120);
        ExtractResult extracted = withSegment(0, 0, longValue);

        var errors = errorsOf(golden, extracted);

        assertThat(errors).anyMatch(e -> e.contains("字".repeat(80)) && !e.contains("字".repeat(120)));
    }

    // ---- helpers ----

    private ContentJson loadGolden() throws Exception {
        return MAPPER.readValue(goldenFile.toFile(), ContentJson.class);
    }

    private List<String> errorsOf(ContentJson content, ExtractResult extracted) {
        var result = validator.validate(new ValidationContext(content, extracted));
        assertThat(result.pass()).as("变形应被打中，实际错误：%s", result.errors()).isFalse();
        return result.errors();
    }

    private static ExtractResult extractOf(ContentJson content) {
        return new ExtractResult(content.meta().problemType(), content.problem().lines().stream()
                .map(line -> new ExtractResult.Line(line.id(), line.segments().stream()
                        .map(seg -> new ExtractResult.Seg(seg.type(), seg.value())).toList()))
                .toList());
    }

    /** extracted：改第 lineIdx 行第 segIdx 段的值（golden 之上的不可变变形）。 */
    private ExtractResult withSegment(int lineIdx, int segIdx, String value) throws Exception {
        return withSegment(extractOf(loadGolden()), lineIdx, segIdx, value);
    }

    private static ExtractResult withSegment(ExtractResult source, int lineIdx, int segIdx, String value) {
        List<ExtractResult.Line> lines = new ArrayList<>(source.lines());
        ExtractResult.Line line = lines.get(lineIdx);
        List<ExtractResult.Seg> segments = new ArrayList<>(line.segments());
        ExtractResult.Seg old = segments.get(segIdx);
        segments.set(segIdx, new ExtractResult.Seg(old.type(), value));
        lines.set(lineIdx, new ExtractResult.Line(line.id(), segments));
        return new ExtractResult(source.problemType(), lines);
    }

    private ExtractResult withLineId(int lineIdx, String id) throws Exception {
        ExtractResult source = extractOf(loadGolden());
        List<ExtractResult.Line> lines = new ArrayList<>(source.lines());
        ExtractResult.Line line = lines.get(lineIdx);
        lines.set(lineIdx, new ExtractResult.Line(id, line.segments()));
        return new ExtractResult(source.problemType(), lines);
    }

    private ExtractResult withoutLine(int lineIdx) throws Exception {
        ExtractResult source = extractOf(loadGolden());
        List<ExtractResult.Line> lines = new ArrayList<>(source.lines());
        lines.remove(lineIdx);
        return new ExtractResult(source.problemType(), lines);
    }

    private ExtractResult withoutSegment(int lineIdx, int segIdx) throws Exception {
        ExtractResult source = extractOf(loadGolden());
        List<ExtractResult.Line> lines = new ArrayList<>(source.lines());
        ExtractResult.Line line = lines.get(lineIdx);
        List<ExtractResult.Seg> segments = new ArrayList<>(line.segments());
        segments.remove(segIdx);
        lines.set(lineIdx, new ExtractResult.Line(line.id(), segments));
        return new ExtractResult(source.problemType(), lines);
    }
}
