package com.involutionhell.backend.rag.indexing.persistence.jdbc;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.model.RagIndexJobStatus;
import com.involutionhell.backend.rag.indexing.model.RagIndexStage;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 基于 Spring JDBC 的离线索引作业仓储实现。
 */
@Repository
public class JdbcRagIndexJobRepository implements RagIndexJobRepository {

    private static final RowMapper<RagIndexJobRecord> ROW_MAPPER = (rs, rowNum) -> new RagIndexJobRecord(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getString("content_sha256"),
            rs.getString("status"),
            rs.getString("stage"),
            rs.getLong("version"),
            rs.getString("last_event"),
            rs.getInt("attempt_count"),
            (Long) rs.getObject("target_generation"),
            rs.getString("message_id"),
            rs.getString("last_error"),
            toOffsetDateTime(rs.getTimestamp("started_at")),
            toOffsetDateTime(rs.getTimestamp("finished_at")),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public JdbcRagIndexJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void queue(Long documentId, String contentSha256) {
        // 同一文档版本只保留一条作业记录；重复排队时直接把状态重置为 QUEUED。
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET status = ?, stage = ?, attempt_count = 0, target_generation = NULL,
                       message_id = NULL, last_error = NULL, started_at = NULL, finished_at = NULL,
                       updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                RagIndexJobStatus.QUEUED.name(),
                RagIndexStage.QUEUED.name(),
                documentId,
                contentSha256
        );
        if (updatedRows > 0) {
            return;
        }

        try {
            jdbc.update(
                    """
                    INSERT INTO rag_index_jobs (
                        document_id, content_sha256, status, stage
                    ) VALUES (?, ?, ?, ?)
                    """,
                    documentId,
                    contentSha256,
                    RagIndexJobStatus.QUEUED.name(),
                    RagIndexStage.QUEUED.name()
            );
        } catch (DuplicateKeyException exception) {
            // 并发排队时允许重试一次，把最终状态收敛到同一行。
            queue(documentId, contentSha256);
        }
    }

    @Override
    public void attachMessageId(Long documentId, String contentSha256, String messageId) {
        // messageId 后补绑定，方便把 RocketMQ 消息和内部 job 状态串起来排查。
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET message_id = ?, updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                messageId,
                documentId,
                contentSha256
        );
        if (updatedRows > 0) {
            return;
        }

        try {
            jdbc.update(
                    """
                    INSERT INTO rag_index_jobs (
                        document_id, content_sha256, status, stage, message_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    documentId,
                    contentSha256,
                    RagIndexJobStatus.QUEUED.name(),
                    RagIndexStage.QUEUED.name(),
                    messageId
            );
        } catch (DuplicateKeyException exception) {
            attachMessageId(documentId, contentSha256, messageId);
        }
    }

    @Override
    public void annotateEvent(Long documentId, String contentSha256, String event) {
        jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET last_event = ?, version = version + 1, updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                event,
                documentId,
                contentSha256
        );
    }

    @Override
    public void startAttempt(Long documentId, String contentSha256, Long targetGeneration) {
        // 真正开始一次消费时才进入 RUNNING，并记录目标 generation。
        int updatedRows = jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET status = ?, stage = ?, target_generation = ?, attempt_count = attempt_count + 1,
                       last_error = NULL, started_at = COALESCE(started_at, now()), finished_at = NULL,
                       updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                RagIndexJobStatus.RUNNING.name(),
                RagIndexStage.PREPARING.name(),
                targetGeneration,
                documentId,
                contentSha256
        );
        if (updatedRows > 0) {
            return;
        }

        try {
            jdbc.update(
                    """
                    INSERT INTO rag_index_jobs (
                        document_id, content_sha256, status, stage, attempt_count, target_generation, started_at
                    ) VALUES (?, ?, ?, ?, ?, ?, now())
                    """,
                    documentId,
                    contentSha256,
                    RagIndexJobStatus.RUNNING.name(),
                    RagIndexStage.PREPARING.name(),
                    1,
                    targetGeneration
            );
        } catch (DuplicateKeyException exception) {
            startAttempt(documentId, contentSha256, targetGeneration);
        }
    }

    @Override
    public void updateStage(Long documentId, String contentSha256, RagIndexStage stage) {
        jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET stage = ?, updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                stage.name(),
                documentId,
                contentSha256
        );
    }

    @Override
    public void recordRetry(Long documentId, String contentSha256, RagIndexStage stage, String errorMessage) {
        // MQ 将继续投递，所以 job 状态退回 QUEUED，但保留当前失败阶段和错误信息。
        jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET status = ?, stage = ?, last_error = ?, updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                RagIndexJobStatus.QUEUED.name(),
                stage.name(),
                errorMessage,
                documentId,
                contentSha256
        );
    }

    @Override
    public void markSucceeded(Long documentId, String contentSha256, Long targetGeneration) {
        jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET status = ?, stage = ?, target_generation = ?, last_error = NULL,
                       finished_at = now(), updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                RagIndexJobStatus.SUCCEEDED.name(),
                RagIndexStage.COMPLETED.name(),
                targetGeneration,
                documentId,
                contentSha256
        );
    }

    @Override
    public void markFailed(Long documentId, String contentSha256, RagIndexStage stage, String errorMessage) {
        jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET status = ?, stage = ?, last_error = ?, finished_at = now(), updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                RagIndexJobStatus.FAILED.name(),
                stage.name(),
                errorMessage,
                documentId,
                contentSha256
        );
    }

    @Override
    public void markSkipped(Long documentId, String contentSha256, String reason) {
        // 例如旧版本消息、文档不存在等场景会被显式标记为 SKIPPED，避免误判为失败。
        jdbc.update(
                """
                UPDATE rag_index_jobs
                   SET status = ?, stage = ?, last_error = ?, finished_at = now(), updated_at = now()
                 WHERE document_id = ? AND content_sha256 = ?
                """,
                RagIndexJobStatus.SKIPPED.name(),
                RagIndexStage.SKIPPED.name(),
                reason,
                documentId,
                contentSha256
        );
    }

    @Override
    public Optional<RagIndexJobRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
        List<RagIndexJobRecord> results = jdbc.query(
                "SELECT * FROM rag_index_jobs WHERE document_id = ? AND content_sha256 = ?",
                ROW_MAPPER,
                documentId,
                contentSha256
        );
        return results.stream().findFirst();
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        jdbc.update("DELETE FROM rag_index_jobs WHERE document_id = ?", documentId);
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
