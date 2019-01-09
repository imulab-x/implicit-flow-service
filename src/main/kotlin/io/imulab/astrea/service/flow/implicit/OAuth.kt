package io.imulab.astrea.service.flow.implicit

import com.typesafe.config.Config
import io.imulab.astrea.sdk.commons.doNotCall
import io.imulab.astrea.sdk.oauth.request.OAuthRequest
import io.imulab.astrea.sdk.oauth.reserved.AuthenticationMethod
import io.imulab.astrea.sdk.oauth.token.storage.AccessTokenRepository
import io.imulab.astrea.sdk.oidc.discovery.Discovery
import io.imulab.astrea.sdk.oidc.discovery.OidcContext
import io.imulab.astrea.sdk.oidc.jwk.JsonWebKeySetRepository
import io.imulab.astrea.sdk.oidc.jwk.JsonWebKeySetStrategy
import io.imulab.astrea.sdk.oidc.spi.HttpResponse
import io.imulab.astrea.sdk.oidc.spi.SimpleHttpClient
import org.jose4j.jwk.JsonWebKeySet
import java.time.Duration

class ServiceContext(
    private val discovery: Discovery,
    config: Config
) : OidcContext, Discovery by discovery {
    override val idTokenLifespan: Duration = config.getDuration("service.idTokenLifespan")
    override val masterJsonWebKeySet: JsonWebKeySet = JsonWebKeySet(config.getString("service.jwks"))
    override val nonceEntropy: Int = config.getInt("service.nonceEntropy")
    override val issuerUrl: String = discovery.issuer
    override val authorizeEndpointUrl: String = discovery.authorizationEndpoint
    override val tokenEndpointUrl: String = discovery.tokenEndpoint
    override val defaultTokenEndpointAuthenticationMethod: String = AuthenticationMethod.clientSecretBasic
    override val authorizeCodeLifespan: Duration = Duration.ZERO
    override val accessTokenLifespan: Duration = config.getDuration("service.accessTokenLifespan")
    override val refreshTokenLifespan: Duration = Duration.ZERO
    override val stateEntropy: Int = config.getInt("service.stateEntropy")

    override fun validate() { super<OidcContext>.validate() }
}

object NoOpAccessTokenRepository : AccessTokenRepository {
    override suspend fun createAccessTokenSession(token: String, request: OAuthRequest) {}
    override suspend fun getAccessTokenSession(token: String): OAuthRequest = doNotCall()
    override suspend fun deleteAccessTokenSession(token: String) {}
    override suspend fun deleteAccessTokenAssociatedWithRequest(requestId: String) {}
}

class LocalJsonWebKeySetStrategy(serverJwks: JsonWebKeySet) : JsonWebKeySetStrategy(
    httpClient = object : SimpleHttpClient { override suspend fun get(url: String): HttpResponse = doNotCall() },
    jsonWebKeySetRepository = object : JsonWebKeySetRepository {
        override suspend fun getServerJsonWebKeySet(): JsonWebKeySet = serverJwks
        override suspend fun getClientJsonWebKeySet(jwksUri: String): JsonWebKeySet? = null
        override suspend fun writeClientJsonWebKeySet(jwksUri: String, keySet: JsonWebKeySet) {}
    }
)