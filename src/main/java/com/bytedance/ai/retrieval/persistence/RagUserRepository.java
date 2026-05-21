package com.bytedance.ai.retrieval.persistence;

public interface RagUserRepository {

    void upsertSeen(String userId);
}
