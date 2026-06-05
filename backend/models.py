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
    fcm_token = Column(String, nullable=True)       # Firebase push token (optional)
    is_online = Column(Boolean, default=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


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
