"""Standalone ciphertext relay. Put behind HTTPS; never expose Desktop control APIs."""
from __future__ import annotations

import argparse
import asyncio
from contextlib import asynccontextmanager, suppress
import json
import logging
import os
from pathlib import Path
import secrets
import sqlite3

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from blob_protocol import BlobError, CHUNK_BYTES, MAX_MANIFEST_BYTES, TAG_BYTES, checked_hex
from blob_store import BlobStore


def bearer(request: Request) -> str:
    value = request.headers.get("authorization", "")
    if not value.startswith("Bearer "):
        raise BlobError("authentication_required", 401)
    try:
        return checked_hex(value[7:])
    except BlobError:
        raise BlobError("authentication_required", 401) from None


async def bounded_body(request: Request, maximum: int, *, timeout: float = 60) -> bytes:
    if request.headers.get("content-encoding", "identity").lower() != "identity":
        raise BlobError("content_encoding_not_supported", 415)
    length = request.headers.get("content-length")
    if length is not None and (not length.isdecimal() or int(length) > maximum):
        raise BlobError("body_too_large", 413)
    result = bytearray()
    try:
        async with asyncio.timeout(timeout):
            async for chunk in request.stream():
                if len(result) + len(chunk) > maximum:
                    raise BlobError("body_too_large", 413)
                result.extend(chunk)
    except TimeoutError:
        raise BlobError("body_timeout", 408) from None
    return bytes(result)


def create_app(store: BlobStore, provisioning_token: str, *, max_bulk_requests: int = 4) -> FastAPI:
    checked_hex(provisioning_token)
    if not 1 <= max_bulk_requests <= 64:
        raise ValueError("Invalid relay concurrency")
    slots = asyncio.Semaphore(max_bulk_requests)

    @asynccontextmanager
    async def lifespan(_app):
        async def maintain():
            while True:
                try:
                    await run_in_threadpool(store.collect)
                except (OSError, sqlite3.Error):
                    logging.getLogger(__name__).warning("Blob maintenance failed; retrying next interval")
                await asyncio.sleep(60)
        maintenance = asyncio.create_task(maintain())
        try:
            yield
        finally:
            maintenance.cancel()
            with suppress(asyncio.CancelledError):
                await maintenance

    app = FastAPI(title="GalaxySSI encrypted Blob relay", lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def privacy_headers(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @app.exception_handler(BlobError)
    async def blob_error(_request, error):
        return JSONResponse({"error": error.code}, status_code=error.status)

    @app.put("/v1/blobs/{session_id}")
    async def create(session_id: str, request: Request):
        if not secrets.compare_digest(bearer(request), provisioning_token):
            raise BlobError("authentication_required", 401)
        async with slots:
            raw = await bounded_body(request, MAX_MANIFEST_BYTES)
            try:
                body = json.loads(raw)
            except (ValueError, UnicodeError):
                raise BlobError("invalid_json") from None
            if not isinstance(body, dict) or set(body) != {"manifest", "read_token", "write_token"}:
                raise BlobError("invalid_creation_request")
            return await run_in_threadpool(store.create, session_id, body["manifest"],
                                          body["read_token"], body["write_token"])

    @app.get("/v1/blobs/{session_id}")
    async def get_manifest(session_id: str, request: Request):
        return await run_in_threadpool(store.get_manifest, session_id, bearer(request))

    @app.get("/v1/blobs/{session_id}/missing")
    async def missing(session_id: str, request: Request):
        return await run_in_threadpool(store.status, session_id, bearer(request))

    @app.put("/v1/blobs/{session_id}/chunks/{index}")
    async def put_chunk(session_id: str, index: int, request: Request):
        token = bearer(request)
        await run_in_threadpool(store.authorize, session_id, token, write=True)
        async with slots:
            data = await bounded_body(request, CHUNK_BYTES + TAG_BYTES)
            inserted = await run_in_threadpool(store.put, session_id, token, index, data)
            return {"stored": True, "inserted": inserted}

    @app.get("/v1/blobs/{session_id}/chunks/{index}")
    async def get_chunk(session_id: str, index: int, request: Request):
        async with slots:
            data = await run_in_threadpool(store.get, session_id, bearer(request), index)
        return Response(content=data, media_type="application/octet-stream")

    @app.delete("/v1/blobs/{session_id}")
    async def delete(session_id: str, request: Request):
        await run_in_threadpool(store.delete, session_id, bearer(request))
        return Response(status_code=204)

    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18766)
    parser.add_argument("--quota-gib", type=int, default=10)
    parser.add_argument("--max-sessions", type=int, default=10000)
    parser.add_argument("--ttl-hours", type=int, default=168)
    parser.add_argument("--concurrency", type=int, default=4)
    args = parser.parse_args()
    token = os.environ.get("GALAXYSSI_BLOB_PROVISION_TOKEN", "")
    if not token:
        parser.error("Set GALAXYSSI_BLOB_PROVISION_TOKEN to a random 32-byte hex secret")
    import uvicorn
    store = BlobStore(args.database, quota_bytes=args.quota_gib * 1024**3,
                      max_sessions=args.max_sessions, ttl_seconds=args.ttl_hours * 3600)
    app = create_app(store, token, max_bulk_requests=args.concurrency)
    uvicorn.run(app, host=args.host, port=args.port, access_log=False,
                limit_concurrency=args.concurrency + 32)


if __name__ == "__main__":
    main()
