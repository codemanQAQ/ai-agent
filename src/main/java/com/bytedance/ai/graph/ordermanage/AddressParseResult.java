package com.bytedance.ai.graph.ordermanage;

import java.util.List;

public record AddressParseResult(
        boolean complete,
        List<String> missingFields,
        AddressSnapshot snapshot
) {
}
