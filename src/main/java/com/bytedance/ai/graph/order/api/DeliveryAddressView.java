package com.bytedance.ai.graph.order.api;

public record DeliveryAddressView(
        Long id,
        String receiverName,
        String phone,
        String province,
        String city,
        String district,
        String detail,
        String postalCode
) {
}
