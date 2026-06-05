package com.bytedance.ai.graph.cartmanage;

/**
 * Inventory check outcome used by cart_manage_workflow before allowing a quantity update.
 *
 * @param available     true if the requested quantity can be fulfilled
 * @param availableQty  current available quantity (may be 0 even when {@code available} is true if
 *                      the underlying provider does not expose exact stock)
 * @param productId     echoed back for trace
 * @param skuId         echoed back for trace
 */
public record StockResult(
        boolean available,
        int availableQty,
        String productId,
        String skuId
) {

    public static StockResult inStock(String productId, String skuId, int availableQty) {
        return new StockResult(true, availableQty, productId, skuId);
    }

    public static StockResult outOfStock(String productId, String skuId, int availableQty) {
        return new StockResult(false, availableQty, productId, skuId);
    }
}
