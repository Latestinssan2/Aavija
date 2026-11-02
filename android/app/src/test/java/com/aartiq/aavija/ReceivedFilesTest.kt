package com.aartiq.aavija

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which received files should open in the photo viewer.
 *
 * The rule is intentionally narrow: only real image extensions. A zip named
 * "holiday.jpg.exe" must not be handed to the image decoder, and a PDF should
 * stay a normal saved file.
 */
class ReceivedFilesTest {

    @Test
    fun `common photo types open automatically`() {
        assertTrue(ReceivedFiles.isImageFile("Aavija-Screenshot-2026-11-02-143522.png"))
        assertTrue(ReceivedFiles.isImageFile("photo.jpg"))
        assertTrue(ReceivedFiles.isImageFile("photo.jpeg"))
        assertTrue(ReceivedFiles.isImageFile("scan.webp"))
        assertTrue(ReceivedFiles.isImageFile("portrait.heic"))
        assertTrue(ReceivedFiles.isImageFile("meme.gif"))
    }

    @Test
    fun `extension case does not matter`() {
        assertTrue(ReceivedFiles.isImageFile("SCREENSHOT.PNG"))
        assertTrue(ReceivedFiles.isImageFile("Photo.JpEg"))
    }

    @Test
    fun `non images stay out of the viewer`() {
        assertFalse(ReceivedFiles.isImageFile("notes.txt"))
        assertFalse(ReceivedFiles.isImageFile("archive.zip"))
        assertFalse(ReceivedFiles.isImageFile("movie.mp4"))
        assertFalse(ReceivedFiles.isImageFile("document.pdf"))
        assertFalse(ReceivedFiles.isImageFile("no-extension"))
    }

    @Test
    fun `a disguised executable is not treated as an image`() {
        assertFalse(ReceivedFiles.isImageFile("holiday.jpg.exe"))
    }
}
