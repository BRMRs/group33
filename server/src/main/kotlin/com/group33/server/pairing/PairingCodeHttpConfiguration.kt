package com.group33.server.pairing

import com.group33.server.config.PersistenceRuntime
import com.group33.server.plan.PlanMutationStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class PairingCodeHttpConfiguration {
    @Bean fun pairingCodeClock(): Clock = Clock.systemUTC()
    @Bean fun pairingCodeGenerator(): PairingCodeGenerator = SecurePairingCodeGenerator()
    @Bean fun pairingCodeStore(runtime: PersistenceRuntime): PlanMutationStore = runtime.pairing
    @Bean fun pairingDirectoryService(store: PairingDirectoryStore, clock: Clock) = PairingDirectoryService(store, clock)
    @Bean fun pairingRequestService(store: PairingRequestStore, clock: Clock) = PairingRequestService(store, clock)
    @Bean fun pairingDecisionService(store: PairingDecisionStore, clock: Clock) = PairingDecisionService(store, clock)
    @Bean fun pairingCodeIssuanceService(
        store: PairingCodeStore,
        clock: Clock,
        generator: PairingCodeGenerator,
    ) = PairingCodeIssuanceService(store, clock, generator)
}
