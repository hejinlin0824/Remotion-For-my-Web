package com.wyf.factory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段历史条目：每次阶段进入/退出/失败追加一条，随 Job 以 JSON 序列化存 CLOB。
 * state 取值：ENTER（进入阶段）/ EXIT（离开阶段）/ FAIL（阶段内失败）。
 */
public class StageHistoryEntry {

    public static final String STATE_ENTER = "ENTER";
    public static final String STATE_EXIT = "EXIT";
    public static final String STATE_FAIL = "FAIL";

    private String stage;
    /** ENTER | EXIT | FAIL */
    private String state;
    private String note;
    private LocalDateTime at;

    public StageHistoryEntry() {
        // Jackson 反序列化需要
    }

    public StageHistoryEntry(String stage, String state, String note, LocalDateTime at) {
        this.stage = stage;
        this.state = state;
        this.note = note;
        this.at = at;
    }

    /**
     * List&lt;StageHistoryEntry&gt; ↔ JSON 字符串（存 CLOB）。
     * LocalDateTime 走 jsr310（ISO-8601 字符串，可读且纳秒精度无损）；
     * 序列化/反序列化失败包成 IllegalStateException——历史数据损坏不静默。
     */
    @Converter
    public static class StageHistoryConverter implements AttributeConverter<List<StageHistoryEntry>, String> {

        private static final ObjectMapper MAPPER = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        @Override
        public String convertToDatabaseColumn(List<StageHistoryEntry> attribute) {
            try {
                return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stageHistory 序列化失败", e);
            }
        }

        @Override
        public List<StageHistoryEntry> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return new ArrayList<>();
            }
            try {
                return MAPPER.readValue(dbData,
                        MAPPER.getTypeFactory().constructCollectionType(List.class, StageHistoryEntry.class));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stageHistory 反序列化失败", e);
            }
        }
    }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }

    /**
     * 值语义：Hibernate 对 @Convert 的可变 List 属性做快照比对时按元素 equals 判脏，
     * 没有值语义会导致每次 flush 都误判脏（insert 后紧跟一次空 UPDATE，version 空转）。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StageHistoryEntry other)) {
            return false;
        }
        return java.util.Objects.equals(stage, other.stage)
                && java.util.Objects.equals(state, other.state)
                && java.util.Objects.equals(note, other.note)
                && java.util.Objects.equals(at, other.at);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(stage, state, note, at);
    }
}
