package tally.server

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import java.net.URI
import java.util.concurrent.TimeUnit

const val ACCESS_AUTH = "cloudflare-access"

/** Who made a request: you in a browser (email) or the phone's service token (client id). */
data class Caller(val name: String, val kind: Kind) {
    enum class Kind { USER, SERVICE_TOKEN, LOCAL_DEV }
}

/**
 * Every request must carry the JWT Cloudflare Access adds after login
 * (`Cf-Access-Jwt-Assertion`), signed by the team's keys and issued for this app's AUD. That
 * covers both the browser (you, via the Access login) and the phone (via an Access service
 * token). Checking it here, not only at Cloudflare, means a request that reaches the VPS
 * directly (skipping Cloudflare) gets nothing.
 */
fun AuthenticationConfig.cloudflareAccess(config: AccessConfig, jwks: JwkProvider = defaultJwks(config)) {
    jwt(ACCESS_AUTH) {
        realm = "tally"
        authHeader { call ->
            call.request.headers[ACCESS_JWT_HEADER]?.let { HttpAuthHeader.Single("Bearer", it) }
        }
        verifier(jwks, config.issuer) {
            withAudience(config.audience)
            acceptLeeway(30)
        }
        validate { credential ->
            if (config.audience in credential.payload.audience) JWTPrincipal(credential.payload) else null
        }
    }
}

const val ACCESS_JWT_HEADER = "Cf-Access-Jwt-Assertion"

private fun defaultJwks(config: AccessConfig): JwkProvider =
    JwkProviderBuilder(URI(config.certsUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

/** The verified caller. Service-token JWTs carry `common_name` (the client id) instead of `email`. */
fun ApplicationCall.caller(): Caller {
    val payload = principal<JWTPrincipal>()?.payload ?: return Caller("local", Caller.Kind.LOCAL_DEV)
    payload.getClaim("email").asString()?.let { return Caller(it, Caller.Kind.USER) }
    val clientId = payload.getClaim("common_name").asString() ?: payload.subject.orEmpty()
    return Caller(clientId, Caller.Kind.SERVICE_TOKEN)
}
