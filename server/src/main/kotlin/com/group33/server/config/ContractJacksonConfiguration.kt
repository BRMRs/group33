package com.group33.server.config

import com.fasterxml.jackson.databind.DeserializationFeature
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
    }
}
