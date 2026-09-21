package com.ollacore.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class AttachmentUploader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    fun uploadToPresignedUrl(uploadUrl: String, file: File, mimeType: String, onProgress: ((Int) -> Unit)? = null) {
        val requestBody = file.asRequestBody(mimeType.toMediaType())
        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Upload failed: HTTP ${response.code}")
        }
    }

    fun uploadPart(partUrl: String, data: ByteArray, onProgress: ((Int) -> Unit)? = null): String {
        val requestBody = data.toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(partUrl)
            .put(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Part upload failed: HTTP ${response.code}")
        }

        return response.header("ETag") ?: "\"${java.util.UUID.randomUUID()}\""
    }

    fun uploadMultipart(
        partUrls: List<String>,
        file: File,
        partSize: Long,
        onProgress: ((uploaded: Int, total: Int) -> Unit)? = null
    ): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val totalParts = partUrls.size
        var uploadedParts = 0

        file.inputStream().use { input ->
            val buffer = ByteArray(partSize.toInt())

            partUrls.forEachIndexed { index, url ->
                val bytesRead = input.read(buffer)
                val data = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)

                val etag = uploadPart(url, data)
                results.add(Pair(index + 1, etag))

                uploadedParts++
                onProgress?.invoke(uploadedParts, totalParts)
            }
        }

        return results
    }
}
