"""Loopback operator controls for a paired device's encrypted Blob settings."""
from fastapi import APIRouter, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from pydantic import BaseModel, ConfigDict, SecretStr, StrictBool, StrictInt

from blob_protocol import BlobError
from secure_state import SecureStateError

class RedactedValidationRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()
        async def redacted(request):
            try:
                return await handler(request)
            except RequestValidationError:
                raise HTTPException(status_code=422, detail={"error": "invalid_blob_configuration"}) from None
        return redacted


router = APIRouter(prefix="/api/blob/settings", tags=["blob-settings"], route_class=RedactedValidationRoute)


class BlobSettingsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    identity_fingerprint: str
    identity_binding: str
    expected_revision: StrictInt
    enabled: StrictBool
    origin: str = ""
    provisioning_token: SecretStr | None = None


def _bridge(request):
    from main import require_loopback
    require_loopback(request)
    import mqtt_bridge
    return mqtt_bridge


def _error(error):
    if isinstance(error, BlobError):
        return HTTPException(status_code=error.status, detail={"error": error.code})
    return HTTPException(status_code=503, detail={"error": "blob_configuration_storage_failed"})


@router.get("/{route}")
def get_settings(route: str, request: Request):
    from blob_pair_configuration import public_settings
    bridge = _bridge(request)
    try:
        return public_settings(bridge, route)
    except (BlobError, SecureStateError, OSError) as error:
        raise _error(error) from None


@router.put("/{route}")
def put_settings(route: str, req: BlobSettingsRequest, request: Request):
    from blob_pair_configuration import update_settings
    from blob_configuration import publish_configuration
    bridge = _bridge(request)
    try:
        result = update_settings(bridge, route, identity_fingerprint=req.identity_fingerprint,
            identity_binding=req.identity_binding,
            expected_revision=req.expected_revision, enabled=req.enabled, origin=req.origin,
            provisioning_token=req.provisioning_token.get_secret_value() if req.provisioning_token is not None else None)
    except (BlobError, SecureStateError, OSError) as error:
        raise _error(error) from None
    # Persistence succeeds independently of broker availability. A later authenticated
    # capability request replays the saved revision if queueing is not possible now.
    try:
        result["configuration_queued"] = publish_configuration(bridge, bridge.client, route, requested=False)
    except Exception:
        result["configuration_queued"] = False
    return result
