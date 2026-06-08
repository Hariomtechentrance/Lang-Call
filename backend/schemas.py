from typing import Optional
from pydantic import BaseModel, EmailStr, field_validator
from datetime import datetime


class UserRegister(BaseModel):
    name: str
    email: EmailStr
    phone: Optional[str] = None
    password: str
    preferred_language: str = "hindi"

    @field_validator("name")
    @classmethod
    def name_not_empty(cls, v: str) -> str:
        v = v.strip()
        if len(v) < 2:
            raise ValueError("Name must be at least 2 characters")
        return v

    @field_validator("password")
    @classmethod
    def password_strength(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("Password must be at least 8 characters")
        if not any(c.isdigit() for c in v):
            raise ValueError("Password must contain at least one number")
        if not any(c.isalpha() for c in v):
            raise ValueError("Password must contain at least one letter")
        return v


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
    status: str = "completed"   # completed | missed | timeout


class ChangePasswordRequest(BaseModel):
    current_password: str
    new_password: str

    @field_validator("new_password")
    @classmethod
    def new_password_strength(cls, v: str) -> str:
        if len(v) < 8:
            raise ValueError("New password must be at least 8 characters")
        if not any(c.isdigit() for c in v):
            raise ValueError("New password must contain at least one number")
        if not any(c.isalpha() for c in v):
            raise ValueError("New password must contain at least one letter")
        return v
