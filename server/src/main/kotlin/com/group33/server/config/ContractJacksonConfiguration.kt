package com.group33.server.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Keep transport decoding aligned with the Shared wire contract, not silent primitive defaults. */
@Configuration
class ContractJacksonConfiguration {
    @Bean
    fun strictContractDecoding() = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.featuresToEnable(
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        )
        builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        builder.postConfigurer { mapper ->
            // Shared requires JSON strings; numeric coercion can discard identity formatting.
            mapper.coercionConfigFor(String::class.java)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        }
    }
}
