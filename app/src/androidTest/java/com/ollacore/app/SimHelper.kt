package com.ollacore.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// androidTest only. SIM_TOKEN never in main APK, scripts, or reports.
private val http = OkHttpClient()

fun lastOtp(simUrl: String, simToken: String, phone: String, since: Long): String? {
    val url = simUrl.toHttpUrl().newBuilder()
        .addPathSegment("last-otp")
        .addQueryParameter("phone", phone)              // encodes "+" as %2B via HttpUrl.Builder
        .addQueryParameter("since", since.toString())
        .build()
    val request = Request.Builder().url(url)
        .header("Authorization", "Bearer $simToken")
        .build()
    http.newCall(request).execute().use { resp ->
        val bodyStr = resp.body!!.string()
        val body = JSONObject(bodyStr)
        return when (resp.code) {
            200 -> body.getString("code")               // only 200 has code
            404 -> null                                 // {"error":"no_code"} / {"error":"no_fresh_code"}: keep polling
            else -> error("simulator ${resp.code}: ${body.optString("error")}")
        }
    }
}

// Example usage - keep capturing since BEFORE requesting the code
// @Test fun phoneSignupWithoutSms() = runBlocking {
//     val args = InstrumentationRegistry.getArguments()
//     val simUrl = args.getString("SIM_URL")!!
//     val simToken = args.getString("SIM_TOKEN")!! // from gradle.properties or CI secret
//     val phone = "+15550001111"
//     val api = OllacoreApi(appId = args.getString("APP_ID")!!)
//
//     val since = System.currentTimeMillis()               // BEFORE requesting the code
//     api.requestOtp(phone)
//
//     val code = withTimeout(30_000) {
//         var c: String? = null
//         while (c == null) {
//             c = withContext(Dispatchers.IO) { lastOtp(simUrl, simToken, phone, since) }
//             if (c == null) delay(1_000)
//         }
//         c
//     }
//     val session = api.verifyOtp(phone, code)      // du_...
//     assert(session.sessionToken.startsWith("du_"))
// }
