import io
import wave
import logging
import speech_recognition as sr
from languages import get_stt_code

logger = logging.getLogger(__name__)
recognizer = sr.Recognizer()
recognizer.energy_threshold = 300
recognizer.dynamic_energy_threshold = True


def transcribe_audio(audio_bytes: bytes, language: str) -> str:
    """Convert raw PCM16 audio (16kHz mono) to text for any Indian language."""
    lang_code = get_stt_code(language)

    wav_buffer = io.BytesIO()
    with wave.open(wav_buffer, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(16000)
        wf.writeframes(audio_bytes)
    wav_buffer.seek(0)

    with sr.AudioFile(wav_buffer) as source:
        audio_data = recognizer.record(source)

    try:
        text = recognizer.recognize_google(audio_data, language=lang_code)
        logger.info(f"[STT] {language} ({lang_code}): {text}")
        return text
    except sr.UnknownValueError:
        return ""
    except sr.RequestError as e:
        logger.error(f"[STT] Request error: {e}")
        return ""
