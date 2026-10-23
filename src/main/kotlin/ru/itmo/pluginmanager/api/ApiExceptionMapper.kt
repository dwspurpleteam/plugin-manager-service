package ru.itmo.pluginmanager.api

import com.fasterxml.jackson.core.JsonProcessingException
import io.r2dbc.spi.R2dbcException
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.Logger
import ru.itmo.pluginmanager.domain.ErrorCode
import ru.itmo.pluginmanager.domain.PluginManagerException

@Provider
class ApiExceptionMapper : ExceptionMapper<Throwable> {
    override fun toResponse(exception: Throwable): Response {
        val code = errorCode(exception)
        val status = when (code) {
            ErrorCode.INVALID_REQUEST, ErrorCode.UNKNOWN_REGISTRY -> Response.Status.BAD_REQUEST

            ErrorCode.PLUGIN_NOT_FOUND, ErrorCode.ACTIVATION_NOT_FOUND -> Response.Status.NOT_FOUND

            ErrorCode.IDEMPOTENCY_CONFLICT, ErrorCode.ACTIVATION_NOT_READY,
            ErrorCode.ACTIVATION_FAILED, ErrorCode.ACTIVATION_NOT_ACTIVE,
            -> Response.Status.CONFLICT

            ErrorCode.REGISTRY_INVALID_RESPONSE, ErrorCode.INVALID_MANIFEST -> Response.Status.BAD_GATEWAY

            ErrorCode.REGISTRY_UNAVAILABLE, ErrorCode.DATABASE_UNAVAILABLE -> Response.Status.SERVICE_UNAVAILABLE

            else -> Response.Status.INTERNAL_SERVER_ERROR
        }
        if (status == Response.Status.INTERNAL_SERVER_ERROR) {
            Logger.getLogger(javaClass).error("Request failed", exception)
        }
        return Response.status(status).type("application/json").entity(ErrorResponse(code, status.reasonPhrase)).build()
    }

    private fun errorCode(exception: Throwable): ErrorCode = when {
        exception is PluginManagerException -> exception.code

        exception is BadRequestException || exception is JsonProcessingException -> ErrorCode.INVALID_REQUEST

        exception is NotFoundException && exception.cause is IllegalArgumentException -> ErrorCode.INVALID_REQUEST

        exception is NotFoundException -> ErrorCode.ACTIVATION_NOT_FOUND

        exception is WebApplicationException && exception.response.status == Response.Status.BAD_REQUEST.statusCode ->
            ErrorCode.INVALID_REQUEST

        generateSequence(exception) { it.cause }.any { it is R2dbcException } -> ErrorCode.DATABASE_UNAVAILABLE

        else -> ErrorCode.INTERNAL_ERROR
    }
}
