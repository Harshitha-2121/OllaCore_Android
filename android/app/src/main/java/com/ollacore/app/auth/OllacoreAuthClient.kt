package com.ollacore.app.auth

import com.ollacore.app.data.model.OtpResponse
import com.ollacore.app.data.model.OtpVerifyResponse
import com.ollacore.app.data.remote.OllacoreApi

class OllacoreAuthClient(
    private val appId: String
) {
    private val api = OllacoreApi(appId = appId)

    fun requestOtp(phone: String): OtpResponse = api.requestOtp(phone)

    fun verifyOtp(phone: String, code: String): OtpVerifyResponse = api.verifyOtp(phone, code)
}
