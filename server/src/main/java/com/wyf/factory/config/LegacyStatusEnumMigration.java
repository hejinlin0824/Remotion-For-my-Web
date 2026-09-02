package com.wyf.factory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 老库 status 列原生 ENUM 迁移守卫（T27 亲修，2026-09-02 事故二）。
 *
 * <p>事故：真实老库 {@code jobs.status} 是<b>原生 H2 ENUM</b>（早年建库残留；实体
 * {@code @Enumerated(EnumType.STRING)} 新建库本是 VARCHAR(20)，而 ddl-auto=update 从不改
 * 列类型，隐患潜伏至今）。T27 新增第 11 个状态 {@code AWAITING_CONFIRM} 后，IMAGE 题
 * 识图完成写闸即被 H2 拒绝（{@code Value not permitted for column}）→ 任务 FAILED。
 * 全新库无此问题；凡从老文件库升级的部署必须有此转换。</p>
 *
 * <p>修法（幂等）：启动完成时查 {@code INFORMATION_SCHEMA.COLUMNS}（H2 2.x 的
 * {@code DATA_TYPE} 列直接是类型名，ENUM 列即 {@code ENUM}；注意 JDBC
 * DatabaseMetaData.TYPE_NAME 对 ENUM 不返回 'ENUM'，勿用）检测 {@code JOBS.STATUS}，
 * 若为 ENUM 则 {@code ALTER TABLE jobs ALTER COLUMN status SET DATA TYPE CHARACTER
 * VARYING(20)}——数据与 NOT NULL 均保留，已在一次性内存库实证（注意 NOT NULL 不能随
 * SET DATA TYPE 同语句重申，H2 2.2.224 语法错）。转为 VARCHAR 与实体映射对齐，今后新增
 * 状态值不再受固化值列表阻碍。挂 {@code ApplicationReadyEvent}（Hibernate 建表/迁移
 * 之后）；时序上写新状态值最早发生在一次 IMAGE 识图完成时，远晚于启动，无竞争面。
 * 回归牙齿={@code LegacyDbMigrationTest}（真实老库 schema 镜像）。</p>
 */
@Component
public class LegacyStatusEnumMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyStatusEnumMigration.class);

    private final DataSource dataSource;

    public LegacyStatusEnumMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateEnumStatusToVarchar() {
        try (Connection c = dataSource.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='JOBS' AND COLUMN_NAME='STATUS'")) {
            if (!rs.next() || !"ENUM".equalsIgnoreCase(rs.getString("DATA_TYPE"))) {
                return; // 新库（VARCHAR）或表尚未建：幂等直通
            }
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE jobs ALTER COLUMN status SET DATA TYPE CHARACTER VARYING(20)");
                log.warn("旧库 jobs.status 为原生 ENUM（值列表固化，无法写入 T27 新增状态 "
                        + "AWAITING_CONFIRM），已迁移为 VARCHAR(20)，既有行数据保留");
            }
        } catch (Exception e) {
            // 尽力而为：转换失败仅告警，不阻断启动（与 T22 keepTtsLinesQuietly 同模式）
            log.error("旧库 status ENUM 迁移失败，AWAITING_CONFIRM 写入将继续失败，请人工处理", e);
        }
    }
}
