"""
All supported Indian languages with their STT, translation, and TTS codes.
STT  = Google Web Speech API language code
TR   = Google Translate language code
TTS  = gTTS language code (None = falls back to closest supported language)
"""

LANGUAGES: dict = {
    "hindi":     {"stt": "hi-IN", "tr": "hi", "tts": "hi",  "label": "हिन्दी (Hindi)"},
    "kannada":   {"stt": "kn-IN", "tr": "kn", "tts": "kn",  "label": "ಕನ್ನಡ (Kannada)"},
    "tamil":     {"stt": "ta-IN", "tr": "ta", "tts": "ta",  "label": "தமிழ் (Tamil)"},
    "telugu":    {"stt": "te-IN", "tr": "te", "tts": "te",  "label": "తెలుగు (Telugu)"},
    "bengali":   {"stt": "bn-IN", "tr": "bn", "tts": "bn",  "label": "বাংলা (Bengali)"},
    "marathi":   {"stt": "mr-IN", "tr": "mr", "tts": "mr",  "label": "मराठी (Marathi)"},
    "gujarati":  {"stt": "gu-IN", "tr": "gu", "tts": "gu",  "label": "ગુજરાતી (Gujarati)"},
    "malayalam": {"stt": "ml-IN", "tr": "ml", "tts": "ml",  "label": "മലയാളം (Malayalam)"},
    "punjabi":   {"stt": "pa-IN", "tr": "pa", "tts": "pa",  "label": "ਪੰਜਾਬੀ (Punjabi)"},
    "odia":      {"stt": "or-IN", "tr": "or", "tts": "hi",  "label": "ଓଡ଼ିଆ (Odia)"},      # gTTS has no Odia — TTS→Hindi fallback
    "urdu":      {"stt": "ur-IN", "tr": "ur", "tts": "ur",  "label": "اردو (Urdu)"},
    "assamese":  {"stt": "as-IN", "tr": "as", "tts": "bn",  "label": "অসমীয়া (Assamese)"},  # TTS→Bengali (closest)
    "maithili":  {"stt": "mai",   "tr": "mai","tts": "hi",  "label": "मैथिली (Maithili)"},   # TTS→Hindi fallback
    "konkani":   {"stt": "kok",   "tr": "kok","tts": "mr",  "label": "कोंकणी (Konkani)"},    # TTS→Marathi fallback
    "sindhi":    {"stt": "sd-IN", "tr": "sd", "tts": "ur",  "label": "سنڌي (Sindhi)"},       # TTS→Urdu fallback
    "nepali":    {"stt": "ne-IN", "tr": "ne", "tts": "ne",  "label": "नेपाली (Nepali)"},
    "sanskrit":  {"stt": "sa-IN", "tr": "sa", "tts": "hi",  "label": "संस्कृतम् (Sanskrit)"},# TTS→Hindi fallback
}

# Note: Rajasthani (Marwari) has no ISO code in Google's system — use Hindi
# for translation. Users selecting Hindi will cover Rajasthani speakers.

LANGUAGE_NAMES = list(LANGUAGES.keys())


def get_stt_code(language: str) -> str:
    return LANGUAGES.get(language, LANGUAGES["hindi"])["stt"]


def get_tr_code(language: str) -> str:
    return LANGUAGES.get(language, LANGUAGES["hindi"])["tr"]


def get_tts_code(language: str) -> str:
    return LANGUAGES.get(language, LANGUAGES["hindi"])["tts"]


def is_valid(language: str) -> bool:
    return language in LANGUAGES
