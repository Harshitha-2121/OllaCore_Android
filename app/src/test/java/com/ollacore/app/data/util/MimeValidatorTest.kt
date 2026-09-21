package com.ollacore.app.data.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MimeValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createFile(name: String, content: ByteArray): File {
        return tempFolder.newFile(name).apply {
            writeBytes(content)
        }
    }

    private fun createJpegFile(name: String = "test.jpg"): File {
        val header = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
        )
        val padding = ByteArray(1024)
        return createFile(name, header + padding)
    }

    private fun createPngFile(name: String = "test.png"): File {
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val padding = ByteArray(1024)
        return createFile(name, header + padding)
    }

    private fun createGifFile(name: String = "test.gif"): File {
        val header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        val padding = ByteArray(1024)
        return createFile(name, header + padding)
    }

    private fun createPdfFile(name: String = "test.pdf"): File {
        val header = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)
        val padding = ByteArray(1024)
        return createFile(name, header + padding)
    }

    private fun createZipFile(name: String = "test.zip"): File {
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        val padding = ByteArray(1024)
        return createFile(name, header + padding)
    }

    private fun createExeFile(name: String = "test.exe"): File {
        val header = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00, 0x00, 0x00)
        val padding = ByteArray(1024)
        return createFile(name, header + padding)
    }

    private fun createShellScript(name: String = "test.sh"): File {
        val content = "#!/bin/sh\necho hello".toByteArray()
        return createFile(name, content)
    }

    private fun createPhpFile(name: String = "test.php"): File {
        val content = "<?php echo 'test'; ?>".toByteArray()
        return createFile(name, content)
    }

    private fun createTextFile(name: String = "test.txt", content: String = "Hello world"): File {
        return createFile(name, content.toByteArray())
    }

    private fun createEmptyFile(name: String = "empty.txt"): File {
        return createFile(name, ByteArray(0))
    }

    private fun createRandomBytes(size: Int = 256): ByteArray {
        return ByteArray(size).apply {
            java.util.Random().nextBytes(this)
        }
    }

    // ════════════════════════════════════════════════════════════
    // VALID FILES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `valid JPEG passes validation`() {
        val file = createJpegFile()
        val result = MimeValidator.validate(file, "image/jpeg")
        assertTrue("JPEG should pass: ${result.error}", result.isValid)
        assertNull(result.error)
    }

    @Test
    fun `valid PNG passes validation`() {
        val file = createPngFile()
        val result = MimeValidator.validate(file, "image/png")
        assertTrue("PNG should pass: ${result.error}", result.isValid)
    }

    @Test
    fun `valid GIF passes validation`() {
        val file = createGifFile()
        val result = MimeValidator.validate(file, "image/gif")
        assertTrue("GIF should pass: ${result.error}", result.isValid)
    }

    @Test
    fun `valid PDF passes validation`() {
        val file = createPdfFile()
        val result = MimeValidator.validate(file, "application/pdf")
        assertTrue("PDF should pass: ${result.error}", result.isValid)
    }

    @Test
    fun `valid ZIP passes validation`() {
        val file = createZipFile()
        val result = MimeValidator.validate(file, "application/zip")
        assertTrue("ZIP should pass: ${result.error}", result.isValid)
    }

    @Test
    fun `valid text file passes validation`() {
        val file = createTextFile()
        val result = MimeValidator.validate(file, "text/plain")
        assertTrue("Text should pass", result.isValid)
    }

    // ════════════════════════════════════════════════════════════
    // BLOCKED TYPES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `EXE file is blocked`() {
        val file = createExeFile()
        val result = MimeValidator.validate(file, "application/x-msdownload")
        assertFalse("EXE should be blocked", result.isValid)
        assertTrue(result.error?.contains("not allowed") == true)
    }

    @Test
    fun `shell script is blocked`() {
        val file = createShellScript()
        val result = MimeValidator.validate(file, "application/x-sh")
        assertFalse("Shell script should be blocked", result.isValid)
    }

    @Test
    fun `PHP file is blocked`() {
        val file = createPhpFile()
        val result = MimeValidator.validate(file, "application/x-php")
        assertFalse("PHP should be blocked", result.isValid)
    }

    @Test
    fun `HTML file is blocked`() {
        val file = createFile("test.html", "<html>".toByteArray())
        val result = MimeValidator.validate(file, "text/html")
        assertFalse("HTML should be blocked", result.isValid)
    }

    @Test
    fun `JavaScript is blocked`() {
        val file = createFile("test.js", "alert('xss')".toByteArray())
        val result = MimeValidator.validate(file, "application/javascript")
        assertFalse("JavaScript should be blocked", result.isValid)
    }

    // ════════════════════════════════════════════════════════════
    // MAGIC BYTES MISMATCH
    // ════════════════════════════════════════════════════════════

    @Test
    fun `EXE renamed to JPG fails magic bytes check`() {
        val file = createExeFile("malware.jpg")
        val result = MimeValidator.validate(file, "image/jpeg")
        assertFalse("Renamed EXE should fail", result.isValid)
        assertTrue(result.error?.contains("Magic bytes") == true || 
                   result.error?.contains("content") == true)
    }

    @Test
    fun `random bytes declared as JPEG fails`() {
        val file = createFile("random.jpg", createRandomBytes())
        val result = MimeValidator.validate(file, "image/jpeg")
        assertFalse("Random bytes as JPEG should fail", result.isValid)
    }

    @Test
    fun `random bytes declared as PNG fails`() {
        val file = createFile("random.png", createRandomBytes())
        val result = MimeValidator.validate(file, "image/png")
        assertFalse("Random bytes as PNG should fail", result.isValid)
    }

    @Test
    fun `random bytes declared as PDF fails`() {
        val file = createFile("random.pdf", createRandomBytes())
        val result = MimeValidator.validate(file, "application/pdf")
        assertFalse("Random bytes as PDF should fail", result.isValid)
    }

    // ════════════════════════════════════════════════════════════
    // EMPTY FILES
    // ════════════════════════════════════════════════════════════

    @Test
    fun `empty file fails validation`() {
        val file = createEmptyFile("empty.txt")
        val result = MimeValidator.validate(file, "text/plain")
        assertFalse("Empty file should fail", result.isValid)
        assertTrue(result.error?.contains("empty") == true)
    }

    @Test
    fun `empty JPEG fails validation`() {
        val file = createEmptyFile("empty.jpg")
        val result = MimeValidator.validate(file, "image/jpeg")
        assertFalse("Empty JPEG should fail", result.isValid)
    }

    // ════════════════════════════════════════════════════════════
    // FILE SIZE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `file exceeding max size fails`() {
        // Create a file larger than the limit
        val largeContent = ByteArray(60 * 1024 * 1024) // 60MB
        val file = createFile("large.jpg", largeContent)
        val result = MimeValidator.validate(file, "image/jpeg")
        assertFalse("Large file should fail", result.isValid)
        assertTrue(result.error?.contains("too large") == true)
    }

    @Test
    fun `small valid file passes`() {
        val file = createFile("small.jpg", byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10
        ))
        val result = MimeValidator.validate(file, "image/jpeg")
        assertTrue("Small JPEG should pass: ${result.error}", result.isValid)
    }

    // ════════════════════════════════════════════════════════════
    // NONEXISTENT FILE
    // ════════════════════════════════════════════════════════════

    @Test
    fun `nonexistent file fails`() {
        val file = File(tempFolder.root, "doesnotexist.txt")
        val result = MimeValidator.validate(file, "text/plain")
        assertFalse("Nonexistent file should fail", result.isValid)
        assertTrue(result.error?.contains("does not exist") == true)
    }

    // ════════════════════════════════════════════════════════════
    // DOUBLE EXTENSION ATTACKS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `virus dot exe dot jpg is suspicious`() {
        val file = createExeFile("virus.exe.jpg")
        val result = MimeValidator.validate(file, "image/jpeg")
        assertFalse("Double extension should fail", result.isValid)
    }

    @Test
    fun `script dot sh dot txt is suspicious`() {
        val file = createShellScript("script.sh.txt")
        val result = MimeValidator.validate(file, "text/plain")
        assertFalse("Shell double extension should fail", result.isValid)
    }

    @Test
    fun `malware dot php dot html is suspicious`() {
        val file = createPhpFile("malware.php.html")
        val result = MimeValidator.validate(file, "text/html")
        assertFalse("PHP double extension should fail", result.isValid)
    }

    // ════════════════════════════════════════════════════════════
    // MIME TYPE DETECTION
    // ════════════════════════════════════════════════════════════

    @Test
    fun `detectMimeType identifies JPEG`() {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val detected = MimeValidator.detectMimeType(header, "jpg")
        assertEquals("image/jpeg", detected)
    }

    @Test
    fun `detectMimeType identifies PNG`() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val detected = MimeValidator.detectMimeType(header, "png")
        assertEquals("image/png", detected)
    }

    @Test
    fun `detectMimeType identifies PDF`() {
        val header = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val detected = MimeValidator.detectMimeType(header, "pdf")
        assertEquals("application/pdf", detected)
    }

    @Test
    fun `detectMimeType identifies ZIP`() {
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val detected = MimeValidator.detectMimeType(header, "zip")
        assertEquals("application/zip", detected)
    }

    @Test
    fun `detectMimeType identifies EXE`() {
        val header = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)
        val detected = MimeValidator.detectMimeType(header, "exe")
        assertEquals("application/x-msdownload", detected)
    }

    // ════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ════════════════════════════════════════════════════════════

    @Test
    fun `isBlockedType returns true for blocked types`() {
        assertTrue(MimeValidator.isBlockedType("application/x-msdownload"))
        assertTrue(MimeValidator.isBlockedType("application/x-sh"))
        assertTrue(MimeValidator.isBlockedType("application/x-php"))
        assertTrue(MimeValidator.isBlockedType("text/html"))
    }

    @Test
    fun `isBlockedType returns false for allowed types`() {
        assertFalse(MimeValidator.isBlockedType("image/jpeg"))
        assertFalse(MimeValidator.isBlockedType("image/png"))
        assertFalse(MimeValidator.isBlockedType("application/pdf"))
        assertFalse(MimeValidator.isBlockedType("text/plain"))
    }

    @Test
    fun `computeSha256 returns consistent hash`() {
        val file = createTextFile("hash.txt", "test content")
        val hash1 = MimeValidator.computeSha256(file)
        val hash2 = MimeValidator.computeSha256(file)
        assertEquals("SHA-256 should be consistent", hash1, hash2)
        assertEquals("SHA-256 should be 64 hex chars", 64, hash1.length)
    }

    @Test
    fun `computeSha256 differs for different content`() {
        val file1 = createTextFile("a.txt", "content A")
        val file2 = createTextFile("b.txt", "content B")
        val hash1 = MimeValidator.computeSha256(file1)
        val hash2 = MimeValidator.computeSha256(file2)
        assertNotEquals("Different files should have different hashes", hash1, hash2)
    }
}
