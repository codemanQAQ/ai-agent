package com.bytedance.ai.graph.productrecommend;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class ProductRecallTextTokenizer {

    private ProductRecallTextTokenizer() {
    }

    static Set<String> tokens(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String item : text.split("[\\s,，。.!！?？;；:：/|、()（）\\[\\]{}<>《》\"']+")) {
            addToken(tokens, item);
        }
        String compact = text.replaceAll("[\\s,，。.!！?？;；:：/|、()（）\\[\\]{}<>《》\"']", "");
        if (containsCjk(compact)) {
            for (int index = 0; index < compact.length() - 1; index++) {
                addToken(tokens, compact.substring(index, index + 2));
            }
        }
        return Set.copyOf(tokens);
    }

    static String bestKeyword(String queryText, List<String> fallbackValues) {
        if (StringUtils.hasText(queryText)) {
            return queryText.trim();
        }
        if (fallbackValues != null) {
            return fallbackValues.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static void addToken(Set<String> tokens, String token) {
        if (StringUtils.hasText(token)) {
            tokens.add(token.trim());
        }
    }

    private static boolean containsCjk(String value) {
        for (int index = 0; index < value.length(); index++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(index));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
