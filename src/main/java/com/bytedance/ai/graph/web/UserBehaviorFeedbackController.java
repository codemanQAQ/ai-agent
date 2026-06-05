package com.bytedance.ai.graph.web;

import com.bytedance.ai.graph.session.UserBehaviorFeedbackRequest;
import com.bytedance.ai.graph.session.UserBehaviorFeedbackResult;
import com.bytedance.ai.graph.session.UserPreferenceMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/public/agent/feedback")
public class UserBehaviorFeedbackController {

    private final UserPreferenceMemoryService preferenceMemoryService;

    public UserBehaviorFeedbackController(UserPreferenceMemoryService preferenceMemoryService) {
        this.preferenceMemoryService = preferenceMemoryService;
    }

    @PostMapping
    public UserBehaviorFeedbackResult accept(@Valid @RequestBody FeedbackBody body) {
        return preferenceMemoryService.accept(new UserBehaviorFeedbackRequest(
                body.userId(),
                body.conversationId(),
                body.runId(),
                body.productId(),
                body.skuId(),
                body.externalRef(),
                body.behaviorType(),
                body.rank(),
                body.context()
        ));
    }

    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam @NotBlank @Size(max = 64) String userId) {
        return preferenceMemoryService.memory(userId);
    }

    public record FeedbackBody(
            @NotBlank @Size(max = 64) String userId,
            @Size(max = 64) String conversationId,
            @Size(max = 64) String runId,
            @Size(max = 128) String productId,
            @Size(max = 128) String skuId,
            @Size(max = 128) String externalRef,
            @NotBlank @Size(max = 32) String behaviorType,
            Integer rank,
            Map<String, Object> context
    ) {
    }
}
