package com.involutionhell.backend.rag.indexing.workflow;

import com.involutionhell.backend.rag.document.spi.DocumentIndexingView;
import com.involutionhell.backend.rag.shared.model.RagDocumentStatus;
import org.springframework.stereotype.Component;

/**
 * 索引工作流集中式守卫。
 */
@Component
public class IndexWorkflowGuard {

    /**
     * 校验指定事件在当前文档上下文下是否允许继续。
     */
    public void validate(
            IndexWorkflowEvent event,
            IndexWorkflowState currentState,
            DocumentIndexingView document,
            IndexWorkflowCommand command
    ) {
        if (event == IndexWorkflowEvent.QUEUE) {
            return;
        }
        if (document == null) {
            if (event == IndexWorkflowEvent.SKIP) {
                return;
            }
            throw new IndexWorkflowTransitionException("索引工作流守卫失败：文档不存在，无法处理事件 " + event);
        }
        if (document.contentSha256() != null
                && command.contentSha256() != null
                && !document.contentSha256().equals(command.contentSha256())
                && event != IndexWorkflowEvent.SKIP) {
            throw new IndexWorkflowTransitionException("索引工作流守卫失败：文档版本已变化，无法处理事件 " + event);
        }
        if (RagDocumentStatus.DELETING.name().equals(document.status()) && event != IndexWorkflowEvent.SKIP) {
            throw new IndexWorkflowTransitionException("索引工作流守卫失败：文档正在删除，无法处理事件 " + event);
        }
        if (currentState.terminal() && event != IndexWorkflowEvent.QUEUE) {
            throw new IndexWorkflowTransitionException("索引工作流守卫失败：终态任务不允许继续推进，当前状态=" + currentState);
        }
    }
}
