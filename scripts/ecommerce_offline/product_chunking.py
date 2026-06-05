from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


DEFAULT_STOCK = 100
CATEGORY_ALIASES = {
    "食品生活": "食品饮料",
}


@dataclass(frozen=True)
class ProductChunk:
    chunk_id: str
    product_id: str
    parent_chunk_id: str | None
    chunk_level: str
    chunk_type: str
    chunk_index: int
    text_content: str | None
    content_sha256: str | None
    embedding_required: bool
    embedding_modality: str
    source_ref: dict[str, Any]
    metadata: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def load_products(dataset_root: Path) -> list[tuple[Path, dict[str, Any]]]:
    if not dataset_root.is_dir():
        raise FileNotFoundError(f"dataset root does not exist: {dataset_root}")
    products: list[tuple[Path, dict[str, Any]]] = []
    for path in sorted(dataset_root.rglob("*.json"), key=lambda item: str(item.relative_to(dataset_root))):
        with path.open("r", encoding="utf-8") as file:
            products.append((path, json.load(file)))
    if not products:
        raise ValueError(f"dataset root has no product json files: {dataset_root}")
    return products


def normalize_category(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    category = value.strip()
    return CATEGORY_ALIASES.get(category, category)


def category_path(product: dict[str, Any]) -> str | None:
    category = normalize_category(product.get("category"))
    sub_category = product.get("sub_category")
    sub_category = sub_category.strip() if isinstance(sub_category, str) and sub_category.strip() else None
    if category is None:
        return sub_category
    if sub_category is None or sub_category == category:
        return category
    return f"{category}/{sub_category}"


def product_prices(product: dict[str, Any]) -> tuple[Any, Any]:
    prices = [
        sku.get("price")
        for sku in product.get("skus", [])
        if sku is not None and sku.get("price") is not None
    ]
    if not prices:
        return product.get("base_price"), product.get("base_price")
    return min(prices), max(prices)


def product_stock_total(product: dict[str, Any]) -> int:
    return sum(int(sku.get("stock", DEFAULT_STOCK)) for sku in product.get("skus", []) if isinstance(sku, dict))


def sentiment(rating: Any) -> str:
    if not isinstance(rating, int):
        return "unknown"
    if rating >= 4:
        return "positive"
    if rating <= 2:
        return "negative"
    return "mixed"


def sha256(text: str | None) -> str | None:
    if text is None:
        return None
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def chunk_id(product_id: str, chunk_type: str, index: int) -> str:
    return f"{product_id}:{chunk_type}:{index}"


def compact_text(text: str | None, limit: int = 220) -> str:
    if text is None:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit].rstrip() + "..."


def build_product_chunks(product: dict[str, Any]) -> list[ProductChunk]:
    validate_product(product)
    product_id = product["product_id"]
    parent_id = chunk_id(product_id, "product_parent", 0)
    knowledge = product.get("rag_knowledge") or {}
    chunks = [build_parent_chunk(product, parent_id)]
    chunks.extend(build_profile_chunk(product, parent_id, knowledge))
    chunks.extend(build_marketing_chunks(product, parent_id, knowledge))
    chunks.extend(build_faq_chunks(product, parent_id, knowledge))
    chunks.extend(build_review_chunks(product, parent_id, knowledge))
    chunks.extend(build_review_summary_chunk(product, parent_id, knowledge))
    chunks.extend(build_image_chunks(product, parent_id))
    return chunks


def build_parent_chunk(product: dict[str, Any], parent_id: str) -> ProductChunk:
    product_id = product["product_id"]
    metadata = base_metadata(product) | {
        "chunkLevel": "parent",
        "chunkType": "product_parent",
    }
    return ProductChunk(
        chunk_id=parent_id,
        product_id=product_id,
        parent_chunk_id=None,
        chunk_level="parent",
        chunk_type="product_parent",
        chunk_index=0,
        text_content=None,
        content_sha256=None,
        embedding_required=False,
        embedding_modality="none",
        source_ref={"field": "product"},
        metadata=metadata,
    )


def build_profile_chunk(product: dict[str, Any], parent_id: str, knowledge: dict[str, Any]) -> list[ProductChunk]:
    product_id = product["product_id"]
    price_min, price_max = product_prices(product)
    text = "\n".join(
        part
        for part in [
            f"# {product.get('title')}",
            f"品牌：{product.get('brand')}" if product.get("brand") else None,
            f"类目：{category_path(product)}" if category_path(product) else None,
            f"价格区间：{price_min} ~ {price_max}" if price_min != price_max else f"价格：{price_min}",
            f"图片：{product.get('image_path')}" if product.get("image_path") else None,
            "",
            f"商品摘要：{compact_text(knowledge.get('marketing_description'))}",
        ]
        if part is not None
    ).strip()
    return [
        text_chunk(
            product,
            parent_id,
            "product_profile",
            0,
            text,
            {"field": "product_profile"},
            {},
        )
    ]


def build_marketing_chunks(product: dict[str, Any], parent_id: str, knowledge: dict[str, Any]) -> list[ProductChunk]:
    marketing = knowledge.get("marketing_description")
    if not marketing:
        return []
    return [
        text_chunk(
            product,
            parent_id,
            "marketing_description",
            0,
            "## 营销描述\n\n" + marketing.strip(),
            {"field": "rag_knowledge.marketing_description"},
            {},
        )
    ]


def build_faq_chunks(product: dict[str, Any], parent_id: str, knowledge: dict[str, Any]) -> list[ProductChunk]:
    chunks: list[ProductChunk] = []
    for index, faq in enumerate(knowledge.get("official_faq") or []):
        question = (faq.get("question") or "").strip()
        answer = (faq.get("answer") or "").strip()
        if not question and not answer:
            continue
        chunks.append(
            text_chunk(
                product,
                parent_id,
                "official_faq",
                index,
                f"## 官方 FAQ\n\n问题：{question}\n回答：{answer}".strip(),
                {"field": "rag_knowledge.official_faq", "index": index},
                {"question": question},
            )
        )
    return chunks


def build_review_chunks(product: dict[str, Any], parent_id: str, knowledge: dict[str, Any]) -> list[ProductChunk]:
    chunks: list[ProductChunk] = []
    for index, review in enumerate(knowledge.get("user_reviews") or []):
        content = (review.get("content") or "").strip()
        if not content:
            continue
        rating = review.get("rating")
        text = "\n".join(
            [
                "## 用户评价",
                "",
                f"用户：{review.get('nickname') or ''}",
                f"评分：{rating if rating is not None else ''}",
                f"内容：{content}",
            ]
        ).strip()
        chunks.append(
            text_chunk(
                product,
                parent_id,
                "user_review",
                index,
                text,
                {"field": "rag_knowledge.user_reviews", "index": index},
                {"rating": rating, "sentiment": sentiment(rating)},
            )
        )
    return chunks


def build_review_summary_chunk(product: dict[str, Any], parent_id: str, knowledge: dict[str, Any]) -> list[ProductChunk]:
    ratings = [
        review.get("rating")
        for review in knowledge.get("user_reviews") or []
        if isinstance(review.get("rating"), int)
    ]
    if not ratings:
        return []
    average = round(sum(ratings) / len(ratings), 2)
    distribution = {str(score): ratings.count(score) for score in range(1, 6)}
    text = "\n".join(
        [
            "## 用户评价摘要",
            "",
            f"评价数量：{len(ratings)}",
            f"平均评分：{average}",
            "评分分布：" + json.dumps(distribution, ensure_ascii=False, separators=(",", ":")),
        ]
    )
    return [
        text_chunk(
            product,
            parent_id,
            "review_summary",
            0,
            text,
            {"field": "rag_knowledge.user_reviews"},
            {
                "reviewCount": len(ratings),
                "averageRating": average,
                "ratingDistribution": distribution,
            },
        )
    ]


def build_image_chunks(product: dict[str, Any], parent_id: str) -> list[ProductChunk]:
    image_path = product.get("image_path")
    if not image_path:
        return []
    product_id = product["product_id"]
    return [
        ProductChunk(
            chunk_id=chunk_id(product_id, "image_embedding", 0),
            product_id=product_id,
            parent_chunk_id=parent_id,
            chunk_level="child",
            chunk_type="image_embedding",
            chunk_index=0,
            text_content=None,
            content_sha256=None,
            embedding_required=True,
            embedding_modality="image",
            source_ref={"field": "image_path", "imagePath": image_path},
            metadata=base_metadata(product)
            | {
                "chunkLevel": "child",
                "chunkType": "image_embedding",
                "parentId": product_id,
                "imagePath": image_path,
            },
        )
    ]


def text_chunk(
    product: dict[str, Any],
    parent_id: str,
    chunk_type: str,
    index: int,
    text: str,
    source_ref: dict[str, Any],
    metadata: dict[str, Any],
) -> ProductChunk:
    product_id = product["product_id"]
    return ProductChunk(
        chunk_id=chunk_id(product_id, chunk_type, index),
        product_id=product_id,
        parent_chunk_id=parent_id,
        chunk_level="child",
        chunk_type=chunk_type,
        chunk_index=index,
        text_content=text,
        content_sha256=sha256(text),
        embedding_required=True,
        embedding_modality="text",
        source_ref=source_ref,
        metadata=base_metadata(product)
        | {
            "chunkLevel": "child",
            "chunkType": chunk_type,
            "parentId": product_id,
        }
        | metadata,
    )


def base_metadata(product: dict[str, Any]) -> dict[str, Any]:
    product_id = product["product_id"]
    price_min, price_max = product_prices(product)
    return {
        "productId": product_id,
        "externalRef": product_id,
        "title": product.get("title"),
        "brand": product.get("brand"),
        "category": normalize_category(product.get("category")),
        "subCategory": product.get("sub_category"),
        "categoryPath": category_path(product),
        "priceMin": price_min,
        "priceMax": price_max,
        "stock": product_stock_total(product),
        "imagePath": product.get("image_path"),
    }


def validate_product(product: dict[str, Any]) -> None:
    if not product.get("product_id"):
        raise ValueError("product_id is required")
    if not product.get("title"):
        raise ValueError(f"title is required: {product.get('product_id')}")


def write_jsonl(chunks: list[ProductChunk], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as file:
        for chunk in chunks:
            file.write(json.dumps(chunk.to_dict(), ensure_ascii=False, separators=(",", ":")))
            file.write("\n")


def summarize(chunks: list[ProductChunk]) -> dict[str, Any]:
    by_type: dict[str, int] = {}
    by_modality: dict[str, int] = {}
    for chunk in chunks:
        by_type[chunk.chunk_type] = by_type.get(chunk.chunk_type, 0) + 1
        if chunk.embedding_required:
            by_modality[chunk.embedding_modality] = by_modality.get(chunk.embedding_modality, 0) + 1
    return {
        "chunk_count": len(chunks),
        "embedding_required_count": sum(1 for chunk in chunks if chunk.embedding_required),
        "text_embedding_count": sum(1 for chunk in chunks if chunk.embedding_required and chunk.embedding_modality == "text"),
        "image_embedding_count": sum(1 for chunk in chunks if chunk.embedding_required and chunk.embedding_modality == "image"),
        "by_chunk_type": dict(sorted(by_type.items())),
        "by_embedding_modality": dict(sorted(by_modality.items())),
    }


def build_all_chunks(dataset_root: Path) -> list[ProductChunk]:
    chunks: list[ProductChunk] = []
    for _, product in load_products(dataset_root):
        chunks.extend(build_product_chunks(product))
    return chunks
