from sqlalchemy import Column, Integer, String, DateTime, Boolean
from sqlalchemy.sql import func
from database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, nullable=False)
    email = Column(String, unique=True, index=True, nullable=False)
    phone = Column(String, unique=True, index=True, nullable=True)
    password_hash = Column(String, nullable=False)
    preferred_language = Column(String, default="hindi")
    fcm_token = Column(String, nullable=True)
    is_online = Column(Boolean, default=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    # Security fields
    token_version = Column(Integer, default=0, nullable=False)          # incremented on logout to invalidate all old tokens
    failed_login_attempts = Column(Integer, default=0, nullable=False)  # brute-force counter
    locked_until = Column(DateTime(timezone=True), nullable=True)       # set for 15 min after 5 failures


class PendingCall(Base):
    __tablename__ = "pending_calls"

    id = Column(Integer, primary_key=True, index=True)
    room_id = Column(String, nullable=False)
    caller_id = Column(Integer, nullable=False)
    caller_name = Column(String, nullable=False)
    caller_language = Column(String, nullable=False)
    callee_id = Column(Integer, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    answered = Column(Boolean, default=False)


class CallRecord(Base):
    __tablename__ = "call_records"

    id = Column(Integer, primary_key=True, index=True)
    room_id = Column(String, nullable=False, index=True)
    caller_id = Column(Integer, nullable=False)
    callee_id = Column(Integer, nullable=False)
    caller_name = Column(String, nullable=False)
    callee_name = Column(String, nullable=False)
    caller_language = Column(String, nullable=False)
    callee_language = Column(String, nullable=False)
    duration_seconds = Column(Integer, default=0)
    started_at = Column(DateTime(timezone=True), server_default=func.now())
    ended_at = Column(DateTime(timezone=True), nullable=True)
