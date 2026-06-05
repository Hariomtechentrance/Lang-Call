from typing import Optional
from pydantic import BaseModel, EmailStr
from datetime import datetime


class UserRegister(BaseModel):
    name: str
    email: EmailStr
    phone: Optional[str] = None
    password: str
    preferred_language: str = "hindi"


class UserLogin(BaseModel):
    email: EmailStr
    password: str


class UserResponse(BaseModel):
    id: int
    name: str
    email: str
    phone: Optional[str]
    preferred_language: str
    created_at: datetime

    model_config = {"from_attributes": True}


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserResponse


class UpdateProfileRequest(BaseModel):
    preferred_language: Optional[str] = None
    phone: Optional[str] = None
    fcm_token: Optional[str] = None


class InitiateCallRequest(BaseModel):
    callee_phone: str          # phone number of person to call
    caller_language: str       # language the caller speaks


class CallResponse(BaseModel):
    room_id: str
    callee_name: str
    callee_language: str
    message: str


class CallRecordRequest(BaseModel):
    room_id: str
    caller_id: int
    callee_id: int
    caller_name: str
    callee_name: str
    caller_language: str
    callee_language: str
    duration_seconds: int = 0
