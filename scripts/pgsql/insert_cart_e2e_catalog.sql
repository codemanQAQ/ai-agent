BEGIN;

WITH seed_spu(external_ref, title, brand, category_path, price_min, price_max, stock, description_md, image_urls) AS (
    VALUES
        ('CART-E2E-0001', '轻量通勤双肩包 14 寸防水', 'NorthFace', '服装/箱包/双肩包', 199.00, 199.00, 120,
         '面向都市通勤场景的轻量双肩包，主舱可放 14 寸笔记本与 A4 文件，外层 600D 涂层防泼面料，雨天短时通勤无需额外雨罩。背部 EVA 透气垫减压，适合 14 寸笔记本、A4 文件、雨天短时通勤和 1 到 3 天短差。',
         '["https://example.com/img/cart-e2e-0001.jpg"]'::jsonb),
        ('CART-E2E-0002', '城市通勤双肩包 15 寸大容量', 'Samsonite', '服装/箱包/双肩包', 259.00, 299.00, 75,
         '15 寸电脑仓，独立干湿分区，适合通勤、健身和短途出差。背负系统支撑稳定，前袋可收纳钥匙、公交卡、耳机和充电宝。',
         '["https://example.com/img/cart-e2e-0002.jpg"]'::jsonb),
        ('CART-E2E-0003', '户外防泼水双肩背包 20L', 'Columbia', '服装/箱包/双肩包', 329.00, 329.00, 36,
         '20L 户外背包，防泼水面料，胸扣和腰托减压，适合徒步、露营和周末短途旅行。侧袋可放水杯，主仓可容纳轻薄外套和摄影配件。',
         '["https://example.com/img/cart-e2e-0003.jpg"]'::jsonb),
        ('CART-E2E-0004', '纯棉宽松男士短袖 T 恤', 'Uniqlo', '服装/上衣/T恤', 89.00, 99.00, 600,
         '精梳长绒棉，吸湿透气，落肩宽版剪裁，黑白灰基础色适合日常通勤、家居和简约穿搭。机洗不易变形，圆领领口加密车线。',
         '["https://example.com/img/cart-e2e-0004.jpg"]'::jsonb),
        ('CART-E2E-0005', 'TWS 主动降噪蓝牙耳机长续航', 'Sony', '数码/影音/耳机', 999.00, 999.00, 200,
         '双麦克风主动降噪，蓝牙 5.3 双连接，单次 8 小时续航，含充电盒 32 小时。支持通勤降噪、视频会议和低延迟观影。',
         '["https://example.com/img/cart-e2e-0005.jpg"]'::jsonb),
        ('CART-E2E-0006', '便携笔记本电脑 14 寸轻薄办公', 'Lenovo', '数码/电脑/笔记本', 5499.00, 6299.00, 45,
         '14 寸 2.8K OLED 屏，轻薄办公本，适合移动办公、设计预览和文档处理。整机轻便，长续航，适合学生、程序员和经常出差的用户。',
         '["https://example.com/img/cart-e2e-0006.jpg"]'::jsonb),
        ('CART-E2E-0007', '氨基酸温和洁面乳油皮专用', 'Curel', '美妆/护肤/洁面', 119.00, 119.00, 300,
         '氨基酸表活体系，控油不紧绷，弱酸性配方，适合油皮和敏感肌日常洁面。无酒精、无香精，晨晚洁面都可以使用。',
         '["https://example.com/img/cart-e2e-0007.jpg"]'::jsonb),
        ('CART-E2E-0008', '纯物理防晒霜不含酒精敏感肌可用', 'Anessa', '美妆/护肤/防晒', 169.00, 189.00, 220,
         '氧化锌加二氧化钛纯物理防晒，SPF50+ PA++++，不含酒精，敏感肌可用。适合日常通勤、海边度假和户外补涂。',
         '["https://example.com/img/cart-e2e-0008.jpg"]'::jsonb),
        ('CART-E2E-0009', '玻尿酸保湿精华液干皮急救', 'The Ordinary', '美妆/护肤/精华', 89.00, 89.00, 400,
         '复配多分子玻尿酸和 B5 泛醇，补水保湿，秋冬干皮可叠涂。质地清爽不黏腻，可作妆前打底，也适合干燥季节急救保湿。',
         '["https://example.com/img/cart-e2e-0009.jpg"]'::jsonb),
        ('CART-E2E-0010', '不锈钢保温杯 500ml 便携通勤', 'Thermos', '家居/水具/保温杯', 129.00, 159.00, 180,
         '316 不锈钢内胆，500ml 容量，保温 12 小时，杯身轻量适合通勤。单手开盖，适合办公室、车载和日常随身携带。',
         '["https://example.com/img/cart-e2e-0010.jpg"]'::jsonb)
)
INSERT INTO public.catalog_spu (
    external_ref, title, brand, category_path, price_min, price_max, stock,
    description_md, images, video_url, attributes_json, attributes_status, status, updated_at
)
SELECT external_ref, title, brand, category_path, price_min, price_max, stock,
       description_md, image_urls, NULL, '{}'::jsonb, 'DONE', 'ACTIVE', now()
  FROM seed_spu
ON CONFLICT (external_ref) DO UPDATE
   SET title = EXCLUDED.title,
       brand = EXCLUDED.brand,
       category_path = EXCLUDED.category_path,
       price_min = EXCLUDED.price_min,
       price_max = EXCLUDED.price_max,
       stock = EXCLUDED.stock,
       description_md = EXCLUDED.description_md,
       images = EXCLUDED.images,
       attributes_json = EXCLUDED.attributes_json,
       attributes_status = 'DONE',
       status = 'ACTIVE',
       updated_at = now();

WITH seed_sku(external_ref, sku_code, spec_json, price, stock, status) AS (
    VALUES
        ('CART-E2E-0001', 'CART-E2E-0001-BLACK', '{"color":"黑色"}'::jsonb, 199.00, 80, 'ACTIVE'),
        ('CART-E2E-0001', 'CART-E2E-0001-NAVY', '{"color":"藏青"}'::jsonb, 199.00, 40, 'ACTIVE'),
        ('CART-E2E-0002', 'CART-E2E-0002-GREY', '{"color":"灰色"}'::jsonb, 259.00, 50, 'ACTIVE'),
        ('CART-E2E-0002', 'CART-E2E-0002-BLACK', '{"color":"黑色"}'::jsonb, 299.00, 25, 'ACTIVE'),
        ('CART-E2E-0003', 'CART-E2E-0003-GREEN', '{"color":"军绿"}'::jsonb, 329.00, 36, 'ACTIVE'),
        ('CART-E2E-0004', 'CART-E2E-0004-WHITE-M', '{"color":"白色","size":"M"}'::jsonb, 89.00, 200, 'ACTIVE'),
        ('CART-E2E-0004', 'CART-E2E-0004-BLACK-L', '{"color":"黑色","size":"L"}'::jsonb, 99.00, 200, 'ACTIVE'),
        ('CART-E2E-0005', 'CART-E2E-0005-BLACK', '{"color":"黑色"}'::jsonb, 999.00, 120, 'ACTIVE'),
        ('CART-E2E-0005', 'CART-E2E-0005-WHITE', '{"color":"白色"}'::jsonb, 999.00, 80, 'ACTIVE'),
        ('CART-E2E-0006', 'CART-E2E-0006-16-512', '{"memory":"16GB","storage":"512GB"}'::jsonb, 5499.00, 20, 'ACTIVE'),
        ('CART-E2E-0006', 'CART-E2E-0006-32-1T', '{"memory":"32GB","storage":"1TB"}'::jsonb, 6299.00, 25, 'ACTIVE'),
        ('CART-E2E-0007', 'CART-E2E-0007-150ML', '{"volume":"150ml"}'::jsonb, 119.00, 300, 'ACTIVE'),
        ('CART-E2E-0008', 'CART-E2E-0008-50ML', '{"volume":"50ml"}'::jsonb, 169.00, 120, 'ACTIVE'),
        ('CART-E2E-0008', 'CART-E2E-0008-90ML', '{"volume":"90ml"}'::jsonb, 189.00, 100, 'ACTIVE'),
        ('CART-E2E-0009', 'CART-E2E-0009-30ML', '{"volume":"30ml"}'::jsonb, 89.00, 400, 'ACTIVE'),
        ('CART-E2E-0010', 'CART-E2E-0010-500ML-BLUE', '{"volume":"500ml","color":"蓝色"}'::jsonb, 129.00, 120, 'ACTIVE'),
        ('CART-E2E-0010', 'CART-E2E-0010-500ML-BLACK', '{"volume":"500ml","color":"黑色"}'::jsonb, 159.00, 60, 'ACTIVE')
)
INSERT INTO public.catalog_sku (spu_id, sku_code, spec_json, price, stock, status, updated_at)
SELECT spu.id, sku.sku_code, sku.spec_json, sku.price, sku.stock, sku.status, now()
  FROM seed_sku sku
  JOIN public.catalog_spu spu ON spu.external_ref = sku.external_ref
ON CONFLICT (spu_id, sku_code) DO UPDATE
   SET spec_json = EXCLUDED.spec_json,
       price = EXCLUDED.price,
       stock = EXCLUDED.stock,
       status = EXCLUDED.status,
       updated_at = now();

WITH seed_spu(external_ref, title, brand, category_path, description_md) AS (
    VALUES
        ('CART-E2E-0001', '轻量通勤双肩包 14 寸防水', 'NorthFace', '服装/箱包/双肩包', '面向都市通勤场景的轻量双肩包，主舱可放 14 寸笔记本与 A4 文件，外层 600D 涂层防泼面料，雨天短时通勤无需额外雨罩。背部 EVA 透气垫减压，适合 14 寸笔记本、A4 文件、雨天短时通勤和 1 到 3 天短差。'),
        ('CART-E2E-0002', '城市通勤双肩包 15 寸大容量', 'Samsonite', '服装/箱包/双肩包', '15 寸电脑仓，独立干湿分区，适合通勤、健身和短途出差。背负系统支撑稳定，前袋可收纳钥匙、公交卡、耳机和充电宝。'),
        ('CART-E2E-0003', '户外防泼水双肩背包 20L', 'Columbia', '服装/箱包/双肩包', '20L 户外背包，防泼水面料，胸扣和腰托减压，适合徒步、露营和周末短途旅行。侧袋可放水杯，主仓可容纳轻薄外套和摄影配件。'),
        ('CART-E2E-0004', '纯棉宽松男士短袖 T 恤', 'Uniqlo', '服装/上衣/T恤', '精梳长绒棉，吸湿透气，落肩宽版剪裁，黑白灰基础色适合日常通勤、家居和简约穿搭。机洗不易变形，圆领领口加密车线。'),
        ('CART-E2E-0005', 'TWS 主动降噪蓝牙耳机长续航', 'Sony', '数码/影音/耳机', '双麦克风主动降噪，蓝牙 5.3 双连接，单次 8 小时续航，含充电盒 32 小时。支持通勤降噪、视频会议和低延迟观影。'),
        ('CART-E2E-0006', '便携笔记本电脑 14 寸轻薄办公', 'Lenovo', '数码/电脑/笔记本', '14 寸 2.8K OLED 屏，轻薄办公本，适合移动办公、设计预览和文档处理。整机轻便，长续航，适合学生、程序员和经常出差的用户。'),
        ('CART-E2E-0007', '氨基酸温和洁面乳油皮专用', 'Curel', '美妆/护肤/洁面', '氨基酸表活体系，控油不紧绷，弱酸性配方，适合油皮和敏感肌日常洁面。无酒精、无香精，晨晚洁面都可以使用。'),
        ('CART-E2E-0008', '纯物理防晒霜不含酒精敏感肌可用', 'Anessa', '美妆/护肤/防晒', '氧化锌加二氧化钛纯物理防晒，SPF50+ PA++++，不含酒精，敏感肌可用。适合日常通勤、海边度假和户外补涂。'),
        ('CART-E2E-0009', '玻尿酸保湿精华液干皮急救', 'The Ordinary', '美妆/护肤/精华', '复配多分子玻尿酸和 B5 泛醇，补水保湿，秋冬干皮可叠涂。质地清爽不黏腻，可作妆前打底，也适合干燥季节急救保湿。'),
        ('CART-E2E-0010', '不锈钢保温杯 500ml 便携通勤', 'Thermos', '家居/水具/保温杯', '316 不锈钢内胆，500ml 容量，保温 12 小时，杯身轻量适合通勤。单手开盖，适合办公室、车载和日常随身携带。')
),
document_payload AS (
    SELECT external_ref,
           title,
           'catalog-spu' AS source_type,
           'catalog://spu/' || external_ref AS source_uri,
           title || E'\n品牌：' || brand || E'\n类目：' || category_path || E'\n' || description_md AS content,
           jsonb_build_object('seed', 'cart-e2e', 'externalRef', external_ref, 'brand', brand, 'category', category_path) AS metadata
      FROM seed_spu
),
updated_docs AS (
    UPDATE public.rag_documents d
       SET title = p.title,
           source_uri = p.source_uri,
           content = p.content,
           content_sha256 = lpad(md5(p.content), 64, '0'),
           status = 'INDEXED',
           indexed_generation = 1,
           chunk_count = 1,
           indexed_at = now(),
           metadata = p.metadata,
           updated_at = now()
      FROM document_payload p
     WHERE d.source_type = p.source_type
       AND d.external_ref = p.external_ref
    RETURNING d.id, d.external_ref
),
inserted_docs AS (
    INSERT INTO public.rag_documents (
        source_type, source_uri, external_ref, title, content, content_sha256,
        indexed_generation, status, chunk_count, indexed_at, metadata, updated_at
    )
    SELECT p.source_type, p.source_uri, p.external_ref, p.title, p.content,
           lpad(md5(p.content), 64, '0'), 1, 'INDEXED', 1, now(), p.metadata, now()
      FROM document_payload p
     WHERE NOT EXISTS (
           SELECT 1
             FROM public.rag_documents d
            WHERE d.source_type = p.source_type
              AND d.external_ref = p.external_ref
     )
    RETURNING id, external_ref
),
all_docs AS (
    SELECT id, external_ref FROM updated_docs
    UNION ALL
    SELECT id, external_ref FROM inserted_docs
),
selected_docs AS (
    SELECT min(id) AS id, external_ref
      FROM all_docs
     GROUP BY external_ref
),
chunk_payload AS (
    SELECT d.id AS document_id,
           d.external_ref,
           1::bigint AS index_generation,
           0 AS chunk_index,
           p.content AS chunk_text,
           lpad(md5(p.content), 64, '0') AS chunk_hash,
           char_length(p.content) AS char_count,
           jsonb_build_object(
               'blockType', 'DESC',
               'chunkType', 'DESC',
               'headingPath', jsonb_build_array(p.title),
               'headingPathText', p.title,
               'documentTags', jsonb_build_array('catalog-spu', 'cart-e2e')
           ) AS metadata
      FROM selected_docs d
      JOIN document_payload p ON p.external_ref = d.external_ref
),
upsert_chunks AS (
    INSERT INTO public.rag_chunks (
        document_id, index_generation, chunk_index, chunk_text, chunk_hash,
        char_count, token_count, vector_id, metadata, updated_at
    )
    SELECT document_id, index_generation, chunk_index, chunk_text, chunk_hash,
           char_count, NULL, NULL, metadata, now()
      FROM chunk_payload
    ON CONFLICT (document_id, index_generation, chunk_index) DO UPDATE
       SET chunk_text = EXCLUDED.chunk_text,
           chunk_hash = EXCLUDED.chunk_hash,
           char_count = EXCLUDED.char_count,
           token_count = EXCLUDED.token_count,
           vector_id = EXCLUDED.vector_id,
           metadata = EXCLUDED.metadata,
           updated_at = now()
    RETURNING document_id
)
UPDATE public.catalog_spu spu
   SET document_id = d.id,
       updated_at = now()
  FROM selected_docs d
 WHERE spu.external_ref = d.external_ref;

UPDATE public.rag_chunks c
   SET metadata = jsonb_set(c.metadata, '{blockType}', '"DESC"'::jsonb, true),
       updated_at = now()
  FROM public.rag_documents d
 WHERE d.id = c.document_id
   AND d.source_type = 'catalog-spu'
   AND d.external_ref LIKE 'CART-E2E-%';

COMMIT;

SELECT 'seeded catalog SPUs' AS label, count(*) AS count
  FROM public.catalog_spu
 WHERE external_ref LIKE 'CART-E2E-%'
UNION ALL
SELECT 'seeded catalog SKUs' AS label, count(*) AS count
  FROM public.catalog_sku sku
  JOIN public.catalog_spu spu ON spu.id = sku.spu_id
 WHERE spu.external_ref LIKE 'CART-E2E-%'
UNION ALL
SELECT 'seeded rag documents' AS label, count(*) AS count
  FROM public.rag_documents
 WHERE source_type = 'catalog-spu'
   AND external_ref LIKE 'CART-E2E-%'
UNION ALL
SELECT 'seeded rag chunks' AS label, count(*) AS count
  FROM public.rag_chunks c
  JOIN public.rag_documents d ON d.id = c.document_id
 WHERE d.source_type = 'catalog-spu'
   AND d.external_ref LIKE 'CART-E2E-%';
