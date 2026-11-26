package com.group33.server.auth

import com.group33.server.config.PersistenceRuntime
import java.time.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import javax.sql.DataSource

internal class BoundPhoneInfrastructure(val primary: DataSource, val clock: Clock,
                                       val identity: BoundPhoneIdentityStore, val jwt: BoundPhoneJwt) {
    override fun toString() = "BoundPhoneInfrastructure(redacted)"
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.bound-phone.enabled"], havingValue = "true")
internal class BoundPhoneRuntimeConfiguration {
    @Bean fun boundPhoneRuntimeSettings(env: Environment) = BoundPhoneRuntimeSettings.read(env)

    @Bean fun boundPhoneInfrastructure(settings: BoundPhoneRuntimeSettings, runtime: PersistenceRuntime): BoundPhoneInfrastructure {
        val source = checkNotNull(runtime.primary) { "Phone authentication requires the managed primary database" }
        BoundPhoneSchemaGate.validate(source)
        val clock = Clock.systemUTC()
        return BoundPhoneInfrastructure(source, clock, BoundPhoneIdentityStore(source),
            BoundPhoneJwt(settings.jwtKey, settings.issuer, settings.audience, clock, settings.allowanceSeconds))
    }

    @Bean @ConditionalOnMissingBean(BoundPhoneProvider::class)
    fun boundPhoneProvider(settings: BoundPhoneRuntimeSettings, infrastructure: BoundPhoneInfrastructure): BoundPhoneProvider =
        AlibabaBoundPhoneProvider(settings.accessKeyId, settings.accessKeySecret, settings.scheme,
            settings.packageName, settings.packageSignature, infrastructure.clock)

    @Bean fun boundPhoneAuthentication(settings: BoundPhoneRuntimeSettings, infrastructure: BoundPhoneInfrastructure,
                                      provider: BoundPhoneProvider) = BoundPhoneAuthentication(infrastructure.identity,
        provider, infrastructure.jwt, infrastructure.clock, settings.scheme, settings.phoneHashKey, settings.sessionSeconds)

    @Bean fun boundPhoneRateLimit(settings: BoundPhoneRuntimeSettings): BoundPhoneRateLimit = settings.rateLimit
    @Bean fun boundPhoneJwt(infrastructure: BoundPhoneInfrastructure) = infrastructure.jwt
    @Bean fun boundPhoneIdentityStore(infrastructure: BoundPhoneInfrastructure) = infrastructure.identity

}
