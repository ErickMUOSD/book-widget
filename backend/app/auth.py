import hashlib
import hmac
import secrets

from fastapi import Depends, Header, HTTPException
from sqlmodel import Session, select

from app.config import Settings, get_settings
from app.db import get_session
from app.models import Device


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_token() -> str:
    return "bw_" + secrets.token_urlsafe(32)


def _is_admin(token: str, settings: Settings) -> bool:
    return bool(settings.admin_token) and hmac.compare_digest(token, settings.admin_token)


def require_device(
    x_device_token: str = Header(default=""),
    session: Session = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> str:
    """Acepta el token maestro o el de un dispositivo dado de alta. Devuelve 'admin' | 'device'."""
    if not x_device_token:
        raise HTTPException(401, "Falta X-Device-Token")
    if _is_admin(x_device_token, settings):
        return "admin"
    if session.exec(select(Device).where(Device.token_hash == hash_token(x_device_token))).first():
        return "device"
    raise HTTPException(401, "Token inválido")


def require_admin(role: str = Depends(require_device)) -> None:
    if role != "admin":
        raise HTTPException(403, "Requiere el token maestro")
