package com.bytedance.ai.agent.tool;

import com.bytedance.ai.agent.api.IntentType;
import org.springframework.ai.tool.ToolCallback;

import java.util.Set;

public interface AgentToolCallback extends ToolCallback {

    Set<IntentType> handles();
}
