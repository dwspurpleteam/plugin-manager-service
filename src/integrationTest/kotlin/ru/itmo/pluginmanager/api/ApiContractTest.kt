package ru.itmo.pluginmanager.api

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.itmo.pluginmanager.database.PostgresTestResource
import java.net.URI
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class ApiContractTest {
    /**
     * Тест проверяет асинхронную регистрацию, повтор, конфликт и чтение неготового маршрута через REST.
     * Ожидаемый результат: 202, прежний ID с 200, конфликт 409 и NOT_READY до активации.
     */
    @Test
    fun `registration returns before activation and exposes idempotency`() {
        val body = mapOf(
            "callId" to UUID.randomUUID().toString(),
            "pluginId" to "plugin",
            "requestedBy" to "user",
            "idempotencyKey" to "key",
        )
        val first = given().contentType("application/json").body(body).post("/v1/activations")
            .then().statusCode(202).extract().response()
        val id = first.jsonPath().getString("activationId")
        assertEquals("/v1/activations/$id", URI(first.header("Location")).path)
        val repeated = given().contentType("application/json").body(body).post("/v1/activations")
            .then().statusCode(200).extract().path<String>("activationId")
        assertEquals(id, repeated)
        given().contentType("application/json").body(body + ("requestedBy" to "other"))
            .post("/v1/activations").then().statusCode(409)
        given().get("/v1/activations/$id/signal-route").then().statusCode(409)
    }

    /**
     * Тест проверяет ошибки входных данных и отсутствие активации.
     * Ожидаемый результат: некорректный JSON и UUID дают 400; неизвестная активация даёт 404.
     */
    @Test
    fun `invalid input and missing activation have stable errors`() {
        given().contentType("application/json").body("{}").post("/v1/activations").then().statusCode(400)
        given().get("/v1/activations/not-a-uuid").then().statusCode(400)
        given().get("/v1/activations/${UUID.randomUUID()}").then().statusCode(404)
    }
}
