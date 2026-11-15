package io.github.yosefchai.mealie

import io.github.yosefchai.mealie.core.ServerConfig
import io.github.yosefchai.mealie.net.AboutInfo
import io.github.yosefchai.mealie.net.NetworkState
import io.github.yosefchai.mealie.net.ProbeResult
import io.github.yosefchai.mealie.net.Resolution
import io.github.yosefchai.mealie.net.ServerProbe
import io.github.yosefchai.mealie.net.ServerResolver
import io.github.yosefchai.mealie.net.ServerUrl
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerResolverTest {
    private val internal = ServerUrl.parse("http://192.168.1.10:9000")!!
    private val external = ServerUrl.parse("https://mealie.example.com")!!
    private val server = ServerConfig(internalUrl = internal.toString(), externalUrl = external.toString())
    private val ok = { u: ServerUrl -> ProbeResult.Ok(u, AboutInfo(version = "v3.31.0")) }
    private val down = { u: ServerUrl -> ProbeResult.Unreachable(u, IOException("down")) }
    private val wifi = NetworkState(connected = true, mayReachLocal = true)
    private val cellular = NetworkState(connected = true, mayReachLocal = false)

    @Test
    fun `internal wins at home even when external answers first`() = runTest {
        val resolver = ServerResolver(probe = { u, _ ->
            if (u == internal) { delay(300); ok(u) } else ok(u)
        })
        val r = resolver.resolve(server, wifi) as Resolution.Ready
        assertEquals(internal, r.url)
    }

    @Test
    fun `falls back to external when internal fails`() = runTest {
        val resolver = ServerResolver(probe = { u, _ -> if (u == internal) down(u) else ok(u) })
        assertEquals(external, (resolver.resolve(server, wifi) as Resolution.Ready).url)
    }

    @Test
    fun `cellular without VPN skips the internal address`() = runTest {
        val probed = mutableListOf<ServerUrl>()
        val resolver = ServerResolver(probe = { u, _ -> probed += u; ok(u) })
        assertEquals(external, (resolver.resolve(server, cellular) as Resolution.Ready).url)
        assertEquals(listOf(external), probed)
    }

    @Test
    fun `offline uses the last address that worked`() = runTest {
        val resolver = ServerResolver(probe = { u, _ -> ok(u) })
        val r = resolver.resolve(server.copy(lastUsedUrl = internal.toString()), NetworkState.Offline)
        assertEquals(internal, (r as Resolution.Offline).url)
    }

    @Test
    fun `auth gate counts as reachable`() = runTest {
        val resolver = ServerResolver(probe = { u, _ -> if (u == internal) down(u) else ProbeResult.AuthGate(u, "https://auth.example.com") })
        val r = resolver.resolve(server, wifi) as Resolution.Ready
        assertTrue(r.result is ProbeResult.AuthGate)
    }

    @Test
    fun `both failing reports both results`() = runTest {
        val resolver = ServerResolver(probe = { u, _ -> down(u) })
        assertEquals(2, (resolver.resolve(server, wifi) as Resolution.Failed).results.size)
    }
}

class ServerProbeTest {
    private lateinit var web: MockWebServer
    private val probe = ServerProbe(OkHttpClient.Builder().followRedirects(false).build())

    @Before
    fun start() {
        web = MockWebServer()
        web.start()
    }

    @After
    fun stop() = web.close()

    private fun url() = ServerUrl.parse(web.url("/").toString())!!

    @Test
    fun `mealie about is recognised`() = runTest {
        web.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "application/json")
                .body("""{"version":"v3.31.0","enableOidc":true,"oidcProviderName":"Authentik","tokenTime":72}""").build(),
        )
        val r = probe.probe(url()) as ProbeResult.Ok
        assertEquals("v3.31.0", r.about.version)
        assertEquals(72, r.about.tokenTime)
        assertTrue(r.about.enableOidc)
    }

    @Test
    fun `redirect means an auth gate`() = runTest {
        web.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://auth.example.com/login").build())
        assertEquals("https://auth.example.com/login", (probe.probe(url()) as ProbeResult.AuthGate).location)
    }

    @Test
    fun `html instead of json means an auth gate`() = runTest {
        web.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>login</html>").build())
        assertTrue(probe.probe(url()) is ProbeResult.AuthGate)
    }

    @Test
    fun `other json is not mealie`() = runTest {
        web.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body("{}").build())
        assertTrue(probe.probe(url()) is ProbeResult.NotMealie)
    }
}
