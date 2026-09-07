"""Seed durable UI smoke data only inside an explicitly isolated Electron profile."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys


def seed_peer_image(data_dir: Path, image: Path) -> dict:
    smoke_dir = os.environ.get("GALAXYSSI_UI_SMOKE_DIR", "")
    if (os.environ.get("GALAXYSSI_UI_SMOKE") != "1"
            or os.environ.get("GALAXYSSI_DISABLE_EXTERNAL_SERVICES") != "1"
            or not smoke_dir):
        raise ValueError("Explicit isolated UI smoke environment required")
    profile_root = os.environ.get("GALAXYSSI_STATE_DIR") or smoke_dir
    expected = (Path(profile_root) / "user-data" / "runtime").resolve()
    if data_dir.resolve() != expected:
        raise ValueError("Refusing to seed outside the isolated smoke profile")
    if not image.is_file() or image.is_symlink():
        raise ValueError("Smoke image is unavailable")
    from peer_chat_store import PeerChatStore

    store = PeerChatStore(expected / "peer_chat.db")
    route_id, message_id = "s" * 22, "smoke-peer-image-message"
    if store.get_message(message_id) is None:
        attachment = store.import_attachment(
            client_route_id=route_id, message_id=message_id, source=image,
            name="GalaxySSI-photo.png", mime_type="image/png",
            sha256=hashlib.sha256(image.read_bytes()).hexdigest(),
        )
        store.append(client_route_id=route_id, message_id=message_id,
                     direction="inbound", sender_name="Smoke phone",
                     attachments=[attachment], delivery_status="received")
    return {"route_id": route_id, "message_id": message_id}


if __name__ == "__main__":
    print(json.dumps(seed_peer_image(Path(sys.argv[1]), Path(sys.argv[2]))))
