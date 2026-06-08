package com.bytedance.ai.graph.cart.web;

import com.bytedance.ai.graph.cart.api.CartCommandFacade;
import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartQueryFacade;
import com.bytedance.ai.graph.cart.api.CartView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车 REST 接口：与对话式 agent 共用同一套 {@link CartCommandFacade}/{@link CartQueryFacade}，
 * 因此前端通过这些接口的增删改查与 agent 在对话里的加购操作落在同一个购物车（按 userId+conversationId），
 * 天然同步——前端只需在每次操作后或每轮对话结束后重新拉取购物车即可。
 */
@RestController
@Validated
@RequestMapping("/public/cart")
public class CartController {

    private final CartQueryFacade cartQueryFacade;
    private final CartCommandFacade cartCommandFacade;

    public CartController(CartQueryFacade cartQueryFacade, CartCommandFacade cartCommandFacade) {
        this.cartQueryFacade = cartQueryFacade;
        this.cartCommandFacade = cartCommandFacade;
    }

    @GetMapping
    public CartView getCart(
            @RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestParam @NotBlank @Size(max = 64) String conversationId
    ) {
        return cartQueryFacade.getActiveCart(userId, conversationId);
    }

    @PostMapping("/items")
    public CartView addItem(@RequestBody AddItemRequest request) {
        Long spuId = parseLong(request.productId());
        String externalRef = spuId == null ? request.productId() : null;
        int quantity = request.quantity() == null || request.quantity() < 1 ? 1 : request.quantity();
        return cartCommandFacade.addItem(
                request.userId(),
                request.conversationId(),
                spuId,
                externalRef,
                request.skuCode(),
                quantity,
                null
        );
    }

    @PatchMapping("/items/{itemId}")
    public CartView updateQuantity(
            @PathVariable Long itemId,
            @RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestParam @NotBlank @Size(max = 64) String conversationId,
            @RequestParam int quantity
    ) {
        return cartCommandFacade.updateQuantity(userId, conversationId, itemId, null, null, quantity);
    }

    @DeleteMapping("/items/{itemId}")
    public CartView removeItem(
            @PathVariable Long itemId,
            @RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestParam @NotBlank @Size(max = 64) String conversationId
    ) {
        return cartCommandFacade.removeItem(userId, conversationId, itemId, null, null);
    }

    /** 清空购物车：逐项移除（保持购物车实例本身可继续使用，而非取消整车）。 */
    @DeleteMapping
    public CartView clearCart(
            @RequestParam @NotBlank @Size(max = 64) String userId,
            @RequestParam @NotBlank @Size(max = 64) String conversationId
    ) {
        CartView cart = cartQueryFacade.getActiveCart(userId, conversationId);
        if (cart.items() != null) {
            for (CartItemView item : cart.items()) {
                cartCommandFacade.removeItem(userId, conversationId, item.itemId(), null, null);
            }
        }
        return cartQueryFacade.getActiveCart(userId, conversationId);
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record AddItemRequest(
            @NotBlank @Size(max = 64) String userId,
            @NotBlank @Size(max = 64) String conversationId,
            @NotBlank @Size(max = 64) String productId,
            @Size(max = 64) String skuCode,
            Integer quantity
    ) {
    }
}
