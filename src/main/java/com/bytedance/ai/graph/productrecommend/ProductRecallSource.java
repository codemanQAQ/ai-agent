package com.bytedance.ai.graph.productrecommend;

public enum ProductRecallSource {

    CATALOG_KEYWORD,
    CATALOG_FILTER,
    RAG_CHUNK,
    IMAGE_VECTOR,
    HISTORY_SNAPSHOT,
    PREFERENCE
}
