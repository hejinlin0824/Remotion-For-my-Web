package com.wyf.factory.validate;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.ExtractResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * V2 题干保真：contentJson.problem 与 EXTRACTING 产物 ExtractResult 归一化比对
 * ——行数相等、逐行 id 相等、逐段比对：text 段 normalize 后相等
 * （全角→半角 + 去全部空白，连续标点不折叠）；math 段只去全部空白后相等
 * （LaTeX 大小写敏感、标点不映射）。每处差异一条 error，差异值截断 80 字符。
 */
@Component
public class V2Fidelity implements Validator {

    /** 差异值进错误消息的截断长度 */
    private static final int SNIPPET = 80;

    @Override
    public ValidationResult validate(ValidationContext ctx) {
        List<ContentJson.Line> contentLines = lines(ctx.content());
        List<ExtractResult.Line> extractedLines = ctx.extracted() == null || ctx.extracted().lines() == null
                ? List.of() : ctx.extracted().lines();
        List<String> errors = new ArrayList<>();

        if (contentLines.size() != extractedLines.size()) {
            errors.add("V2: 行数不一致：content=%d 行 vs extracted=%d 行"
                    .formatted(contentLines.size(), extractedLines.size()));
            return ValidationResult.fail(errors);
        }
        for (int i = 0; i < contentLines.size(); i++) {
            ContentJson.Line contentLine = contentLines.get(i);
            ExtractResult.Line extractedLine = extractedLines.get(i);
            if (!Objects.equals(contentLine.id(), extractedLine.id())) {
                errors.add("V2: 第 %d 行 id 不一致：content='%s' vs extracted='%s'"
                        .formatted(i + 1, contentLine.id(), extractedLine.id()));
                continue;
            }
            compareSegments(contentLine, extractedLine, errors);
        }
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private static void compareSegments(ContentJson.Line contentLine, ExtractResult.Line extractedLine,
                                        List<String> errors) {
        List<ContentJson.Seg> contentSegs = contentLine.segments() == null ? List.of() : contentLine.segments();
        List<ExtractResult.Seg> extractedSegs = extractedLine.segments() == null ? List.of() : extractedLine.segments();
        if (contentSegs.size() != extractedSegs.size()) {
            errors.add("V2: %s 段数不一致：content=%d 段 vs extracted=%d 段"
                    .formatted(contentLine.id(), contentSegs.size(), extractedSegs.size()));
            return;
        }
        for (int j = 0; j < contentSegs.size(); j++) {
            ContentJson.Seg contentSeg = contentSegs.get(j);
            ExtractResult.Seg extractedSeg = extractedSegs.get(j);
            String type = contentSeg.type() == null ? "text" : contentSeg.type();
            String extractedType = extractedSeg.type() == null ? "text" : extractedSeg.type();
            if (!type.equals(extractedType)) {
                errors.add("V2: %s 段 %d 类型不一致：content=%s vs extracted=%s"
                        .formatted(contentLine.id(), j + 1, type, extractedType));
                continue;
            }
            boolean same = "math".equals(type)
                    ? stripWhitespace(contentSeg.value()).equals(stripWhitespace(extractedSeg.value()))
                    : normalize(contentSeg.value()).equals(normalize(extractedSeg.value()));
            if (!same) {
                errors.add("V2: %s 段 %d 不一致：content='%s' vs extracted='%s'"
                        .formatted(contentLine.id(), j + 1, snippet(contentSeg.value()), snippet(extractedSeg.value())));
            }
        }
    }

    /**
     * text 段归一化：全角空格/不断行空格及全部空白去除；全角 ASCII 区（！～，：？等）
     * 映射半角；。（U+3002）、（U+3001）、弯引号映射 ASCII 对应；连续标点不折叠。
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isSpace(c)) {
                continue;
            }
            if (c >= '！' && c <= '～') {
                c = (char) (c - 0xFEE0);
            }
            switch (c) {
                case '。' -> c = '.';
                case '、' -> c = ',';
                case '‘', '’' -> c = '\'';
                case '“', '”' -> c = '"';
                default -> { }
            }
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** math 段：只去全部空白（LaTeX 大小写敏感、标点不映射）。 */
    private static String stripWhitespace(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isSpace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == '　' || c == ' ' || c == ' ' || c == ' ' || Character.isWhitespace(c);
    }

    private static String snippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > SNIPPET ? value.substring(0, SNIPPET) : value;
    }

    private static List<ContentJson.Line> lines(ContentJson content) {
        return content == null || content.problem() == null || content.problem().lines() == null
                ? List.of() : content.problem().lines();
    }
}
