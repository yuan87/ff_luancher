package com.ff.launcher.viewmodel

import android.os.UserHandle
import com.ff.launcher.data.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateBuilderTest {

    private fun dummyUserHandle(): UserHandle {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, UserHandle::class.java) as UserHandle
    }

    private fun createApp(label: String, key: String): AppInfo {
        return AppInfo(
            label = label,
            packageName = key.substringBefore("/"),
            componentKey = key,
            icon = null,
            userHandle = dummyUserHandle(),
            firstInstallTime = 1000L,
        )
    }

    @Test
    fun `appInfo equality and hashcode are keyed on componentKey`() {
        val app1 = createApp("Label A", "com.example.app/MainActivity#0")
        val app2 = createApp("Different Label", "com.example.app/MainActivity#0")
        val app3 = createApp("Label A", "com.example.app/MainActivity#10")

        assertEquals(app1, app2)
        assertEquals(app1.hashCode(), app2.hashCode())
        assertNotEquals(app1, app3)
    }

    @Test
    fun `empty apps returns empty state`() {
        val result = HomeStateBuilder.build(
            apps = emptyList(),
            orderedFavoriteKeys = emptyList(),
            hiddenKeys = emptySet(),
        )
        assertEquals(0, result.rows.size)
        assertEquals(0, result.sections.size)
        assertEquals(0, result.favoriteCount)
        assertTrue(result.favoriteKeys.isEmpty())
    }

    @Test
    fun `hidden apps are excluded from both favorites and regular rows`() {
        val app1 = createApp("Alpha", "com.example.alpha/Activity#0")
        val app2 = createApp("Beta", "com.example.beta/Activity#0")
        val app3 = createApp("Hidden", "com.example.hidden/Activity#0")

        val result = HomeStateBuilder.build(
            apps = listOf(app1, app2, app3),
            orderedFavoriteKeys = listOf(app3.componentKey),
            hiddenKeys = setOf(app3.componentKey),
        )

        val componentKeysInRows = result.rows.filterIsInstance<HomeRow.AppRow>().map { it.app.componentKey }
        assertFalse(componentKeysInRows.contains(app3.componentKey))
        assertEquals(0, result.favoriteCount)
    }

    @Test
    fun `favorites appear first followed by divider and preserve user ordering`() {
        val app1 = createApp("Alpha", "com.example.alpha/Activity#0")
        val app2 = createApp("Beta", "com.example.beta/Activity#0")
        val app3 = createApp("Gamma", "com.example.gamma/Activity#0")

        val result = HomeStateBuilder.build(
            apps = listOf(app1, app2, app3),
            orderedFavoriteKeys = listOf(app3.componentKey, app1.componentKey),
            hiddenKeys = emptySet(),
        )

        assertEquals(2, result.favoriteCount)
        val favoriteRows = result.rows.take(2).filterIsInstance<HomeRow.AppRow>()
        assertEquals(app3.componentKey, favoriteRows[0].app.componentKey)
        assertEquals(app1.componentKey, favoriteRows[1].app.componentKey)
        assertTrue(favoriteRows[0].isFavorite)
        assertTrue(favoriteRows[1].isFavorite)

        // Followed by divider
        assertTrue(result.rows[2] is HomeRow.Divider)
    }

    @Test
    fun `favorite count is 0 when no favorites are pinned`() {
        val app1 = createApp("Alpha", "com.example.alpha/Activity#0")
        val result = HomeStateBuilder.build(
            apps = listOf(app1),
            orderedFavoriteKeys = emptyList(),
            hiddenKeys = emptySet(),
        )
        assertEquals(0, result.favoriteCount)
        assertFalse(result.rows.any { it is HomeRow.Divider })
    }
}
