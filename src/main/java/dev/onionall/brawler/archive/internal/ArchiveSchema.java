package dev.onionall.brawler.archive.internal;

import java.io.IOException;
import java.sql.*;

/**
 * <b>Проверка версии и restart-idempotent DDL.</b>
 *
 * <p>Вызывается только при открытии context до публикации экземпляра. H2 DDL имеет implicit commit; core schema остаётся version 2.
 *
 * @author Stephan Kolesnikov
 * @author GPT-6.1 Sol
 */
final class ArchiveSchema {
    private ArchiveSchema() {}

    /**
     * <b>Создаёт и проверяет схему каталога.</b>
     *
     * <p>H2 DDL коммитится отдельно; шаги сохраняют restart-idempotency и additive observation extension.
     *
     * @param db соединение в autocommit до публикации context
     * @throws SQLException при ошибке операции или нарушении контракта
     * @throws IOException при ошибке операции или нарушении контракта
     */
    static void migrate(Connection db) throws SQLException, IOException {
        try (Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY)");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT MAX(version) FROM schema_version"
            )) {
                rs.next();
                if (rs.getInt(1) > 2) {
                    throw new IOException("Archive schema is newer than this application");
                }
            }
            statement.execute("CREATE TABLE IF NOT EXISTS catalog_guard (id INT PRIMARY KEY)");
            statement.execute("MERGE INTO catalog_guard KEY(id) VALUES (1)");
            statement.execute("CREATE TABLE IF NOT EXISTS crawl_plans (id VARCHAR(36) PRIMARY KEY, seeds_json CLOB NOT NULL, origin VARCHAR(256) NOT NULL, max_depth INT NOT NULL CHECK(max_depth BETWEEN 0 AND 5), max_pages INT NOT NULL CHECK(max_pages BETWEEN 1 AND 100), provider VARCHAR(256) NOT NULL, approved BOOLEAN NOT NULL DEFAULT FALSE, created_at VARCHAR(40) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS pages (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, latest_snapshot VARCHAR(36), last_seen VARCHAR(40), UNIQUE(job_id,canonical_url))");
            statement.execute("CREATE TABLE IF NOT EXISTS snapshots (id VARCHAR(36) PRIMARY KEY, page_id VARCHAR(36) NOT NULL REFERENCES pages(id), content_hash VARCHAR(64) NOT NULL, content_size INT NOT NULL CHECK(content_size BETWEEN 1 AND 131072), http_status INT NOT NULL CHECK(http_status=0 OR http_status BETWEEN 200 AND 299), content_type VARCHAR(64) NOT NULL, fetched_at VARCHAR(40), recorded_at VARCHAR(40) NOT NULL, source_kind VARCHAR(16) NOT NULL, discovery_depth INT NOT NULL CHECK(discovery_depth BETWEEN 0 AND 5), source_url VARCHAR(2048) NOT NULL, title VARCHAR(2048) NOT NULL, extracted_text CLOB NOT NULL, UNIQUE(page_id,content_hash,source_kind))");
            statement.execute("CREATE TABLE IF NOT EXISTS page_links (snapshot_id VARCHAR(36) NOT NULL REFERENCES snapshots(id), ordinal INT NOT NULL, source_url VARCHAR(2048) NOT NULL, target_url VARCHAR(2048) NOT NULL, disposition VARCHAR(64) NOT NULL, PRIMARY KEY(snapshot_id,ordinal))");
            statement.execute("CREATE TABLE IF NOT EXISTS admission_observations (job_id VARCHAR(36) PRIMARY KEY REFERENCES crawl_plans(id), observation_json CLOB NOT NULL, observed_at VARCHAR(40) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS failures (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, category VARCHAR(64) NOT NULL, occurred_at VARCHAR(40) NOT NULL, UNIQUE(job_id,canonical_url,category))");
            statement.execute("CREATE INDEX IF NOT EXISTS pages_job_idx ON pages(job_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS snapshots_page_idx ON snapshots(page_id,recorded_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS failures_job_idx ON failures(job_id,occurred_at)");
            // H2 DDL commits independently: each migration step must be restart-idempotent.
            // Legacy edges have no original discovery clock. Retain their snapshot's
            // recorded time explicitly as an approximation instead of fabricating one.
            statement.execute("ALTER TABLE page_links ADD COLUMN IF NOT EXISTS discovered_at VARCHAR(40)");
            statement.execute("ALTER TABLE page_links ADD COLUMN IF NOT EXISTS discovery_time_basis VARCHAR(32)");
            statement.execute("UPDATE page_links l SET discovered_at=(SELECT s.recorded_at FROM snapshots s WHERE s.id=l.snapshot_id), discovery_time_basis='SNAPSHOT_RECORDED' WHERE discovered_at IS NULL");
            statement.execute("CREATE INDEX IF NOT EXISTS links_target_idx ON page_links(target_url)");
            // Additive, independently versioned observation extension. CoreV2 and
            // legacy tables stay unchanged so previous binaries can still serializableRead them.
            statement.execute("CREATE TABLE IF NOT EXISTS failure_observations_v1 (observation_seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), failure_id VARCHAR(36) NOT NULL REFERENCES failures(id), canonical_url VARCHAR(2048) NOT NULL, category VARCHAR(64) NOT NULL, observed_at VARCHAR(40) NOT NULL, phase VARCHAR(16) NOT NULL, source VARCHAR(32) NOT NULL, http_status INT CHECK(http_status IS NULL OR http_status BETWEEN 100 AND 599), retryable BOOLEAN, terminal_intent BOOLEAN, next_due_epoch BIGINT CHECK(next_due_epoch IS NULL OR next_due_epoch>=0), transport_category VARCHAR(32) NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS failure_observations_job_seq_v1 ON failure_observations_v1(job_id,observation_seq)");
            // Independently versioned text extension: legacy HTML tables and core version stay unchanged.
            statement.execute("CREATE TABLE IF NOT EXISTS archive_document_pages_v1 (id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES crawl_plans(id), canonical_url VARCHAR(2048) NOT NULL, latest_version VARCHAR(36), last_seen VARCHAR(40), UNIQUE(job_id,canonical_url))");
            statement.execute("CREATE TABLE IF NOT EXISTS archive_document_versions_v1 (id VARCHAR(36) PRIMARY KEY, page_id VARCHAR(36) NOT NULL REFERENCES archive_document_pages_v1(id), content_hash VARCHAR(64) NOT NULL, content_size INT NOT NULL CHECK(content_size BETWEEN 1 AND 131072), content_type VARCHAR(64) NOT NULL CHECK(content_type IN ('text/plain','text/markdown')), document_format VARCHAR(16) NOT NULL CHECK(document_format IN ('PLAIN_TEXT','MARKDOWN')), source_kind VARCHAR(16) NOT NULL CHECK(source_kind IN ('CRAWLER','MANUAL_IMPORT')), source_url VARCHAR(2048) NOT NULL, discovery_depth INT NOT NULL CHECK(discovery_depth BETWEEN 0 AND 5), http_status INT NOT NULL CHECK(http_status=0 OR http_status BETWEEN 200 AND 299), fetched_at VARCHAR(40), recorded_at VARCHAR(40) NOT NULL, title VARCHAR(2048) NOT NULL, extracted_text CLOB NOT NULL, links_json CLOB NOT NULL, UNIQUE(page_id,content_hash,source_kind,document_format), UNIQUE(page_id,id))");
            statement.execute("ALTER TABLE archive_document_pages_v1 ADD CONSTRAINT IF NOT EXISTS document_latest_owner_v1 FOREIGN KEY(id,latest_version) REFERENCES archive_document_versions_v1(page_id,id)");
            statement.execute("CREATE INDEX IF NOT EXISTS archive_documents_job_v1 ON archive_document_pages_v1(job_id)");
            statement.execute("MERGE INTO schema_version KEY(version) VALUES(2)");
        }
    }

}
