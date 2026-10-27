package com.group33.server.pairing

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class PairingCodeHttpConfiguration {
    @Bean fun pairingCodeClock(): Clock = Clock.systemUTC()
    @Bean fun pairingCodeGenerator(): PairingCodeGenerator = SecurePairingCodeGenerator()
    @Bean fun pairingCodeStore(): PairingCodeStore = InMemoryPairingCodeStore()
    @Bean fun pairingCodeIssuanceService(
        store: PairingCodeStore,
        clock: Clock,
        generator: PairingCodeGenerator,
    ) = PairingCodeIssuanceService(store, clock, generator)
}
