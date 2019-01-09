package io.imulab.astrea.service

import com.typesafe.config.ConfigFactory
import io.imulab.astrea.service.flow.implicit.App
import io.imulab.astrea.service.flow.implicit.GrpcVerticle
import io.imulab.astrea.service.flow.implicit.HealthVerticle
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.awaitResult
import org.kodein.di.generic.instance
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal val logger: Logger = LoggerFactory.getLogger("io.imulab.astrea.service.Main")

suspend fun main(args: Array<String>) {

}