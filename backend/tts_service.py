import io
import logging
from gtts import gTTS
from languages import get_tts_code

logger = logging.getLogger(__name__)


def synthesize_speech(text: str, language: str) -> bytes:
    if not text.strip():
        return b""

    lang_code = get_tts_code(language)
    try:
        tts = gTTS(text=text, lang=lang_code, slow=False)
        buf = io.BytesIO()
        tts.write_to_fp(buf)
        buf.seek(0)
        return buf.read()
    except Exception as e:
        logger.error(f"[TTS] {language} ({lang_code}) error: {e}")
        # Fallback: try Hindi if original fails
        try:
            tts = gTTS(text=text, lang="hi", slow=False)
            buf = io.BytesIO()
            tts.write_to_fp(buf)
            buf.seek(0)
            return buf.read()
        except Exception:
            return b""
