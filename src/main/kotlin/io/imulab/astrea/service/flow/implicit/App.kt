package io.imulab.astrea.service.flow.implicit

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannelBuilder
import io.imulab.astrea.sdk.discovery.RemoteDiscoveryService
import io.imulab.astrea.sdk.discovery.SampleDiscovery
import io.imulab.astrea.sdk.oauth.handler.OAuthImplicitHandler
import io.imulab.astrea.sdk.oauth.handler.helper.AccessTokenHelper
import io.imulab.astrea.sdk.oauth.token.JwtSigningAlgorithm
import io.imulab.astrea.sdk.oauth.token.strategy.AccessTokenStrategy
import io.imulab.astrea.sdk.oauth.token.strategy.JwtAccessTokenStrategy
import io.imulab.astrea.sdk.oauth.validation.*
import io.imulab.astrea.sdk.oidc.discovery.Discovery
import io.imulab.astrea.sdk.oidc.handler.OidcImplicitHandler
import io.imulab.astrea.sdk.oidc.token.IdTokenStrategy
import io.imulab.astrea.sdk.oidc.token.JwxIdTokenStrategy
import io.imulab.astrea.sdk.oidc.validation.NonceValidator
import io.imulab.astrea.sdk.oidc.validation.OidcResponseTypeValidator
import io.vertx.core.Vertx
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.kotlin.coroutines.awaitResult
import kotlinx.coroutines.runBlocking
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.eagerSingleton
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("io.imulab.astrea.service.flow.implicit.AppKt")

suspend fun main(args: Array<String>) {

    val vertx = Vertx.vertx()
    val config = ConfigFactory.load()

    val components = App(vertx, config).bootstrap()
    val gateway by components.instance<GrpcVerticle>()

    try {
        val deploymentId = awaitResult<String> { vertx.deployVerticle(gateway, it) }
        io.imulab.astrea.service.logger.info("Implicit flow service successfully deployed with id {}", deploymentId)
    } catch (e: Exception) {
        io.imulab.astrea.service.logger.error("Implicit flow service encountered error during deployment.", e)
    }

    val healthVerticle by components.instance<HealthVerticle>()
    try {
        awaitResult<String> { vertx.deployVerticle(healthVerticle, it) }
        io.imulab.astrea.service.logger.info("Implicit flow service health information available.")
    } catch (e: Exception) {
        io.imulab.astrea.service.logger.error("Implicit flow service health information unavailable.", e)
    }
}

@Suppress("MemberVisibilityCanBePrivate")
open class App(private val vertx: Vertx, private val config: Config) {

    open fun bootstrap(): Kodein {
        return Kodein {
            importOnce(discovery)
            importOnce(app)

            bind<GrpcVerticle>() with singleton {
                GrpcVerticle(flowService = instance(), appConfig = config, healthCheckHandler = instance())
            }

            bind<HealthVerticle>() with singleton {
                HealthVerticle(healthCheckHandler = instance(), appConfig = config)
            }
        }
    }

    val app = Kodein.Module("app") {
        bind<HealthCheckHandler>() with singleton { HealthCheckHandler.create(vertx) }

        bind<ServiceContext>() with singleton { ServiceContext(discovery = instance(), config = config) }

        bind<AccessTokenStrategy>() with singleton {
            JwtAccessTokenStrategy(
                oauthContext = instance(),
                serverJwks = instance<ServiceContext>().masterJsonWebKeySet,
                signingAlgorithm = JwtSigningAlgorithm.RS256
            )
        }

        bind<AccessTokenHelper>() with singleton {
            AccessTokenHelper(
                oauthContext = instance(),
                accessTokenRepository = NoOpAccessTokenRepository,
                accessTokenStrategy = instance()
            )
        }

        bind<IdTokenStrategy>() with singleton {
            JwxIdTokenStrategy(
                oidcContext = instance(),
                jsonWebKeySetStrategy = LocalJsonWebKeySetStrategy(instance<ServiceContext>().masterJsonWebKeySet)
            )
        }

        bind<OAuthImplicitHandler>() with singleton {
            OAuthImplicitHandler(
                oauthContext = instance(),
                accessTokenStrategy = instance(),
                accessTokenRepository = NoOpAccessTokenRepository
            )
        }

        bind<OidcImplicitHandler>() with singleton {
            OidcImplicitHandler(
                accessTokenHelper = instance(),
                idTokenStrategy = instance()
            )
        }

        bind<OAuthRequestValidationChain>() with singleton {
            OAuthRequestValidationChain(listOf(
                StateValidator(instance()),
                NonceValidator(instance()),
                ScopeValidator,
                GrantedScopeValidator,
                RedirectUriValidator,
                OidcResponseTypeValidator
            ))
        }

        bind<ImplicitFlowService>() with singleton {
            ImplicitFlowService(
                authorizeHandlers = listOf(
                    instance<OAuthImplicitHandler>(),
                    instance<OidcImplicitHandler>()
                ),
                authorizeValidation = instance()
            )
        }
    }

    val discovery = Kodein.Module("discovery") {
        bind<Discovery>() with eagerSingleton {
            if (config.getBoolean("discovery.useSample")) {
                logger.info("Using sample discovery.")
                SampleDiscovery.default()
            } else {
                runBlocking {
                    RemoteDiscoveryService(
                        ManagedChannelBuilder.forAddress(
                            config.getString("discovery.host"),
                            config.getInt("discovery.port")
                        ).enableRetry().maxRetryAttempts(10).usePlaintext().build()
                    ).getDiscovery()
                }.also { logger.info("Acquired discovery from remote.") }
            }
        }
    }
}