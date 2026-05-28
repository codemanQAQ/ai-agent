package com.bytedance.ai.graph.cart.workflow;

public class CartWorkflowException extends RuntimeException {

    public CartWorkflowException(String message) {
        super(message);
    }

    public CartWorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
