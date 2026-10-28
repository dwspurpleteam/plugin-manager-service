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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.eclipse.microprofile.context.ThreadContext
import org.jboss.logging.Logger
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckEventPermissionResponse
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionRequest
import ru.itmo.pluginmanager.routes.v1.CheckMediaPermissionResponse

@GrpcService
@Singleton
class RoutesGrpcServer(private val routesService: RoutesService, private val context: ThreadContext) : BindableService {
    private val scope = CoroutineScope(SupervisorJob())
    private val logger = Logger.getLogger(this::class.simpleName)

    override fun bindService(): ServerServiceDefinition = ServerServiceDefinition.builder(SERVICE_NAME)
        .addMethod(
            checkEventPermissionMethod,
            unaryCall("CheckEventPermission", routesService::getPermissionsForEvent),
        ).addMethod(
            checkMediaPermissionMethod,
            unaryCall("CheckMediaPermission", routesService::getPermissionsForMedia),
        ).build()

    private fun <RequestT, ResponseT> unaryCall(methodName: String, handler: suspend (RequestT) -> ResponseT) =
        ServerCalls.asyncUnaryCall { request: RequestT, observer ->
            scope.launch(context.currentContextExecutor().asCoroutineDispatcher()) {
                try {
                    observer.onNext(handler(request))
                    observer.onCompleted()
                } catch (
                    @Suppress("TooGenericExceptionCaught") exception: Exception,
                ) {
                    failCall(methodName, observer, exception)
                } catch (error: LinkageError) {
                    failCall(methodName, observer, error)
                }
            }
        }

    private fun <ResponseT> failCall(
        methodName: String,
        observer: io.grpc.stub.StreamObserver<ResponseT>,
        cause: Throwable,
    ) {
        observer.onError(
            Status.INTERNAL
                .withDescription("Permission check failed")
                .withCause(cause)
                .asRuntimeException(),
        )
        logger.errorf(cause, "error with method %s", methodName)
    }

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
