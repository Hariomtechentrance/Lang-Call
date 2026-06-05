import os
import sys
from datetime import datetime, timedelta, timezone
from typing import Optional

import bcrypt
from jose import JWTError, jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session

import models
from database import get_db

SECRET_KEY = os.getenv("SECRET_KEY", "")
ALGORITHM = "HS256"
TOKEN_EXPIRE_DAYS = 7           # was 30 — shorter window limits stolen-token damage
MAX_LOGIN_ATTEMPTS = 5          # lock after this many consecutive failures
LOCKOUT_MINUTES = 15            # how long an account stays locked

if not SECRET_KEY:
    if os.getenv("RENDER") or os.getenv("RAILWAY_ENVIRONMENT") or os.getenv("PRODUCTION"):
        # Hard-fail on production if SECRET_KEY is not set — prevents running with empty key
        sys.exit("FATAL: SECRET_KEY environment variable is not set. Set it in Render → Environment.")
    else:
        # Local development fallback — still warn loudly
        import logging
        logging.warning("WARNING: SECRET_KEY not set. Using insecure default for local dev only.")
        SECRET_KEY = "langcall-local-dev-only-do-not-use-in-prod"

bearer_scheme = HTTPBearer()


# ── Password utilities ───────────────────────────────────────────────────────

def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=12)).decode("utf-8")


def verify_password(plain: str, hashed: str) -> bool:
    try:
        return bcrypt.checkpw(plain.encode("utf-8"), hashed.encode("utf-8"))
    except Exception:
        return False


def validate_password_strength(password: str) -> None:
    """Raise HTTPException if the password doesn't meet minimum security requirements."""
    if len(password) < 8:
        raise HTTPException(400, "Password must be at least 8 characters long")
    if not any(c.isdigit() for c in password):
        raise HTTPException(400, "Password must contain at least one number (0-9)")
    if not any(c.isalpha() for c in password):
        raise HTTPException(400, "Password must contain at least one letter")


# ── JWT utilities ────────────────────────────────────────────────────────────

def create_token(user_id: int, token_version: int) -> str:
    """Create a JWT that includes token_version so it can be server-side invalidated on logout."""
    now = datetime.now(timezone.utc)
    expire = now + timedelta(days=TOKEN_EXPIRE_DAYS)
    return jwt.encode(
        {
            "sub": str(user_id),
            "ver": token_version,   # logout bumps this; old tokens with lower ver are rejected
            "iat": int(now.timestamp()),
            "exp": expire,
        },
        SECRET_KEY,
        algorithm=ALGORITHM,
    )


def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(bearer_scheme),
    db: Session = Depends(get_db),
) -> models.User:
    token = credentials.credentials
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        user_id = int(payload["sub"])
        token_ver = int(payload.get("ver", 0))
    except (JWTError, ValueError, KeyError):
        raise HTTPException(status_code=401, detail="Invalid or expired session. Please login again.")

    user = db.query(models.User).filter(models.User.id == user_id).first()
    if not user:
        raise HTTPException(status_code=401, detail="Account not found")

    # Token version check — any logout or password change bumps this, killing all older tokens
    if token_ver != (user.token_version or 0):
        raise HTTPException(status_code=401, detail="Session was revoked. Please login again.")

    return user


def decode_token_user_id(token: str) -> Optional[int]:
    """Used for WebSocket auth — returns user_id or None (no DB check here, just decode)."""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return int(payload["sub"])
    except Exception:
        return None
