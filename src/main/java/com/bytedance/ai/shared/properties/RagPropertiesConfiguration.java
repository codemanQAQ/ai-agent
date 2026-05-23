package com.bytedance.ai.shared.properties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers shared RAG configuration properties wherever the shared module is loaded.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
class RagPropertiesConfiguration {
}
