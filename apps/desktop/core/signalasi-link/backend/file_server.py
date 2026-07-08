"""SignalASI Link file service."""
import logging
import os
import shutil
import uuid
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse

log = logging.getLogger("signalasi.files")

FILES_DIR = Path.home() / "signalasi_files"
FILES_DIR.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="SignalASI Files")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    """Upload a file and return a file id."""
    ext = os.path.splitext(file.filename or "file")[1] or ""
    file_id = f"{uuid.uuid4().hex[:12]}{ext}"
    save_path = FILES_DIR / file_id

    with open(save_path, "wb") as f:
        shutil.copyfileobj(file.file, f)

    file_size = save_path.stat().st_size
    log.info("File uploaded: %s (%s, %s bytes)", file_id, file.filename, file_size)

    return {
        "file_id": file_id,
        "name": file.filename,
        "size": file_size,
        "content_type": file.content_type or "application/octet-stream",
    }


@app.get("/download/{file_id}")
async def download_file(file_id: str):
    """Download a file by id."""
    if "/" in file_id or "\\" in file_id or ".." in file_id:
        return JSONResponse({"error": "invalid file_id"}, status_code=400)

    file_path = FILES_DIR / file_id
    if not file_path.exists():
        return JSONResponse({"error": "file not found"}, status_code=404)

    return FileResponse(str(file_path))


@app.get("/files")
async def list_files():
    """List uploaded files."""
    files = []
    for item in sorted(FILES_DIR.iterdir(), key=lambda value: value.stat().st_mtime, reverse=True):
        if item.is_file():
            files.append({
                "file_id": item.name,
                "size": item.stat().st_size,
                "created_at": item.stat().st_mtime,
            })
    return files


def start(host="0.0.0.0", port=18765):
    log.info("File service started on %s:%s", host, port)
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    start()
