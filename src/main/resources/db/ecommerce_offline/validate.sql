SELECT 'products' AS label, count(*) AS count
FROM ecommerce_offline.ecom_product
UNION ALL
SELECT 'skus', count(*)
FROM ecommerce_offline.ecom_sku
UNION ALL
SELECT 'faqs', count(*)
FROM ecommerce_offline.ecom_product_faq
UNION ALL
SELECT 'reviews', count(*)
FROM ecommerce_offline.ecom_product_review
UNION ALL
SELECT 'images', count(*)
FROM ecommerce_offline.ecom_product_image
UNION ALL
SELECT 'chunks', count(*)
FROM ecommerce_offline.ecom_product_chunk
UNION ALL
SELECT 'embedding_required_chunks', count(*)
FROM ecommerce_offline.ecom_product_chunk
WHERE embedding_required;

SELECT product_id, title, brand, category_path, price_min, price_max, stock_total, image_path
FROM ecommerce_offline.ecom_product
WHERE product_id = 'p_digital_001';

SELECT sku_id, spec_json, price, stock
FROM ecommerce_offline.ecom_sku
WHERE product_id = 'p_digital_001'
ORDER BY sku_id;

SELECT chunk_type, embedding_modality, count(*) AS count
FROM ecommerce_offline.ecom_product_chunk
WHERE embedding_required
GROUP BY chunk_type, embedding_modality
ORDER BY chunk_type, embedding_modality;
