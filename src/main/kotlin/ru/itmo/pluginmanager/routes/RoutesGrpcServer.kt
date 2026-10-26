package ru.itmo.pluginmanager.routes

import com.google.protobuf.Message
import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.Status
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ServerCalls
import io.quarkus.grpc.GrpcService
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jboss.logging.Logger
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionResponse
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionResponse

@GrpcService
@Singleton
class RoutesGrpcServer(private val routesService: RoutesService) : BindableService {
    private val scope = CoroutineScope(SupervisorJob())
    private val logger = Logger.getLogger(this::class.simpleName)

    override fun bindService(): ServerServiceDefinition = ServerServiceDefinition.builder(SERVICE_NAME)
        .addMethod(
            checkEventPermissionMethod,
            ServerCalls.asyncUnaryCall { request, observer ->
                scope.launch {
                    try {
                        val response = routesService.getPermissionsForEvent(request)
                        observer.onNext(response)
                        observer.onCompleted()
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        observer.onError(
                            Status.INTERNAL
                                .withDescription("Permission check failed")
                                .withCause(e)
                                .asRuntimeException(),
                        )
                        logger.errorf(e, "error with method checkEventPermissionMethod")
                    }
                }
            },
        ).addMethod(
            checkMediaPermissionMethod,
            ServerCalls.asyncUnaryCall { request, observer ->
                scope.launch {
                    try {
                        val response = routesService.getPermissionsForMedia(request)
                        observer.onNext(response)
                        observer.onCompleted()
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        observer.onError(
                            Status.INTERNAL
                                .withDescription("Permission check failed")
                                .withCause(e)
                                .asRuntimeException(),
                        )
                        logger.errorf(e, "error with method checkMediaPermissionMethod")
                    }
                }
            },
        ).build()

    private companion object {
        const val SERVICE_NAME = "routes.v1.RoutesService"

        val checkEventPermissionMethod: MethodDescriptor<CheckEventPermissionRequest, CheckEventPermissionResponse> =
            unaryMethod(
                "CheckEventPermission",
                CheckEventPermissionRequest.getDefaultInstance(),
                CheckEventPermissionResponse.getDefaultInstance(),
            )

        val checkMediaPermissionMethod: MethodDescriptor<CheckMediaPermissionRequest, CheckMediaPermissionResponse> =
            unaryMethod(
                "CheckMediaPermission",
                CheckMediaPermissionRequest.getDefaultInstance(),
                CheckMediaPermissionResponse.getDefaultInstance(),
            )

        private fun <RequestT : Message, ResponseT : Message> unaryMethod(
            methodName: String,
            request: RequestT,
            response: ResponseT,
        ): MethodDescriptor<RequestT, ResponseT> = MethodDescriptor.newBuilder<RequestT, ResponseT>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, methodName))
            .setRequestMarshaller(ProtoUtils.marshaller(request))
            .setResponseMarshaller(ProtoUtils.marshaller(response))
            .build()
    }
}
