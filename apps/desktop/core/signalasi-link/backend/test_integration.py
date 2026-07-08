"""SignalASI Integration Test — imports, API health, DB, agent gateway."""
import sys, os, json, time, threading, traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

PASS = 0
FAIL = 0

def ok(msg):
    global PASS
    PASS += 1
    print(f"  PASS  {msg}")

def fail(msg, err=None):
    global FAIL
    FAIL += 1
    print(f"  FAIL  {msg}")
    if err:
        print(f"        {err}")
        traceback.print_exc()

def section(title):
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")

# ── 1. Module imports ──
section("Module imports")
modules = [
    "models", "agent_config", "api_response", "agent_gateway",
    "mqtt_bridge", "signalasi_client", "signalasi_notify",
    "pairing_state", "push_auth", "websocket", "file_server",
    "custom_agent_stdio", "stt_bridge", "main",
]
for m in modules:
    try:
        __import__(m)
        ok(f"import {m}")
    except Exception as e:
        fail(f"import {m}", e)

# ── 2. Database init ──
section("Database")
try:
    from models import init_db, get_session, Contact, Message, ContactType, MessageType, SenderType
    init_db()
    db = get_session()
    # verify tables exist
    tables = db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall()
    table_names = {r[0] for r in tables}
    for t in ["contacts", "messages"]:
        if t in table_names:
            ok(f"table '{t}' exists")
        else:
            fail(f"table '{t}' missing")
    db.close()
except Exception as e:
    fail("database init", e)

# ── 3. API response helpers ──
section("API response helpers")
try:
    from api_response import api_error, api_success
    err = api_error("test_code", "test detail")
    assert "error" in err, "api_error missing 'error' key"
    assert err["error"]["code"] == "test_code"
    ok("api_error format")
except Exception as e:
    fail("api_error", e)

# ── 4. Agent config load/save ──
section("Agent config")
try:
    from agent_config import load_config, save_config
    cfg = load_config()
    assert isinstance(cfg, dict), "load_config should return dict"
    ok("load_config")
    from agent_gateway import list_agents, connector_diagnostics
    agents = list_agents()
    assert isinstance(agents, list), "list_agents should return list"
    ok("list_agents")
    diag = connector_diagnostics()
    assert isinstance(diag, dict), "connector_diagnostics should return dict"
    ok("connector_diagnostics")
except Exception as e:
    fail("agent config/gateway", e)

# ── 5. HTTP API health check (start server) ──
section("HTTP API (live server)")
try:
    import uvicorn, httpx
except ImportError:
    try:
        os.system(f"{sys.executable} -m pip install httpx -q")
        import httpx
    except:
        httpx = None

if httpx:
    from main import app
    import uvicorn
    port = 19876
    server_thread = threading.Thread(
        target=uvicorn.run,
        args=(app,),
        kwargs={"host": "127.0.0.1", "port": port, "log_level": "error"},
        daemon=True,
    )
    server_thread.start()
    time.sleep(2)

    try:
        r = httpx.get(f"http://127.0.0.1:{port}/api/agents/diagnostics", timeout=5)
        if r.status_code == 200:
            ok("GET /api/agents/diagnostics")
        else:
            fail(f"GET /api/agents/diagnostics ({r.status_code})")
    except Exception as e:
        fail("GET /api/agents/diagnostics", e)

    try:
        r = httpx.get(f"http://127.0.0.1:{port}/api/agents", timeout=5)
        if r.status_code == 200:
            ok("GET /api/agents")
        else:
            fail(f"GET /api/agents ({r.status_code})")
    except Exception as e:
        fail("GET /api/agents", e)

    try:
        r = httpx.get(f"http://127.0.0.1:{port}/api/contacts", timeout=5)
        if r.status_code == 200:
            ok("GET /api/contacts")
        else:
            fail(f"GET /api/contacts ({r.status_code})")
    except Exception as e:
        fail("GET /api/contacts", e)
else:
    print("  SKIP  HTTP tests (httpx not available)")

# ── 6. Pairing state ──
section("Pairing state")
try:
    from pairing_state import pairing_status, new_pairing_token, clear_pairing_state
    s = pairing_status()
    assert "paired" in s, "pairing_status missing 'paired'"
    token = new_pairing_token()
    assert isinstance(token, str) and len(token) > 8
    ok("pairing_state lifecycle")
except Exception as e:
    fail("pairing_state", e)

# ── Summary ──
print(f"\n{'='*60}")
print(f"  RESULTS: {PASS} passed, {FAIL} failed")
print(f"{'='*60}")
sys.exit(0 if FAIL == 0 else 1)
