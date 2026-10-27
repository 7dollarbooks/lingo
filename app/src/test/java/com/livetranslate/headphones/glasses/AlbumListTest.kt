package com.livetranslate.headphones.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumListTest {
    @Test
    fun newestVideoSkipsTheJpegAndKeepsTheLaterClip() {
        val list = """
            20261027_100000.jpg
            20261027_100100.mp4
            note 20261027_100200.mov
        """.trimIndent()
        assertEquals("20261027_100200.mov", AlbumList.newestVideoName(list))
    }

    @Test
    fun jpegOnlyListHasNoVideo() {
        val list = """
            20261027_100000.jpg
            20261027_100100.jpeg
        """.trimIndent()
        assertNull(AlbumList.newestVideoName(list))
    }
}
