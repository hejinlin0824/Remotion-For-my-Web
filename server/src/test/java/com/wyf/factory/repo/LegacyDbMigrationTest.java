package com.wyf.factory.repo;

import com.wyf.factory.domain.Job;
import com.wyf.factory.domain.JobStatus;
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
 * 老库迁移守卫（T27 亲修回归）：新列/新枚举值必须能对<b>已有数据</b>的 H2 文件库安全落地。
 * 预置 schema 按真实老库逐列镜像（2026-09-02 SHOW COLUMNS 实录），不再手写猜测。
 *
 * <p>事故一（09-01 23:09 起服务实测）：T27 新增 {@code revise_count} 裸 int 生成
 * {@code add column integer not null}（无 DEFAULT）——H2 拒绝对有行数据的表追加 NOT NULL
 * 无默认列，DDL 失败后列不存在，启动 sweep 首查缺列 → 服务崩启动。既有测试全绿系盲区：
 * 测试库全为全新空表。修法：{@code @Column(columnDefinition = "integer default 0")}。</p>
 *
 * <p>事故二（09-02 08:3x 用户实测首单）：真实老库 {@code status} 列是<b>原生 H2 ENUM</b>
 * （早年建库残留，值列表固化旧 10 状态；实体 {@code @Enumerated(EnumType.STRING)} 的新库
 * 本是 VARCHAR，而 ddl-auto=update 从不改列类型，潜伏至今）——IMAGE 题识图完成写
 * {@code AWAITING_CONFIRM} 即被拒（{@code Value not permitted for column}）。修法：
 * 启动守卫 {@code LegacyStatusEnumMigration} 检测到 ENUM 列即转 VARCHAR(20)（数据与
 * NOT NULL 均保留，已在一次性内存库实证）。</p>
 *
 * <p>机制：静态初始化块在 Spring 上下文构建<b>之前</b>，用普通 JDBC 把同名命名内存库
 * 预置成「真实老库 schema + 一行 DONE 旧数据」；随后 Hibernate {@code ddl-auto=update}
 * 对它做真实迁移。行种 DONE 终态，避免启动 sweep 试图驱动它。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:h2:mem:legacyt27;DB_CLOSE_DELAY=-1")
@DisplayName("老库迁移守卫：已有数据的库上新增列/新状态值必须安全落地")
class LegacyDbMigrationTest {

    private static final String LEGACY_ID = "legacy-t27-migration-row";
    private static final String URL = "jdbc:h2:mem:legacyt27;DB_CLOSE_DELAY=-1";

    static {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement()) {
            // 真实老库 schema 镜像（09-02 SHOW COLUMNS 实录）：status=原生 ENUM（事故二根源），
            // 无 revise_count（事故一根源）；其余列类型逐列对齐。
            st.execute("""
                    CREATE TABLE IF NOT EXISTS jobs (
                      id VARCHAR(36) PRIMARY KEY,
                      artifacts_dir VARCHAR(255),
                      aspect VARCHAR(10),
                      callback_url VARCHAR(255),
                      cancel_requested BOOLEAN DEFAULT FALSE,
                      created_at TIMESTAMP(6),
                      error_message CLOB,
                      extract_retries INTEGER DEFAULT 0,
                      gen_deadline_at TIMESTAMP(6),
                      gen_retries INTEGER DEFAULT 0,
                      image_base64 BINARY LARGE OBJECT,
                      input_text CLOB,
                      input_type VARCHAR(10),
                      last_error CLOB,
                      processing_deadline_at TIMESTAMP(6),
                      qa_rounds INTEGER DEFAULT 0,
                      resolution VARCHAR(10),
                      review_retries INTEGER DEFAULT 0,
                      stage VARCHAR(20),
                      stage_history CLOB,
                      status ENUM('QUEUED','EXTRACTING','GENERATING','REVIEWING','SPEAKING',
                            'RENDERING','QA','DONE','FAILED','CANCELLED') NOT NULL,
                      tts_retries INTEGER DEFAULT 0,
                      updated_at TIMESTAMP(6),
                      version BIGINT DEFAULT 0,
                      voice VARCHAR(50)
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
    @DisplayName("事故一：revise_count 迁移后老行可读回且回填 0")
    void legacyRowReadableAfterMigration(@Autowired JobRepository repo) {
        Job legacy = repo.findById(LEGACY_ID).orElseThrow();
        assertThat(legacy.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(legacy.getReviseCount()).isZero();
    }

    @Test
    @DisplayName("事故二：老库上必须能写入第 11 个状态 AWAITING_CONFIRM（确认闸）")
    void awaitingConfirmWritableOnLegacyDb(@Autowired JobRepository repo) {
        Job legacy = repo.findById(LEGACY_ID).orElseThrow();
        legacy.setStatus(JobStatus.AWAITING_CONFIRM);
        Job saved = repo.save(legacy);
        repo.flush();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.AWAITING_CONFIRM);
        assertThat(repo.findById(LEGACY_ID).orElseThrow().getStatus())
                .isEqualTo(JobStatus.AWAITING_CONFIRM);
        // 还原现场：重读拿新 @Version 再还原（测试法间互不污染，读回测试依赖行仍为 DONE）
        Job fresh = repo.findById(LEGACY_ID).orElseThrow();
        fresh.setStatus(JobStatus.DONE);
        repo.save(fresh);
        repo.flush();
    }
}
