package tally.server

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tally.core.llm.CategorizeService
import tally.core.llm.InMemoryCategoryCache
import tally.core.sync.InMemorySyncStore
import tally.core.sync.SyncServer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

class AccessAuthTest {
    private val access = AccessConfig(teamDomain = "emre.cloudflareaccess.com", audience = "tally-aud")
    private val keys = rsaKeys()
    private val otherKeys = rsaKeys()

    /** Stands in for Cloudflare's certs endpoint: serves the public half of [keys] as kid "k1". */
    private val jwks = JwkProvider { kid ->
        val key = keys.public as RSAPublicKey
        val b64 = Base64.getUrlEncoder().withoutPadding()
        Jwk.fromValues(
            mapOf(
                "kid" to kid,
                "kty" to "RSA",
                "alg" to "RS256",
                "n" to b64.encodeToString(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()),
                "e" to b64.encodeToString(key.publicExponent.toByteArray()),
            ),
        )
    }

    private fun token(
        signWith: KeyPair = keys,
        audience: String = access.audience,
        expiresInSeconds: Long = 300,
        email: String? = "me@ems-place.com",
        commonName: String? = null,
    ): String = JWT.create()
        .withKeyId("k1")
        .withIssuer(access.issuer)
        .withAudience(audience)
        .withExpiresAt(Date(System.currentTimeMillis() + expiresInSeconds * 1000))
        .apply { email?.let { withClaim("email", it) } }
        .apply { commonName?.let { withClaim("common_name", it); withSubject("") } }
        .sign(Algorithm.RSA256(signWith.public as RSAPublicKey, signWith.private as RSAPrivateKey))

    private fun app(
        databaseUp: Boolean = true,
        categorize: CategorizeService? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { tallyModule(access, { databaseUp }, SyncServer(InMemorySyncStore()), jwks, categorize) }
        block()
    }

    @Test
    fun `liveness needs no login`() = app {
        assertEquals("ok", client.get("/healthz").bodyAsText())
    }

    @Test
    fun `no access header is rejected`() = app {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status)
    }

    @Test
    fun `a signed-in browser is you`() = app {
        val response = client.get("/api/me") { header(ACCESS_JWT_HEADER, token()) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"name":"me@ems-place.com","kind":"USER"}""", response.bodyAsText())
        val home = client.get("/") { header(ACCESS_JWT_HEADER, token()) }.bodyAsText()
        assertTrue("me@ems-place.com" in home)
    }

    @Test
    fun `the phone's service token is a service caller`() = app {
        val response = client.get("/api/me") {
            header(ACCESS_JWT_HEADER, token(email = null, commonName = "abc123.access"))
        }
        assertEquals("""{"name":"abc123.access","kind":"SERVICE_TOKEN"}""", response.bodyAsText())
    }

    @Test
    fun `wrong audience, foreign signature and expired tokens are rejected`() = app {
        for (bad in listOf(token(audience = "other-app"), token(signWith = otherKeys), token(expiresInSeconds = -120))) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { header(ACCESS_JWT_HEADER, bad) }.status)
        }
    }

    @Test
    fun `sync needs access and round trips a row`() = app {
        val body = """{"deviceId":"phone","since":0,"changes":[
            {"table":"transactions","id":"t1","updatedAt":${System.currentTimeMillis()},"deleted":false,"data":{"amountCents":425}}]}"""
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/sync") { contentType(ContentType.Application.Json); setBody(body) }.status,
        )
        val response = client.post("/api/sync") {
            header(ACCESS_JWT_HEADER, token(email = null, commonName = "phone.access"))
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""cursor":1"""))
        val bad = client.post("/api/sync") {
            header(ACCESS_JWT_HEADER, token())
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun `categorize answers from the model, needs access, and says when it's off`() {
        val body = """{"merchant":"Foodie Fruitie","amountCents":3432,"categories":["Food","Food/Groceries"]}"""
        val service = CategorizeService(InMemoryCategoryCache(), { _, _ -> "Food/Groceries" })
        app(categorize = service) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/categorize") { contentType(ContentType.Application.Json); setBody(body) }.status,
            )
            val response = client.post("/api/categorize") {
                header(ACCESS_JWT_HEADER, token(email = null, commonName = "phone.access"))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals("""{"category":"Food/Groceries","cached":false}""", response.bodyAsText())
        }
        app(categorize = null) {
            val response = client.post("/api/categorize") {
                header(ACCESS_JWT_HEADER, token())
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
    }

    @Test
    fun `the event stream needs access and frames events as sse`() = app {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/events").status)
        assertEquals(
            "event: sync\ndata: {\"cursor\":7,\"source\":\"web-abc\"}\n\n",
            tally.core.sync.SyncEvent(7, "web-abc").toSse(),
        )
    }

    @Test
    fun `readiness reports the database`() {
        app(databaseUp = true) {
            assertEquals(HttpStatusCode.OK, client.get("/api/health") { header(ACCESS_JWT_HEADER, token()) }.status)
        }
        app(databaseUp = false) {
            val response = client.get("/api/health") { header(ACCESS_JWT_HEADER, token()) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("""{"ok":false,"service":"tally","database":"unreachable"}""", response.bodyAsText())
        }
    }

    @Test
    fun `config requires access settings unless explicitly disabled`() {
        assertThrows<IllegalStateException> { ServerConfig.fromEnv(mapOf("POSTGRES_PASSWORD" to "x")) }
        val config = ServerConfig.fromEnv(
            mapOf(
                "POSTGRES_PASSWORD" to "x",
                "ACCESS_TEAM_DOMAIN" to "https://emre.cloudflareaccess.com/",
                "ACCESS_AUD" to "aud",
            ),
        )
        assertEquals("https://emre.cloudflareaccess.com", config.access?.issuer)
        assertEquals(null, ServerConfig.fromEnv(mapOf("POSTGRES_PASSWORD" to "x", "ACCESS_DISABLED" to "true")).access)
        assertThrows<IllegalStateException> { ServerConfig.fromEnv(mapOf("ACCESS_DISABLED" to "true")) }

        val base = mapOf("POSTGRES_PASSWORD" to "x", "ACCESS_DISABLED" to "true")
        assertEquals(null, ServerConfig.fromEnv(base + ("ANTHROPIC_API_KEY" to "")).llm)
        val llm = ServerConfig.fromEnv(base + ("ANTHROPIC_API_KEY" to "sk-secret")).llm
        assertEquals(LlmConfig.DEFAULT_MODEL, llm?.model)
        assertTrue("sk-secret" !in llm.toString())
    }

    private companion object {
        fun rsaKeys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    }
}
