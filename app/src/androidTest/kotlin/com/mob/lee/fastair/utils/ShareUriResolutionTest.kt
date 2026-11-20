package com.mob.lee.fastair.utils

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareUriResolutionTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sharedFile(name: String, content: String): File {
        val dir = File(context.externalCacheDir, "share-source").apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }

    private fun fileProviderUri(file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.FileProvider",
        file
    )

    @Test
    fun providerUriHasNoFilePath() {
        val uri = fileProviderUri(sharedFile("provider.txt", "content"))
        assertNull(context.urlToPath(uri))
    }

    @Test
    fun providerUriIsCopiedIntoCacheWithSameContent() {
        val source = sharedFile("provider.txt", "hello share")
        val resolved = context.resolveShareUri(fileProviderUri(source))

        assertNotNull(resolved)
        assertEquals("hello share", resolved!!.readText())
        assertTrue(resolved.absolutePath.startsWith(context.cacheDir.absolutePath))
    }

    @Test
    fun repeatedSharesOfSameNameDoNotOverwrite() {
        val first = context.resolveShareUri(fileProviderUri(sharedFile("dup.txt", "first")))
        val second = context.resolveShareUri(fileProviderUri(sharedFile("dup.txt", "second")))

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first!!.absolutePath != second!!.absolutePath)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test
    fun unresolvableUriYieldsNull() {
        val uri = fileProviderUri(sharedFile("gone.txt", "content"))
        sharedFile("gone.txt", "content").delete()

        assertNull(context.resolveShareUri(uri))
    }
}
