const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidMqttClient = path.join(
  workspaceRoot,
  "android",
  "app",
  "src",
  "main",
  "java",
  "com",
  "signalasi",
  "chat",
  "SignalASIMqttClient.kt"
);

function log(message) {
  console.log(`[mqtt-persistence] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

function findPython() {
  const candidates = [
    path.join(root, ".runtime-python", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ];
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate)) || "python";
}

function assertAndroidPersistentSessionConfig() {
  const source = fs.readFileSync(androidMqttClient, "utf8");
  const required = [
    "isCleanSession = false",
    "isAutomaticReconnect = true",
    "private const val MQTT_QOS = 1",
    "stableClientId()",
    "signalasi-android-$identity",
    "SignalASICrypto.localIdentitySha256().take(16)",
    "SignalASILinkProtocol.allServerLinks(appContext ?: return).forEach { subscribeLink(it) }",
    "mqtt.subscribe(link.routes.down, MQTT_QOS)",
    "mqtt.subscribe(link.routes.control, MQTT_QOS)"
  ];
  for (const marker of required) {
    if (!source.includes(marker)) {
      fail(`Android MQTT persistence config is missing: ${marker}`);
    }
  }
}

function runBrokerPersistenceProbe() {
  const code = String.raw`
import sys
import threading
import time
import uuid
import warnings

import paho.mqtt.client as mqtt

warnings.filterwarnings("ignore", category=DeprecationWarning)

BROKER = "broker.emqx.io"
PORT = 1883
CLIENT_ID = "signalasi-offline-smoke-" + uuid.uuid4().hex
TOPIC = "signalasi/offline-smoke/" + CLIENT_ID
PAYLOAD = "offline-qos1-" + uuid.uuid4().hex

subscribed = threading.Event()
received = []
message_event = threading.Event()

def make_client(client_id, clean_session=False):
    try:
        return mqtt.Client(client_id=client_id, clean_session=clean_session, protocol=mqtt.MQTTv311)
    except TypeError:
        return mqtt.Client(mqtt.CallbackAPIVersion.VERSION1, client_id=client_id, clean_session=clean_session, protocol=mqtt.MQTTv311)

def on_connect(client, userdata, flags, rc, *extra):
    if rc != 0:
        print("connect_rc=" + str(rc), file=sys.stderr)
        return
    client.subscribe(TOPIC, qos=1)

def on_subscribe(client, userdata, mid, granted_qos, *extra):
    subscribed.set()

def on_message(client, userdata, message):
    text = message.payload.decode("utf-8", "replace")
    received.append(text)
    if text == PAYLOAD:
        message_event.set()

subscriber = make_client(CLIENT_ID, clean_session=False)
subscriber.on_connect = on_connect
subscriber.on_subscribe = on_subscribe
subscriber.connect(BROKER, PORT, 30)
subscriber.loop_start()
if not subscribed.wait(12):
    subscriber.loop_stop()
    subscriber.disconnect()
    raise SystemExit("initial_subscribe_timeout")
subscriber.disconnect()
subscriber.loop_stop()

publisher = make_client(CLIENT_ID + "-publisher", clean_session=True)
publisher.connect(BROKER, PORT, 30)
publisher.loop_start()
info = publisher.publish(TOPIC, PAYLOAD, qos=1, retain=False)
info.wait_for_publish(timeout=12)
publisher.disconnect()
publisher.loop_stop()

subscriber = make_client(CLIENT_ID, clean_session=False)
subscriber.on_connect = on_connect
subscriber.on_subscribe = on_subscribe
subscriber.on_message = on_message
subscriber.connect(BROKER, PORT, 30)
subscriber.loop_start()
ok = message_event.wait(15)
subscriber.disconnect()
subscriber.loop_stop()

if not ok:
    raise SystemExit("offline_message_not_delivered")

print("offline_qos1_delivery_ok topic=" + TOPIC)
`;

  execFileSync(findPython(), ["-c", code], {
    cwd: root,
    stdio: "inherit",
    windowsHide: true
  });
}

async function main() {
  log("checking Android persistent MQTT session settings");
  assertAndroidPersistentSessionConfig();
  log("probing broker QoS1 offline queue with persistent client id");
  runBrokerPersistenceProbe();
  log("MQTT persistent delivery smoke OK");
}

withSignalasiLock("smoke:mqtt-persistence", main).catch((error) => {
  console.error(`[mqtt-persistence] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
