CREATE TABLE IF NOT EXISTS public.pending_cart_actions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL,
    product_name VARCHAR(1024),
    quantity INTEGER,
    candidates JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_cart_user_conv ON public.pending_cart_actions(user_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_pending_cart_status ON public.pending_cart_actions(status);
CREATE INDEX IF NOT EXISTS idx_pending_cart_expire ON public.pending_cart_actions(expire_at);
