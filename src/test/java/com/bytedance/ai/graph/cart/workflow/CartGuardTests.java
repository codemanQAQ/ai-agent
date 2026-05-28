package com.bytedance.ai.graph.cart.workflow;

import com.bytedance.ai.graph.catalog.api.CatalogQueryFacade;
import com.bytedance.ai.graph.catalog.api.CatalogSkuView;
import com.bytedance.ai.graph.catalog.api.CatalogSpuView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartGuardTests {

    @Test
    void sameNumericPriceWithDifferentScaleDoesNotTriggerPriceChanged() {
        CartGuard guard = new CartGuard(new StubCatalog(new BigDecimal("199")));
        CartCommand command = new CartCommand(
                "user-1", "conversation-1", 101L, null, 1,
                new BigDecimal("199.00"), Map.of(), "test", null, null, Map.of());

        assertThatCode(() -> guard.validate(CartEvent.CONFIRM_ADD, com.bytedance.ai.graph.cart.api.CartState.IN_CART, command))
                .doesNotThrowAnyException();
    }

    @Test
    void differentNumericPriceTriggersPriceChanged() {
        CartGuard guard = new CartGuard(new StubCatalog(new BigDecimal("199")));
        CartCommand command = new CartCommand(
                "user-1", "conversation-1", 101L, null, 1,
                new BigDecimal("209.00"), Map.of(), "test", null, null, Map.of());

        assertThatThrownBy(() -> guard.validate(CartEvent.CONFIRM_ADD, com.bytedance.ai.graph.cart.api.CartState.IN_CART, command))
                .isInstanceOf(CartWorkflowException.class)
                .hasMessageContaining("商品价格已变化");
    }

    private record StubCatalog(BigDecimal price) implements CatalogQueryFacade {
        @Override
        public CatalogSpuView getSpu(Long spuId) {
            return new CatalogSpuView(
                    spuId, "SPU-" + spuId, "通勤包", "brand", "bags",
                    price, price, 10, "", List.of(), null, Map.of(), "DONE", "ACTIVE",
                    null, List.of(), OffsetDateTime.now(), OffsetDateTime.now());
        }

        @Override
        public Optional<CatalogSpuView> findSpuByExternalRef(String externalRef) {
            return Optional.empty();
        }

        @Override
        public List<CatalogSkuView> listSkus(Long spuId) {
            return List.of();
        }
    }
}
