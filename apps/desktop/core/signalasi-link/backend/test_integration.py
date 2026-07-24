"""Isolated integration contracts for the packaged SignalASI backend."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


BACKEND_DIR = Path(__file__).resolve().parent


class BackendIntegrationContractTest(unittest.TestCase):
    def run_isolated(self, source: str) -> None:
        with tempfile.TemporaryDirectory(prefix="signalasi-backend-test-") as temporary:
            state_dir = Path(temporary)
            environment = os.environ.copy()
            environment.update(
                {
                    "SIGNALASI_STATE_DIR": str(state_dir),
                    "SIGNALASI_DATA_DIR": str(state_dir / "pairing"),
                    "SIGNALASI_DATABASE_PATH": str(state_dir / "signalasi.db"),
                    "SIGNALASI_CONFIG_PATH": str(state_dir / "signalasi_agents.json"),
                }
            )
            result = subprocess.run(
                [sys.executable, "-c", textwrap.dedent(source)],
                cwd=BACKEND_DIR,
                env=environment,
                capture_output=True,
                text=True,
                timeout=30,
                check=False,
            )
            self.assertEqual(
                0,
                result.returncode,
                msg=f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}",
            )

    def test_packaged_runtime_modules_import(self) -> None:
        self.run_isolated(
            """
            modules = (
                "models", "agent_config", "api_response", "agent_gateway",
                "mqtt_bridge", "signalasi_client", "signalasi_notify",
                "pairing_state", "push_auth", "file_server",
                "custom_agent_stdio", "stt_bridge", "main",
            )
            for module in modules:
                __import__(module)
            """
        )

    def test_database_schema_initializes_in_isolated_state(self) -> None:
        self.run_isolated(
            """
            from sqlalchemy import inspect
            from models import engine, init_db

            init_db()
            tables = set(inspect(engine).get_table_names())
            assert {"contacts", "messages"}.issubset(tables), tables
            """
        )

    def test_database_path_does_not_copy_source_database(self) -> None:
        self.run_isolated(
            """
            import os
            from pathlib import Path
            import models

            state_dir = Path(os.environ["SIGNALASI_STATE_DIR"])
            source_dir = state_dir / "source"
            source_dir.mkdir(parents=True, exist_ok=True)
            (source_dir / "signalasi.db").write_bytes(b"obsolete-source-database")
            models.__file__ = str(source_dir / "models.py")

            target = state_dir / "current" / "signalasi.db"
            os.environ["SIGNALASI_DATABASE_PATH"] = str(target)
            assert models._database_path() == target
            assert not target.exists()
            """
        )

    def test_legacy_whisper_environment_is_ignored(self) -> None:
        self.run_isolated(
            """
            import os

            for key in (
                "SIGNALASI_WHISPER_MODEL",
                "SIGNALASI_WHISPER_DEVICE",
                "SIGNALASI_WHISPER_COMPUTE_TYPE",
            ):
                os.environ.pop(key, None)
            os.environ["HERMESCHAT_WHISPER_MODEL"] = "legacy-model"
            os.environ["HERMESCHAT_WHISPER_DEVICE"] = "legacy-device"
            os.environ["HERMESCHAT_WHISPER_COMPUTE_TYPE"] = "legacy-compute"

            import stt_bridge

            assert stt_bridge.MODEL_NAME == "medium"
            assert stt_bridge.DEVICE == "cpu"
            assert stt_bridge.COMPUTE_TYPE == "int8"
            """
        )

    def test_stable_api_response_contract(self) -> None:
        self.run_isolated(
            """
            from api_response import api_error, api_ok

            success = api_ok("ready", params={"agent": "codex"})
            assert success == {"ok": True, "code": "ready", "params": {"agent": "codex"}}
            failure = api_error("content_required")
            assert failure["ok"] is False
            assert failure["code"] == "content_required"
            assert failure["error"] == "content is required."
            """
        )

    def test_agent_registry_and_diagnostics_load_in_isolation(self) -> None:
        self.run_isolated(
            """
            from agent_config import load_config
            from agent_gateway import connector_diagnostics, list_agents

            assert isinstance(load_config(), dict)
            assert isinstance(list_agents(), list)
            assert isinstance(connector_diagnostics(), dict)
            """
        )

    def test_fastapi_surface_contains_current_contract_routes(self) -> None:
        self.run_isolated(
            """
            from main import app

            routes = {route.path for route in app.routes}
            required = {
                "/health",
                "/api/contacts",
                "/api/agents",
                "/api/agents/diagnostics",
                "/api/pairing/status",
                "/api/pairing/qr",
                "/api/desktop-tools",
                "/api/agent-adapters",
                "/signalasi/verify",
                "/ws/{contact_id}",
            }
            assert required.issubset(routes), required - routes
            """
        )

    def test_pairing_state_isolated_lifecycle(self) -> None:
        self.run_isolated(
            """
            from pairing_state import (
                clear_pairing_state,
                new_pairing_token,
                pairing_status,
                validate_pairing_token,
            )

            clear_pairing_state()
            assert pairing_status()["paired"] is False
            token = new_pairing_token()
            assert isinstance(token, str) and len(token) > 8
            status = pairing_status()
            assert status["token"]["active"] is True
            assert status["token"]["active_count"] == 1
            assert validate_pairing_token(token) is True
            """
        )


if __name__ == "__main__":
    unittest.main()
