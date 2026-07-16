"""SignalASI Link data models."""
from datetime import datetime, timezone
from pathlib import Path
import os
import shutil
from sqlalchemy import create_engine, Column, Integer, String, Text, DateTime, Enum as SAEnum
from sqlalchemy.orm import declarative_base, sessionmaker
import enum

Base = declarative_base()

class ContactType(str, enum.Enum):
    AI = "ai"
    BOT = "bot"
    SELF = "self"

class MessageType(str, enum.Enum):
    TEXT = "text"
    IMAGE = "image"
    VOICE = "voice"
    SYSTEM = "system"

class SenderType(str, enum.Enum):
    SELF = "self"
    OTHER = "other"

class Contact(Base):
    __tablename__ = "contacts"
    id = Column(String(64), primary_key=True)
    name = Column(String(128), nullable=False)
    avatar = Column(String(8), default="🤖")
    type = Column(SAEnum(ContactType), default=ContactType.AI)
    status = Column(String(64), default="")
    preview = Column(String(256), default="")
    unread = Column(Integer, default=0)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

class Message(Base):
    __tablename__ = "messages"
    id = Column(Integer, primary_key=True, autoincrement=True)
    contact_id = Column(String(64), index=True, nullable=False)
    sender = Column(SAEnum(SenderType), nullable=False)
    content = Column(Text, default="")
    type = Column(SAEnum(MessageType), default=MessageType.TEXT)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

def _database_path() -> Path:
    configured = os.environ.get("SIGNALASI_DATABASE_PATH", "").strip()
    state_directory = os.environ.get("SIGNALASI_STATE_DIR", "").strip()
    root = Path(state_directory) if state_directory else Path(os.environ.get("APPDATA") or Path.home()) / "SignalASI"
    target = Path(configured) if configured else root / "signalasi.db"
    target.parent.mkdir(parents=True, exist_ok=True)
    legacy = Path(__file__).with_name("signalasi.db")
    if not target.exists() and legacy.exists() and legacy.resolve() != target.resolve():
        shutil.copy2(legacy, target)
    return target


DB_PATH = _database_path()
engine = create_engine(f"sqlite:///{DB_PATH.as_posix()}", echo=False)
SessionLocal = sessionmaker(bind=engine)

def init_db():
    Base.metadata.create_all(engine)
    seed_contacts()

def seed_contacts():
    session = SessionLocal()
    defaults = [
        Contact(id="hermes", name="Hermes", avatar="🤖", type=ContactType.AI,
                status="online", preview="Connected and ready"),
        Contact(id="system", name="System Notifications", avatar="🔔", type=ContactType.BOT,
                status="", preview="System is running normally", unread=1),
        Contact(id="codex", name="Codex Agent", avatar="⚡", type=ContactType.BOT,
                status="idle", preview="Waiting for tasks..."),
        Contact(id="claude", name="Claude Agent", avatar="🎯", type=ContactType.BOT,
                status="idle", preview="Code review ready"),
        Contact(id="local-llm", name="Local LLM", avatar="🧠", type=ContactType.BOT,
                status="needs setup", preview="Connect Ollama or a local OpenAI-compatible service"),
        Contact(id="me", name="Me (this device)", avatar="👤", type=ContactType.SELF,
                status="", preview="Voice input is ready"),
    ]
    try:
        for contact in defaults:
            if session.get(Contact, contact.id) is None:
                session.add(contact)
        session.commit()
    finally:
        session.close()

def get_session():
    return SessionLocal()
