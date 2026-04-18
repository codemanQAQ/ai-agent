package com.involutionhell.backend.rag.indexing.messaging;

import com.involutionhell.backend.rag.indexing.application.RagIndexingService;
import com.involutionhell.backend.rag.indexing.application.RagIndexMessageFailureAuditService;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexMessageFailureRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRepository;
import com.involutionhell.backend.rag.indexing.persistence.RagIndexOutboxRecord;
import com.involutionhell.backend.rag.indexing.notification.RagIndexMessageParseFailureNotifier;
import com.involutionhell.backend.rag.indexing.service.RagIndexingFailureClassifier;
import com.involutionhell.backend.rag.indexing.service.RagIndexingMetrics;
import com.involutionhell.backend.rag.indexing.workflow.IndexWorkflowService;
import com.involutionhell.backend.rag.shared.properties.RagProperties;
import com.involutionhell.backend.rag.shared.support.RagJsonCodec;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndexMessageListenerTests {

    @Test
    void malformedMessageReturnsFailureForMqRetry() {
        RagJsonCodec jsonCodec = new RagJsonCodec(new ObjectMapper());
        RagIndexMessageListener listener = new RagIndexMessageListener(
                jsonCodec,
                RagProperties.defaults(),
                new RagIndexingFailureClassifier(),
                null,
                null,
                new TestOutboxRepository(),
                new RagIndexMessageFailureAuditService(
                        new TestFailureRepository(1),
                        new RagIndexingMetrics(noMeterRegistry()),
                        RagProperties.defaults(),
                        jsonCodec
                ),
                emptyNotifierProvider(),
                null
        );

        ConsumeResult result = listener.consume(new TestMessageView("message-1", "{bad json".getBytes(), 1));

        assertThat(result).isEqualTo(ConsumeResult.FAILURE);
    }

    @Test
    void malformedMessageOverThresholdIsAcknowledgedAfterAudit() {
        RagJsonCodec jsonCodec = new RagJsonCodec(new ObjectMapper());
        RagIndexMessageListener listener = new RagIndexMessageListener(
                jsonCodec,
                RagProperties.defaults(),
                new RagIndexingFailureClassifier(),
                null,
                null,
                new TestOutboxRepository(),
                new RagIndexMessageFailureAuditService(
                        new TestFailureRepository(3),
                        new RagIndexingMetrics(noMeterRegistry()),
                        RagProperties.defaults(),
                        jsonCodec
                ),
                emptyNotifierProvider(),
                null
        );

        ConsumeResult result = listener.consume(new TestMessageView("message-2", "{bad json".getBytes(), 3));

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
    }

    @Test
    void malformedMessageAtThresholdTriggersExternalNotifierOnce() {
        TestParseFailureNotifier notifier = new TestParseFailureNotifier();
        TestOutboxRepository outboxRepository = new TestOutboxRepository();
        RagJsonCodec jsonCodec = new RagJsonCodec(new ObjectMapper());
        RagIndexMessageListener listener = new RagIndexMessageListener(
                jsonCodec,
                RagProperties.defaults(),
                new RagIndexingFailureClassifier(),
                null,
                null,
                outboxRepository,
                new RagIndexMessageFailureAuditService(
                        new TestFailureRepository(3),
                        new RagIndexingMetrics(noMeterRegistry()),
                        RagProperties.defaults(),
                        jsonCodec
                ),
                provider(notifier),
                null
        );

        ConsumeResult result = listener.consume(new TestMessageView("message-3", "{bad json".getBytes(), 3));

        assertThat(result).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(notifier.notifiedMessageIds).containsExactly("message-3");
        assertThat(outboxRepository.confirmedByMessageId).containsExactly("message-3");
    }

    private record TestMessageId(String value) implements MessageId {
        @Override
        public String getVersion() {
            return "v1";
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private record TestMessageView(String id, byte[] body, int deliveryAttempt) implements MessageView {
        @Override
        public MessageId getMessageId() {
            return new TestMessageId(id);
        }

        @Override
        public String getTopic() {
            return "topic";
        }

        @Override
        public ByteBuffer getBody() {
            return ByteBuffer.wrap(body);
        }

        @Override
        public Map<String, String> getProperties() {
            return Map.of();
        }

        @Override
        public Optional<String> getTag() {
            return Optional.empty();
        }

        @Override
        public Collection<String> getKeys() {
            return List.of();
        }

        @Override
        public Optional<String> getMessageGroup() {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getDeliveryTimestamp() {
            return Optional.empty();
        }

        @Override
        public String getBornHost() {
            return "localhost";
        }

        @Override
        public long getBornTimestamp() {
            return 0L;
        }

        @Override
        public int getDeliveryAttempt() {
            return deliveryAttempt;
        }
    }

    private record TestFailureRepository(int countAfterSave) implements RagIndexMessageFailureRepository {
        @Override
        public void save(
                String messageId,
                String topic,
                int deliveryAttempt,
                String failureType,
                String errorMessage,
                String payloadBase64,
                String payloadPreview,
                String propertiesJson
        ) {
        }

        @Override
        public int countByMessageId(String messageId) {
            return countAfterSave;
        }
    }

    private static final class TestParseFailureNotifier implements RagIndexMessageParseFailureNotifier {

        private final List<String> notifiedMessageIds = new java.util.ArrayList<>();

        @Override
        public void notifyParseFailureThresholdReached(
                String messageId,
                String topic,
                int deliveryAttempt,
                int failureCount,
                String errorMessage,
                String payloadPreview,
                String propertiesJson
        ) {
            notifiedMessageIds.add(messageId);
        }
    }

    private static final class TestOutboxRepository implements RagIndexOutboxRepository {

        private final List<String> confirmedByMessageId = new java.util.ArrayList<>();

        @Override
        public void enqueue(Long documentId, String contentSha256, com.involutionhell.backend.rag.indexing.model.RagIndexOutboxEventType eventType) {
        }

        @Override
        public List<RagIndexOutboxRecord> findDispatchable(java.time.OffsetDateTime now, int limit) {
            return List.of();
        }

        @Override
        public boolean markSending(Long id) {
            return false;
        }

        @Override
        public void markSent(Long id, String messageId) {
        }

        @Override
        public boolean confirmConsumed(Long documentId, String contentSha256, String messageId) {
            return false;
        }

        @Override
        public boolean confirmConsumedByMessageId(String messageId) {
            confirmedByMessageId.add(messageId);
            return true;
        }

        @Override
        public void markFailed(Long id, String errorMessage, java.time.OffsetDateTime nextAttemptAt) {
        }

        @Override
        public List<RagIndexOutboxRecord> findStuckSendingBefore(java.time.OffsetDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public void resetForRetry(Long id, String errorMessage, java.time.OffsetDateTime nextAttemptAt) {
        }

        @Override
        public java.util.Optional<RagIndexOutboxRecord> findByDocumentIdAndContentSha256(Long documentId, String contentSha256) {
            return java.util.Optional.empty();
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }
    }

    private static ObjectProvider<RagIndexMessageParseFailureNotifier> emptyNotifierProvider() {
        return provider(null);
    }

    private static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noMeterRegistry() {
        return provider(null);
    }
}
