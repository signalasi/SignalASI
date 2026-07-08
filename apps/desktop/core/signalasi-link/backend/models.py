"""SignalASI Link data models."""
from datetime import datetime, timezone
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

DB_PATH = "signalasi.db"
engine = create_engine(f"sqlite:///{DB_PATH}", echo=False)
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
