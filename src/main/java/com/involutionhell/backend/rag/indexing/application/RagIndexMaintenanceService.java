package com.involutionhell.backend.rag.indexing.application;

import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRecord;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexJobRepository;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowCommand;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowTriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class RagIndexMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(RagIndexMaintenanceService.class);

    private final RagIndexJobRepository jobRepository;
    private final RagIndexingService indexingService;
    private final IndexWorkflowService workflowService;
    private final TransactionTemplate transactionTemplate;

    public RagIndexMaintenanceService(RagIndexJobRepository jobRepository, RagIndexingService indexingService, IndexWorkflowService workflowService, TransactionTemplate transactionTemplate) {
        this.jobRepository = jobRepository;
        this.indexingService = indexingService;
        this.workflowService = workflowService;
        this.transactionTemplate = transactionTemplate;
    }

    // 默认清理 24 小时 前还未完成的任务
    @Scheduled(cron = "0 0 0 * * *")
    public void executeOrphanCleanup() {
        OffsetDateTime timeoutThreshold = OffsetDateTime.now().minusHours(2);
        List<RagIndexJobRecord> staleJobs = jobRepository.findStaleJobs(timeoutThreshold, 100);

        for (RagIndexJobRecord job : staleJobs) {
            // 使用 TransactionTemplate 手动开启事务
            transactionTemplate.executeWithoutResult(status -> {
                try {
                    processSingleOrphan(job);
                } catch (Exception e) {
                    status.setRollbackOnly(); // 发生异常回滚事务
                    log.error("自动清理孤儿任务失败: jobId={}, error={}", job.id(), e.getMessage());
                }
            });
        }
    }

    protected void processSingleOrphan(RagIndexJobRecord job) {
        log.warn("发现异常超时的索引任务，启动自动修复: documentId={}, generation={}",
                job.documentId(), job.targetGeneration());

        // 1. 调用 RagIndexingService 中现有的安全清理逻辑
        // 这会物理删除 Milvus 中对应 generation 的向量，并删除 PG 的 chunks
        indexingService.deleteOrphanedIndexingState(job.documentId(), job.targetGeneration());

        // 2. 强制将该 Job 状态推向失败，避免被重新扫描，并留下审计痕迹
        workflowService.fail(IndexWorkflowCommand.of(
                job.documentId(),
                job.contentSha256(),
                IndexWorkflowTriggerType.SYSTEM,
                "orphan-cleaner"
        ).withFailure("timeout", "任务处理超时且进程已失联，系统自动回收资源"));
    }
}