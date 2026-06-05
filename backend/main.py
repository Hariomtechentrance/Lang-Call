"""
LangCall Backend — Full Indian Language Translation Call Server
---------------------------------------------------------------
REST:
  POST /auth/register        — register with phone + language
  POST /auth/login           — login
  GET  /users/me             — get profile
  PUT  /users/me/profile     — update language / phone / fcm_token
  GET  /users/phone/{phone}  — find user by phone number
  POST /call/initiate        — start a call to someone by phone number
  GET  /call/pending         — check for incoming calls (polling fallback)
  POST /call/answer/{id}     — mark call as answered

WebSocket:
  /ws/{room_id}?token=…     — translation call (audio ↔ audio)
  /notify?token=…            — receive incoming call notifications
"""
import asyncio
import base64
import hashlib
import json
import logging
import re
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Dict, List, Optional

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

import models
import schemas
from auth import (create_token, decode_token_user_id, get_current_user,
                   hash_password, verify_password, validate_password_strength,
                   MAX_LOGIN_ATTEMPTS, LOCKOUT_MINUTES)
from database import Base, engine, get_db
from languages import LANGUAGE_NAMES, is_valid
from stt_service import transcribe_audio
from translate_service import translate_text
from tts_service import synthesize_speech

load_dotenv()
logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

Base.metadata.create_all(bind=engine)


def run_migrations():
    """Add any missing columns to existing tables — works for both SQLite and PostgreSQL."""
    from sqlalchemy import inspect, text
    inspector = inspect(engine)
    if "users" not in inspector.get_table_names():
        return  # Fresh DB — create_all already created everything correctly
    existing = {col["name"] for col in inspector.get_columns("users")}
    additions = []
    if "phone" not in existing:
        additions.append("ALTER TABLE users ADD COLUMN phone VARCHAR")
    if "fcm_token" not in existing:
        additions.append("ALTER TABLE users ADD COLUMN fcm_token VARCHAR")
    if "is_online" not in existing:
        additions.append("ALTER TABLE users ADD COLUMN is_online BOOLEAN DEFAULT FALSE")
    if "token_version" not in existing:
        additions.append("ALTER TABLE users ADD COLUMN token_version INTEGER DEFAULT 0")
    if "failed_login_attempts" not in existing:
        additions.append("ALTER TABLE users ADD COLUMN failed_login_attempts INTEGER DEFAULT 0")
    if "locked_until" not in existing:
        additions.append("ALTER TABLE users ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE")
    if additions:
        with engine.connect() as conn:
            for sql in additions:
                conn.execute(text(sql))
                logger.info(f"Migration: {sql}")
            conn.commit()


run_migrations()

app = FastAPI(title="LangCall API", version="2.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

executor = ThreadPoolExecutor(max_workers=10)

# room_id → {slot: ws, lang_slot: language}
rooms: Dict[str, dict] = {}
# user_id → notification WebSocket
notify_sockets: Dict[int, WebSocket] = {}


# ───────────────────────── HELPERS ─────────────────────────

_PHONE_RE = re.compile(r"^\+?[0-9]{7,15}$")

def normalize_phone(raw: str) -> str:
    """Strip spaces/dashes and validate basic format.  Raises 400 on bad input."""
    clean = re.sub(r"[\s\-\(\)]", "", raw.strip())
    if not _PHONE_RE.match(clean):
        raise HTTPException(400, "Invalid phone number format (digits only, 7-15 chars, optional leading +)")
    return clean


def make_room_id(phone_a: str, phone_b: str) -> str:
    """Deterministic room ID from two phone numbers + timestamp shard."""
    phones = sorted([phone_a.strip(), phone_b.strip()])
    shard = str(int(time.time()) // 120)          # changes every 2 min so old calls don't conflict
    h = hashlib.md5(f"{phones[0]}_{phones[1]}_{shard}".encode()).hexdigest()[:10]
    return f"call_{h}"


async def push_notification(user_id: int, payload: dict):
    ws = notify_sockets.get(user_id)
    if ws:
        try:
            await ws.send_text(json.dumps(payload))
        except Exception:
            notify_sockets.pop(user_id, None)


# ───────────────────────── AUTH ─────────────────────────

@app.post("/auth/register", response_model=schemas.TokenResponse, status_code=201)
def register(payload: schemas.UserRegister, db: Session = Depends(get_db)):
    # Server-side validation (cannot be bypassed by skipping the app)
    validate_password_strength(payload.password)

    # Normalise email to lowercase
    email = payload.email.strip().lower()

    if db.query(models.User).filter(models.User.email == email).first():
        raise HTTPException(400, "Email already registered")

    phone = None
    if payload.phone:
        phone = normalize_phone(payload.phone)
        if db.query(models.User).filter(models.User.phone == phone).first():
            raise HTTPException(400, "Phone number already registered")

    user = models.User(
        name=payload.name.strip(),
        email=email,
        phone=phone,
        password_hash=hash_password(payload.password),
        preferred_language=payload.preferred_language,
        token_version=0,
        failed_login_attempts=0,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    logger.info(f"New user registered: id={user.id} email={email}")
    return schemas.TokenResponse(
        access_token=create_token(user.id, user.token_version),
        user=schemas.UserResponse.model_validate(user),
    )


@app.post("/auth/login", response_model=schemas.TokenResponse)
def login(payload: schemas.UserLogin, db: Session = Depends(get_db)):
    email = payload.email.strip().lower()
    user = db.query(models.User).filter(models.User.email == email).first()

    # ── Account lockout check ────────────────────────────────────────────────
    if user and user.locked_until:
        if datetime.now(timezone.utc) < user.locked_until.replace(tzinfo=timezone.utc):
            mins_left = int((user.locked_until.replace(tzinfo=timezone.utc) - datetime.now(timezone.utc)).total_seconds() / 60) + 1
            raise HTTPException(
                429,
                f"Too many failed attempts. Account locked for {mins_left} more minute(s)."
            )
        else:
            # Lockout expired — reset
            user.failed_login_attempts = 0
            user.locked_until = None
            db.commit()

    # ── Credential check ─────────────────────────────────────────────────────
    if not user or not verify_password(payload.password, user.password_hash):
        if user:
            attempts = (user.failed_login_attempts or 0) + 1
            user.failed_login_attempts = attempts
            if attempts >= MAX_LOGIN_ATTEMPTS:
                from datetime import timedelta
                user.locked_until = datetime.now(timezone.utc) + timedelta(minutes=LOCKOUT_MINUTES)
                user.failed_login_attempts = 0
                db.commit()
                raise HTTPException(
                    429,
                    f"Too many failed attempts. Account locked for {LOCKOUT_MINUTES} minutes."
                )
            db.commit()
            remaining = MAX_LOGIN_ATTEMPTS - attempts
            raise HTTPException(401, f"Incorrect password. {remaining} attempt(s) remaining before lockout.")
        raise HTTPException(401, "No account found with that email address.")

    # ── Success ───────────────────────────────────────────────────────────────
    user.failed_login_attempts = 0
    user.locked_until = None
    db.commit()
    db.refresh(user)
    logger.info(f"Login: user id={user.id}")
    return schemas.TokenResponse(
        access_token=create_token(user.id, user.token_version or 0),
        user=schemas.UserResponse.model_validate(user),
    )


@app.post("/auth/logout")
def logout(
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Invalidates the current token by bumping token_version. All existing tokens for this user stop working."""
    current_user.token_version = (current_user.token_version or 0) + 1
    db.commit()
    logger.info(f"Logout: user id={current_user.id}, new token_version={current_user.token_version}")
    return {"status": "logged out"}


# ───────────────────────── USER ─────────────────────────

@app.get("/users/me", response_model=schemas.UserResponse)
def get_me(current_user: models.User = Depends(get_current_user)):
    return current_user


@app.put("/users/me/profile", response_model=schemas.UserResponse)
def update_profile(
    payload: schemas.UpdateProfileRequest,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    if payload.preferred_language and not is_valid(payload.preferred_language):
        raise HTTPException(400, f"Unsupported language. Supported: {LANGUAGE_NAMES}")
    if payload.preferred_language:
        current_user.preferred_language = payload.preferred_language
    if payload.phone:
        clean_phone = normalize_phone(payload.phone)
        existing = db.query(models.User).filter(models.User.phone == clean_phone).first()
        if existing and existing.id != current_user.id:
            raise HTTPException(400, "Phone already in use")
        current_user.phone = clean_phone
    if payload.fcm_token:
        current_user.fcm_token = payload.fcm_token
    db.commit()
    db.refresh(current_user)
    return current_user


@app.get("/users/phone/{phone}", response_model=schemas.UserResponse)
def find_by_phone(
    phone: str,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    user = db.query(models.User).filter(models.User.phone == phone).first()
    if not user:
        raise HTTPException(404, "No LangCall user found with that phone number")
    return user


@app.get("/languages")
def get_languages():
    from languages import LANGUAGES
    return {"languages": {k: v["label"] for k, v in LANGUAGES.items()}}


# ───────────────────────── CALL ─────────────────────────

@app.post("/call/initiate", response_model=schemas.CallResponse)
async def initiate_call(
    payload: schemas.InitiateCallRequest,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    callee = db.query(models.User).filter(models.User.phone == payload.callee_phone).first()
    if not callee:
        raise HTTPException(404, "No LangCall user found with that phone number")
    if callee.id == current_user.id:
        raise HTTPException(400, "You cannot call yourself")

    caller_phone = current_user.phone or str(current_user.id)
    room_id = make_room_id(caller_phone, payload.callee_phone)

    pending = models.PendingCall(
        room_id=room_id,
        caller_id=current_user.id,
        caller_name=current_user.name,
        caller_language=payload.caller_language,
        callee_id=callee.id,
    )
    db.add(pending)
    db.commit()
    db.refresh(pending)

    # Push real-time notification to callee via notify WebSocket
    await push_notification(callee.id, {
        "type": "incoming_call",
        "call_id": pending.id,
        "room_id": room_id,
        "from_name": current_user.name,
        "from_language": payload.caller_language,
        "from_phone": current_user.phone or "",
    })

    return schemas.CallResponse(
        room_id=room_id,
        callee_name=callee.name,
        callee_language=callee.preferred_language,
        message=f"Calling {callee.name}…",
    )


@app.get("/call/pending")
def get_pending_calls(
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Polling fallback — check for unanswered incoming calls."""
    calls = (
        db.query(models.PendingCall)
        .filter(models.PendingCall.callee_id == current_user.id,
                models.PendingCall.answered == False)  # noqa: E712
        .order_by(models.PendingCall.created_at.desc())
        .limit(5)
        .all()
    )
    return {"calls": [
        {"call_id": c.id, "room_id": c.room_id,
         "from_name": c.caller_name, "from_language": c.caller_language}
        for c in calls
    ]}


@app.post("/call/record")
def save_call_record(
    payload: schemas.CallRecordRequest,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Called by the client when a call ends to log it in history."""
    from datetime import datetime, timezone
    record = models.CallRecord(
        room_id=payload.room_id,
        caller_id=payload.caller_id,
        callee_id=payload.callee_id,
        caller_name=payload.caller_name,
        callee_name=payload.callee_name,
        caller_language=payload.caller_language,
        callee_language=payload.callee_language,
        duration_seconds=payload.duration_seconds,
        ended_at=datetime.now(timezone.utc),
    )
    db.add(record)
    db.commit()
    return {"status": "saved"}


@app.get("/call/history")
def get_call_history(
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """Return the last 50 calls the current user was part of."""
    records = (
        db.query(models.CallRecord)
        .filter(
            (models.CallRecord.caller_id == current_user.id) |
            (models.CallRecord.callee_id == current_user.id)
        )
        .order_by(models.CallRecord.started_at.desc())
        .limit(50)
        .all()
    )
    result = []
    for r in records:
        is_caller = r.caller_id == current_user.id
        result.append({
            "id": r.id,
            "room_id": r.room_id,
            "other_name": r.callee_name if is_caller else r.caller_name,
            "other_language": r.callee_language if is_caller else r.caller_language,
            "my_language": r.caller_language if is_caller else r.callee_language,
            "direction": "outgoing" if is_caller else "incoming",
            "duration_seconds": r.duration_seconds,
            "started_at": r.started_at.isoformat() if r.started_at else "",
        })
    return {"history": result}


@app.post("/call/answer/{call_id}")
def answer_call(
    call_id: int,
    current_user: models.User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    call = db.query(models.PendingCall).filter(
        models.PendingCall.id == call_id,
        models.PendingCall.callee_id == current_user.id,
    ).first()
    if not call:
        raise HTTPException(404, "Call not found")
    call.answered = True
    db.commit()
    return {"room_id": call.room_id, "caller_language": call.caller_language}


# ───────────────────────── NOTIFY WEBSOCKET ─────────────────────────

@app.websocket("/notify")
async def notify_ws(
    websocket: WebSocket,
    token: Optional[str] = Query(default=None),
):
    """Persistent connection for incoming call notifications (no room needed)."""
    user_id = decode_token_user_id(token) if token else None
    if user_id is None:
        await websocket.close(code=4001)
        return

    await websocket.accept()
    notify_sockets[user_id] = websocket
    logger.info(f"Notify socket connected: user {user_id}")

    try:
        while True:
            # Keep alive — client sends pings, server echoes
            msg = await websocket.receive_text()
            if msg == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    finally:
        notify_sockets.pop(user_id, None)
        logger.info(f"Notify socket disconnected: user {user_id}")


# ───────────────────────── CALL WEBSOCKET ─────────────────────────

def opposite_language(room: dict, slot: str) -> str:
    other = "user_b" if slot == "user_a" else "user_a"
    return room.get(f"lang_{other}", "hindi")


async def run_pipeline(audio_bytes: bytes, speaker_lang: str, target_lang: str, target_ws: WebSocket):
    loop = asyncio.get_event_loop()
    transcript = await loop.run_in_executor(executor, transcribe_audio, audio_bytes, speaker_lang)
    if not transcript:
        return
    translated = await loop.run_in_executor(executor, translate_text, transcript, speaker_lang, target_lang)
    audio_out = await loop.run_in_executor(executor, synthesize_speech, translated, target_lang)
    payload = {
        "type": "audio",
        "original": transcript,
        "translated": translated,
        "audio_b64": base64.b64encode(audio_out).decode() if audio_out else "",
    }
    try:
        await target_ws.send_text(json.dumps(payload))
    except Exception:
        pass


@app.websocket("/ws/{room_id}")
async def ws_call(
    websocket: WebSocket,
    room_id: str,
    token: Optional[str] = Query(default=None),
):
    user_id = decode_token_user_id(token) if token else None
    if user_id is None:
        await websocket.close(code=4001)
        return

    await websocket.accept()

    try:
        init_raw = await asyncio.wait_for(websocket.receive_text(), timeout=10)
        init = json.loads(init_raw)
        language = init.get("language", "hindi")
    except Exception:
        await websocket.close(code=4002)
        return

    if room_id not in rooms:
        rooms[room_id] = {}
    room = rooms[room_id]

    if "user_a" not in room:
        slot, other_slot = "user_a", "user_b"
    elif "user_b" not in room:
        slot, other_slot = "user_b", "user_a"
    else:
        await websocket.send_text(json.dumps({"type": "error", "msg": "Room is full"}))
        await websocket.close()
        return

    room[slot] = websocket
    room[f"lang_{slot}"] = language
    logger.info(f"Room {room_id}: {slot} joined ({language})")
    await websocket.send_text(json.dumps({"type": "joined", "slot": slot, "language": language}))

    # Notify the other participant that their peer has arrived
    if slot == "user_b":
        peer_ws: Optional[WebSocket] = room.get("user_a")
        if peer_ws:
            try:
                await peer_ws.send_text(json.dumps({"type": "peer_joined"}))
            except Exception:
                pass

    try:
        while True:
            audio_bytes = await websocket.receive_bytes()
            room = rooms.get(room_id, {})
            other_ws: Optional[WebSocket] = room.get(other_slot)
            if other_ws is None:
                continue
            target_lang = room.get(f"lang_{other_slot}", "hindi")
            asyncio.create_task(run_pipeline(audio_bytes, language, target_lang, other_ws))
    except WebSocketDisconnect:
        logger.info(f"Room {room_id}: {slot} disconnected")
    finally:
        if room_id in rooms:
            rooms[room_id].pop(slot, None)
            rooms[room_id].pop(f"lang_{slot}", None)
            if not rooms[room_id]:
                del rooms[room_id]


@app.get("/health")
def health():
    return {"status": "ok", "rooms": len(rooms), "online_users": len(notify_sockets)}
