package io.github.yosefchai.mealie

import io.github.yosefchai.mealie.core.VersionCode
import io.github.yosefchai.mealie.net.HostPolicy
import io.github.yosefchai.mealie.net.ServerUrl
import io.github.yosefchai.mealie.web.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCodeTest {
    @Test
    fun `app patch never collides with the next Mealie version`() {
        assertEquals(3_010_000, VersionCode.of(3, 1, 0, 0))
        assertEquals(3_010_001, VersionCode.of(3, 1, 0, 1))
        assertEquals(3_010_100, VersionCode.of(3, 1, 1, 0))
        assertTrue(VersionCode.of(3, 1, 0, 99) < VersionCode.of(3, 1, 1, 0))
        assertTrue(VersionCode.of(3, 99, 99, 99) < VersionCode.of(4, 0, 0, 0))
    }

    @Test
    fun `tags parse with and without app patch`() {
        val base = VersionCode.fromTag("android-v3.31.0")!!
        assertEquals("3.31.0", base.mealieVersion)
        assertEquals(0, base.appPatch)
        assertEquals(3_310_000, base.code)
        val patched = VersionCode.fromTag("android-v3.31.0.2")!!
        assertEquals(2, patched.appPatch)
        assertEquals(3_310_002, patched.code)
        assertNull(VersionCode.fromTag("v3.31.0"))
        assertNull(VersionCode.fromTag("android-v3.31"))
        assertEquals("android-v3.31.0", VersionCode.tagFor("3.31.0", 0))
        assertEquals("android-v3.31.0.4", VersionCode.tagFor("3.31.0", 4))
    }

    /** הנוסחה ב-Gradle (buildSrc) וב-Kotlin חייבות להיות זהות, אחרת העדכון העצמי ישווה גרסאות לא נכון. */
    @Test
    fun `build config version code matches the app formula`() {
        val (x, y, z) = BuildConfig.MEALIE_VERSION.split('.').map(String::toInt)
        assertEquals(VersionCode.of(x, y, z, BuildConfig.APP_PATCH), BuildConfig.VERSION_CODE)
    }
}

class HostPolicyTest {
    @Test
    fun `private addresses allow cleartext`() {
        listOf(
            "192.168.1.10", "10.0.0.5", "172.16.3.4", "172.31.255.1", "127.0.0.1", "169.254.2.2",
            "100.64.0.1", "100.127.10.10", "localhost", "mealie", "mealie.local", "nas.lan",
            "kitchen.home.arpa", "box.tailnet-123.ts.net", "::1", "fd12:3456::1", "fe80::1", "[fd00::1]",
        ).forEach { assertTrue(it, HostPolicy.isCleartextAllowed(it)) }
    }

    @Test
    fun `public addresses do not`() {
        listOf(
            "8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.1.1", "mealie.example.com",
            "example.local.evil.com", "2001:4860::8888", "256.1.1.1",
        ).forEach { assertFalse(it, HostPolicy.isCleartextAllowed(it)) }
    }
}

class ServerUrlTest {
    @Test
    fun `scheme defaults to http on private hosts and https elsewhere`() {
        assertEquals("http://192.168.1.10:9000", ServerUrl.parse("192.168.1.10:9000").toString())
        assertEquals("https://mealie.example.com", ServerUrl.parse("mealie.example.com").toString())
        assertEquals("https://mealie.example.com", ServerUrl.parse(" https://mealie.example.com/ ").toString())
        assertNull(ServerUrl.parse(""))
        assertNull(ServerUrl.parse("http://"))
    }

    @Test
    fun `sub path is kept in every built URL`() {
        val url = ServerUrl.parse("https://home.example.com/mealie/")!!
        assertEquals("https://home.example.com", url.origin)
        assertEquals("/mealie", url.basePath)
        assertEquals("https://home.example.com/mealie/api/app/about", url.resolve("/api/app/about"))
        assertEquals("/shopping-lists", url.relativePath("https://home.example.com/mealie/shopping-lists"))
        assertEquals("/g/home?x=1", url.relativePath("https://home.example.com/mealie/g/home?x=1"))
        assertNull(url.relativePath("https://home.example.com/other/page"))
        assertNull(url.relativePath("https://evil.example.com/mealie/g/home"))
    }

    @Test
    fun `origin includes non default port and drops credentials`() {
        val url = ServerUrl.parse("http://user:pw@192.168.1.10:9000/?a=1#x")!!
        assertEquals("http://192.168.1.10:9000", url.origin)
        assertEquals("192.168.1.10:9000", url.hostPort)
        assertEquals("http://192.168.1.10:9000/g/home", url.resolve("g/home"))
    }
}

class TabsTest {
    @Test
    fun `paths map to tabs`() {
        assertEquals(Tab.RECIPES, Tab.forPath("/g/home"))
        assertEquals(Tab.RECIPES, Tab.forPath("/g/home/r/pasta"))
        assertEquals(Tab.RECIPES, Tab.forPath("/g/home/recipes/tags"))
        assertEquals(Tab.COOKBOOKS, Tab.forPath("/g/home/cookbooks"))
        assertEquals(Tab.COOKBOOKS, Tab.forPath("/g/home/cookbooks/dinner"))
        assertEquals(Tab.MEALPLAN, Tab.forPath("/household/mealplan/planner/view"))
        assertEquals(Tab.SHOPPING, Tab.forPath("/shopping-lists/abc?x=1"))
        assertNull(Tab.forPath("/user/profile"))
    }

    @Test
    fun `tab roots and hidden bar`() {
        assertTrue(Tab.RECIPES.isRoot("/g/home"))
        assertTrue(Tab.RECIPES.isRoot("/g/home/"))
        assertFalse(Tab.RECIPES.isRoot("/g/home/r/pasta"))
        assertTrue(Tab.SHOPPING.isRoot("/shopping-lists"))
        assertFalse(Tab.SHOPPING.isRoot("/shopping-lists/abc"))
        assertTrue(Tab.hidesBar("/login"))
        assertTrue(Tab.hidesBar("/login?redirect=%2F"))
        assertFalse(Tab.hidesBar("/g/home"))
        assertEquals("home", Tab.groupSlugOf("/g/home/r/pasta"))
        assertEquals("/g/home", Tab.RECIPES.rootPath("home"))
        assertEquals("/", Tab.RECIPES.rootPath(null))
    }
}
