package com.wyf.factory.repo;

import com.wyf.factory.domain.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老库迁移守卫（T27 亲修回归）：新列必须能对<b>已有数据</b>的 H2 文件库安全追加。
 *
 * <p>事故（2026-09-01 23:09 起服务实测）：T27 新增 {@code revise_count} 时裸 {@code int}
 * 生成 {@code alter table jobs add column revise_count integer not null}（无 DEFAULT）——
 * H2 拒绝对有行数据的表追加 NOT NULL 无默认列（{@code NULL not allowed for column
 * "REVISE_COUNT"}），DDL 失败后列不存在，启动 sweep 首查即
 * {@code Column "J1_0.REVISE_COUNT" not found} → 整个服务起不来。既有 384 测全绿是因为
 * 测试库全是全新空表（空表追加 NOT NULL 列不触发此错）——本测试补上这个盲区。</p>
 *
 * <p>修法：{@code @Column(columnDefinition = "integer default 0")}，生成
 * {@code add column revise_count integer default 0 not null}，H2 用默认值回填老行。</p>
 *
 * <p>机制：静态初始化块在 Spring 上下文构建<b>之前</b>，用普通 JDBC 连接把同名命名内存库
 * 预置成「T26 时代 schema + 一行 DONE 旧数据」（无 revise_count 列）；随后 Hibernate
 * {@code ddl-auto=update} 对它做真实迁移——修法错误则上下文启动即失败（先红）。
 * 行种 DONE 终态，避免启动 sweep 试图驱动它。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:h2:mem:legacyt27;DB_CLOSE_DELAY=-1")
@DisplayName("老库迁移守卫：已有数据的库上追加 revise_count 必须成功且老行回填 0")
class LegacyDbMigrationTest {

    private static final String LEGACY_ID = "legacy-t27-migration-row";
    private static final String URL = "jdbc:h2:mem:legacyt27;DB_CLOSE_DELAY=-1";

    static {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement()) {
            // T26 时代 jobs schema（无 revise_count；与实体其余列对齐，类型从宽）
            st.execute("""
                    CREATE TABLE IF NOT EXISTS jobs (
                      id VARCHAR(36) PRIMARY KEY,
                      artifacts_dir VARCHAR(512),
                      aspect VARCHAR(10),
                      callback_url VARCHAR(512),
                      cancel_requested BOOLEAN DEFAULT FALSE,
                      created_at TIMESTAMP,
                      error_message CLOB,
                      extract_retries INTEGER DEFAULT 0,
                      gen_deadline_at TIMESTAMP,
                      gen_retries INTEGER DEFAULT 0,
                      image_base64 CLOB,
                      input_text CLOB,
                      input_type VARCHAR(10),
                      last_error CLOB,
                      processing_deadline_at TIMESTAMP,
                      qa_rounds INTEGER DEFAULT 0,
                      resolution VARCHAR(10),
                      review_retries INTEGER DEFAULT 0,
                      stage VARCHAR(30),
                      stage_history CLOB,
                      status VARCHAR(30),
                      tts_retries INTEGER DEFAULT 0,
                      updated_at TIMESTAMP,
                      version BIGINT DEFAULT 0,
                      voice VARCHAR(30)
                    )""");
            // 幂等：同 JVM 重入先清后插
            st.execute("DELETE FROM jobs WHERE id = '" + LEGACY_ID + "'");
            st.execute("""
                    INSERT INTO jobs (id, artifacts_dir, aspect, callback_url, cancel_requested,
                      created_at, extract_retries, gen_retries, input_text, input_type,
                      qa_rounds, resolution, review_retries, stage, status, tts_retries,
                      updated_at, version, voice)
                    VALUES ('""" + LEGACY_ID + """
                    ', 'artifacts/legacy', '16:9', NULL, FALSE,
                      CURRENT_TIMESTAMP, 0, 0, '旧库老行（T26 时代提交）', 'TEXT',
                      0, '720p', 0, 'DONE', 'DONE', 0,
                      CURRENT_TIMESTAMP, 0, 'Cherry')""");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
    }

    @Test
    @DisplayName("迁移后老行可读回且 reviseCount 回填 0")
    void legacyRowReadableAfterMigration(@Autowired JobRepository repo) {
        Job legacy = repo.findById(LEGACY_ID).orElseThrow();
        assertThat(legacy.getStatus().name()).isEqualTo("DONE");
        assertThat(legacy.getReviseCount()).isZero();
    }
}
