package io.imulab.astrea.service.flow.implicit

import com.typesafe.config.Config
import io.grpc.stub.StreamObserver
import io.imulab.astrea.sdk.commons.flow.implicit.ImplicitFlowServiceGrpc
import io.imulab.astrea.sdk.commons.flow.implicit.ImplicitTokenRequest
import io.imulab.astrea.sdk.commons.flow.implicit.ImplicitTokenResponse
import io.imulab.astrea.sdk.commons.toFailure
import io.imulab.astrea.sdk.flow.implicit.toImplicitTokenResponse
import io.imulab.astrea.sdk.flow.implicit.toOidcAuthorizeRequest
import io.imulab.astrea.sdk.oauth.error.OAuthException
import io.imulab.astrea.sdk.oauth.error.ServerError
import io.imulab.astrea.sdk.oauth.handler.AuthorizeRequestHandler
import io.imulab.astrea.sdk.oauth.validation.OAuthRequestValidationChain
import io.imulab.astrea.sdk.oidc.response.OidcAuthorizeEndpointResponse
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.Router
import io.vertx.grpc.VertxServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class GrpcVerticle(
    private val flowService: ImplicitFlowService,
    private val appConfig: Config,
    private val healthCheckHandler: HealthCheckHandler
) : AbstractVerticle() {

    private val logger = LoggerFactory.getLogger(GrpcVerticle::class.java)

    override fun start(startFuture: Future<Void>?) {
        val server = VertxServerBuilder
            .forPort(vertx, appConfig.getInt("service.port"))
            .addService(flowService)
            .build()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            logger.info("implicit flow gRPC verticle shutting down...")
            server.shutdown()
            server.awaitTermination(10, TimeUnit.SECONDS)
        })

        server.start { ar ->
            if (ar.failed()) {
                logger.error("implicit flow gRPC verticle failed to start.", ar.cause())
            } else {
                startFuture?.complete()
                logger.info("implicit flow gRPC verticle started...")
            }
        }

        healthCheckHandler.register("implicit_flow_grpc_api") { h ->
            if (server.isTerminated)
                h.complete(Status.KO())
            else
                h.complete(Status.OK())
        }
    }
}

class HealthVerticle(
    private val healthCheckHandler: HealthCheckHandler,
    private val appConfig: Config
) : AbstractVerticle() {
    override fun start() {
        val router = Router.router(vertx)
        router.get("/health").handler(healthCheckHandler)
        vertx.createHttpServer(HttpServerOptions().apply {
            port = appConfig.getInt("service.healthPort")
        }).requestHandler(router).listen()
    }
}

class ImplicitFlowService(
    private val concurrency: Int = 4,
    private val authorizeHandlers: List<AuthorizeRequestHandler>,
    private val authorizeValidation: OAuthRequestValidationChain
) : ImplicitFlowServiceGrpc.ImplicitFlowServiceImplBase(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()

    override fun authorize(request: ImplicitTokenRequest?, responseObserver: StreamObserver<ImplicitTokenResponse>?) {
        if (request == null || responseObserver == null)
            return

        val job = Job()
        val authorizeRequest = request.toOidcAuthorizeRequest()
        val authorizeResponse = OidcAuthorizeEndpointResponse()

        launch(job) {
            authorizeValidation.validate(authorizeRequest)
            authorizeHandlers.forEach { h ->
                h.handleAuthorizeRequest(authorizeRequest, authorizeResponse)
            }
            if (!authorizeResponse.handledResponseTypes.containsAll(authorizeRequest.responseTypes))
                throw ServerError.internal("Some response types were not handled.")
        }.invokeOnCompletion { t ->
            if (t != null) {
                job.cancel()
                val e: OAuthException = if (t is OAuthException) t else ServerError.wrapped(t)
                responseObserver.onNext(
                    ImplicitTokenResponse.newBuilder()
                        .setSuccess(false)
                        .setFailure(e.toFailure())
                        .build()
                )
            } else {
                responseObserver.onNext(authorizeResponse.toImplicitTokenResponse())
            }
            responseObserver.onCompleted()
        }
    }
}