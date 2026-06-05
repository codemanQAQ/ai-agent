package com.bytedance.ai.graph.cartmanage;

import com.bytedance.ai.graph.cart.api.CartItemView;
import com.bytedance.ai.graph.cart.api.CartView;

import java.util.List;

/**
 * Structured payload written by cart_manage_workflow to state keys workflowResult / evidence /
 * businessResult. The downstream build_answer_context node turns this into the user-facing reply.
 *
 * <p>Convention:
 * <ul>
 *   <li>{@code action}, {@code slots} – what the slot-filling LLM decided</li>
 *   <li>{@code cartBefore} – cart snapshot read at the start of the workflow</li>
 *   <li>{@code targetItem} – resolved cart item (REMOVE/UPDATE), null otherwise</li>
 *   <li>{@code candidateItems} – populated when productName matched multiple items, prompting clarification</li>
 *   <li>{@code productCandidates} – populated when ADD needs the user to choose a catalog item</li>
 *   <li>{@code mutationResult} – populated after a successful write (REMOVE/UPDATE)</li>
 *   <li>{@code stockResult} – populated for UPDATE_QUANTITY</li>
 *   <li>{@code clarifyQuestion} – the question to ask user, for WAITING_CLARIFICATION</li>
 *   <li>{@code pendingConfirmAction} – machine-readable next step for WAITING_CONFIRMATION (e.g. "CLEAR_CART")</li>
 *   <li>{@code responseInstruction} – natural-language hint for the answer-context layer</li>
 * </ul>
 */
public record CartManageWorkflowResult(
        CartManageAction action,
        CartManageSlots slots,
        CartView cartBefore,
        CartItemView targetItem,
        List<CartItemView> candidateItems,
        List<ProductCandidate> productCandidates,
        CartMutationResult mutationResult,
        StockResult stockResult,
        String clarifyQuestion,
        String pendingConfirmAction,
        String responseInstruction,
        String errorCode,
        String errorMessage
) {

    public CartManageWorkflowResult {
        candidateItems = candidateItems == null ? List.of() : List.copyOf(candidateItems);
        productCandidates = productCandidates == null ? List.of() : List.copyOf(productCandidates);
    }
}
