package com.personalmorningalarm.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Pins the exact bytes POST /api/presence/open sends, through the same Retrofit
 * and Gson setup [DashboardClient] uses. The dashboard compares the PIN byte for
 * byte, so anything extra (a wrapper object, a renamed key, a number instead of a
 * string, stray whitespace) is a "wrong PIN" with no other symptom.
 */
class DashboardWireFormatTest {

    @Test
    fun `open sends exactly the typed PIN as a JSON string`() = runBlocking {
        var sentPath: String? = null
        var sentBody: String? = null
        var sentType: String? = null

        val capture = Interceptor { chain ->
            val request = chain.request()
            sentPath = request.url().encodedPath()
            sentType = request.body()?.contentType()?.toString()
            sentBody = Buffer().also { request.body()?.writeTo(it) }.readUtf8()
            okhttp3.Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    ResponseBody.create(
                        MediaType.parse("application/json"),
                        """{"open":true,"person":"alex","expires_in_s":300}"""
                    )
                )
                .build()
        }

        val service = Retrofit.Builder()
            .baseUrl("http://192.168.1.100:8500/")
            .client(OkHttpClient.Builder().addInterceptor(capture).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DashboardApiService::class.java)

        val response = service.openPresence(PresencePinRequest("0123"))

        assertEquals("/api/presence/open", sentPath)
        assertEquals("""{"pin":"0123"}""", sentBody)
        assertEquals("application/json; charset=UTF-8", sentType)
        // And an integer expires_in_s parses into the DTO.
        assertEquals(300.0, response.body()?.expiresInSeconds)
    }
}
