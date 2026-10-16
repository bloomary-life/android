package life.bloomary.app.features.linkedinpost.api

import java.io.ByteArrayOutputStream

/**
 * Line-for-line port of ios/Features/LinkedInPost/API/MultipartFormDataBuilder.swift.
 *
 * Raw byte builder (not OkHttp MultipartBody) so the CRLF layout and the
 * `Boundary-{UUID}` boundary match the iOS request byte-for-byte. Field order
 * follows map iteration order; iOS builds fields from a Swift Dictionary, so
 * order is nondeterministic there — callers/tests must not depend on it.
 */
class MultipartFormDataBuilder(
    private val boundary: String,
) {
    fun body(fields: Map<String, String>, file: MultipartFormFile? = null): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in fields) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
            out.write(value.toByteArray(Charsets.UTF_8))
            out.writeAscii("\r\n")
        }
        if (file != null) {
            out.writeAscii("--$boundary\r\n")
            out.writeAscii("Content-Disposition: form-data; name=\"${file.fieldName}\"; filename=\"${file.filename}\"\r\n")
            out.writeAscii("Content-Type: ${file.mimeType}\r\n\r\n")
            out.write(file.data)
            out.writeAscii("\r\n")
        }
        out.writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(string: String) {
        write(string.toByteArray(Charsets.UTF_8))
    }
}

class MultipartFormFile(
    val fieldName: String,
    val filename: String,
    val mimeType: String,
    val data: ByteArray,
)
