package com.techentrance.languageapp.data

data class RegisterRequest(
    val name: String,
    val email: String,
    val phone: String?,
    val password: String,
    val preferred_language: String,
)

data class LoginRequest(
    val email: String,
    val password: String,
)

data class UpdateProfileRequest(
    val preferred_language: String? = null,
    val phone: String? = null,
    val fcm_token: String? = null,
)

data class InitiateCallRequest(
    val callee_phone: String,
    val caller_language: String,
)

data class UserResponse(
    val id: Int,
    val name: String,
    val email: String,
    val phone: String?,
    val preferred_language: String,
    val created_at: String,
)

data class TokenResponse(
    val access_token: String,
    val token_type: String,
    val user: UserResponse,
)

data class CallResponse(
    val room_id: String,
    val callee_name: String,
    val callee_language: String,
    val message: String,
)

data class PendingCall(
    val call_id: Int,
    val room_id: String,
    val from_name: String,
    val from_language: String,
)

data class PendingCallsResponse(
    val calls: List<PendingCall>,
)

data class CallRecordRequest(
    val room_id: String,
    val caller_id: Int,
    val callee_id: Int,
    val caller_name: String,
    val callee_name: String,
    val caller_language: String,
    val callee_language: String,
    val duration_seconds: Int = 0,
)

data class CallHistoryItem(
    val id: Int,
    val room_id: String,
    val other_name: String,
    val other_language: String,
    val my_language: String,
    val direction: String,        // "incoming" or "outgoing"
    val duration_seconds: Int,
    val status: String = "completed",   // completed | missed | timeout
    val started_at: String,
)

data class CallHistoryResponse(
    val history: List<CallHistoryItem>,
)

data class ChangePasswordRequest(
    val current_password: String,
    val new_password: String,
)

data class ChangePasswordResponse(
    val status: String,
    val new_token: String,
)

data class BlockedUsersResponse(
    val blocked: List<String>,
)

val INDIAN_LANGUAGES = listOf(
    "hindi", "kannada", "tamil", "telugu", "bengali",
    "marathi", "gujarati", "malayalam", "punjabi",
    "odia", "urdu", "assamese", "nepali", "sindhi",
    "maithili", "konkani", "sanskrit",
)

val LANGUAGE_LABELS = mapOf(
    "hindi"     to "Hindi — हिन्दी",
    "kannada"   to "Kannada — ಕನ್ನಡ",
    "tamil"     to "Tamil — தமிழ்",
    "telugu"    to "Telugu — తెలుగు",
    "bengali"   to "Bengali — বাংলা",
    "marathi"   to "Marathi — मराठी",
    "gujarati"  to "Gujarati — ગુજરાતી",
    "malayalam" to "Malayalam — മലയാളം",
    "punjabi"   to "Punjabi — ਪੰਜਾਬੀ",
    "odia"      to "Odia — ଓଡ଼ିଆ",
    "urdu"      to "Urdu — اردو",
    "assamese"  to "Assamese — অসমীয়া",
    "nepali"    to "Nepali — नेपाली",
    "sindhi"    to "Sindhi — سنڌي",
    "maithili"  to "Maithili — मैथिली",
    "konkani"   to "Konkani — कोंकणी",
    "sanskrit"  to "Sanskrit — संस्कृतम्",
)
