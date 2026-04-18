package com.involutionhell.backend.rag.shared.support;

/**
 * RAG 模块日志辅助工具，统一做预览截断、hash 缩写和异常摘要。
 */
public final class RagLogHelper {

    private RagLogHelper() {
    }

    public static String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = normalizeWhitespace(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        if (maxLength <= 3) {
            return normalized.substring(0, Math.max(0, maxLength));
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    public static String previewQuestion(String question) {
        return abbreviate(question, 96);
    }

    public static String shortSha(String contentSha256) {
        if (contentSha256 == null || contentSha256.isBlank()) {
            return "-";
        }
        return contentSha256.length() <= 8 ? contentSha256 : contentSha256.substring(0, 8);
    }

    public static String errorSummary(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        Throwable root = rootCause(throwable);
        String type = root.getClass().getSimpleName();
        String message = abbreviate(root.getMessage(), 160);
        return message.isEmpty() ? type : type + ": " + message;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
