package com.techentrance.languageapp.data

import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<TokenResponse>

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<TokenResponse>

    @GET("users/me")
    suspend fun getMe(@Header("Authorization") token: String): Response<UserResponse>

    @PUT("users/me/profile")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body body: UpdateProfileRequest,
    ): Response<UserResponse>

    @GET("users/phone/{phone}")
    suspend fun findByPhone(
        @Header("Authorization") token: String,
        @Path("phone") phone: String,
    ): Response<UserResponse>

    @POST("call/initiate")
    suspend fun initiateCall(
        @Header("Authorization") token: String,
        @Body body: InitiateCallRequest,
    ): Response<CallResponse>

    @GET("call/pending")
    suspend fun getPendingCalls(
        @Header("Authorization") token: String,
    ): Response<PendingCallsResponse>

    @POST("call/answer/{callId}")
    suspend fun answerCall(
        @Header("Authorization") token: String,
        @Path("callId") callId: Int,
    ): Response<Map<String, String>>

    @POST("auth/logout")
    suspend fun logout(
        @Header("Authorization") token: String,
    ): Response<Map<String, String>>

    @POST("call/record")
    suspend fun saveCallRecord(
        @Header("Authorization") token: String,
        @Body body: CallRecordRequest,
    ): Response<Map<String, String>>

    @GET("call/history")
    suspend fun getCallHistory(
        @Header("Authorization") token: String,
    ): Response<CallHistoryResponse>
}
