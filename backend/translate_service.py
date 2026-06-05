import logging
from deep_translator import GoogleTranslator
from languages import get_tr_code

logger = logging.getLogger(__name__)


def translate_text(text: str, source_language: str, target_language: str) -> str:
    if not text.strip():
        return ""

    src = get_tr_code(source_language)
    tgt = get_tr_code(target_language)

    try:
        result = GoogleTranslator(source=src, target=tgt).translate(text)
        logger.info(f"[TRANSLATE] {source_language}→{target_language}: {result}")
        return result
    except Exception as e:
        logger.error(f"[TRANSLATE] Error: {e}")
        return text
