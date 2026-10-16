package life.bloomary.app.features.linkedinpost.api

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of ios/BloomaryTests/MultipartFormDataBuilderTests.swift.
 */
class MultipartFormDataBuilderTests {

    @Test
    fun buildsExpectedFormBody() {
        val builder = MultipartFormDataBuilder(boundary = "test-boundary")
        val body = String(builder.body(fields = mapOf("commentary" to "Hello")), Charsets.UTF_8)

        assertTrue(body.contains("--test-boundary\r\n"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"commentary\"\r\n\r\n"))
        assertTrue(body.contains("Hello\r\n"))
        assertTrue(body.endsWith("--test-boundary--\r\n"))
    }

    @Test
    fun buildsFilePartWithContentType() {
        val builder = MultipartFormDataBuilder(boundary = "test-boundary")
        val body = String(
            builder.body(
                fields = emptyMap(),
                file = MultipartFormFile(
                    fieldName = "attachment",
                    filename = "image.jpg",
                    mimeType = "image/jpeg",
                    data = byteArrayOf(1, 2, 3),
                ),
            ),
            Charsets.UTF_8,
        )

        assertTrue(body.contains("Content-Disposition: form-data; name=\"attachment\"; filename=\"image.jpg\"\r\n"))
        assertTrue(body.contains("Content-Type: image/jpeg\r\n\r\n"))
        assertTrue(body.endsWith("--test-boundary--\r\n"))
    }
}
