package com.wyf.factory.validate;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.ExtractResult;

/**
 * 校验输入：ASSEMBLED 剧本 + 该单的审题产物（V2 保真比对用）。
 *
 * @param content   ASSEMBLED 工位产出的剧本
 * @param extracted EXTRACTING 工位产出的结构化题干
 */
public record ValidationContext(ContentJson content, ExtractResult extracted) {
}
