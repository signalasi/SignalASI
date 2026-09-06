"""Production output callbacks with isolated encrypted settings and durable stores."""
from contextlib import ExitStack
import io
import threading
import time
from unittest.mock import Mock, patch

import blob_artifact_bridge as adapter
from blob_artifact_contract import artifact_binding
from blob_artifact_journal import BlobArtifactJournal
from blob_crypto import StagedBlob
import blob_pair_configuration as settings
from blob_protocol import BlobError
from secure_state import read_secure_json
from test_blob_artifact_journal import artifact_job
from test_blob_pair_configuration import PairConfigurationFixture


class BlobArtifactBridgeTests(PairConfigurationFixture):
    def setUp(self):
        super().setUp()
        self.bridge.phone_publish_lock = threading.RLock()
        self.bridge.outbound_status = Mock(return_value=None)
        for route, peer in self.peers.items():
            peer["client_route_id"] = route
        peer = self.peers[self.routes[0]]
        self.body = artifact_job(desktop_id="desktop", size_bytes=3, original_size_bytes=3,
                                 sha256=adapter.sha256(b"abc"))
        self.body.update(origin="https://relay.test", source_id=peer["signal_name"],
                         peer_fingerprint=peer["identity_fingerprint"], local_fingerprint=peer["local_identity_fingerprint"])
        self.addCleanup(self.close_runtime)

    def close_runtime(self):
        adapter.stop(self.bridge)
        self.assertTrue(adapter.wait_stopped(self.bridge, 10))
        with adapter._lock:
            runtime = adapter._runtimes.pop(adapter._root(self.bridge), None)
            adapter._starters.pop(adapter._root(self.bridge), None)
            adapter._creation_locks.pop(adapter._root(self.bridge), None)
        if runtime is not None:
            self.assertTrue(runtime.sender.wait_stopped(10))

    def enable(self):
        self.save()
        settings.record_artifact_capability(self.bridge, self.routes[0], self.body["source_id"], {
            "type": settings.ARTIFACT_CAPABILITY_TYPE, "version": 1, "revision": 1, "enabled": True,
            "client_route_id": self.routes[0], "desktop_id": "desktop", "desktop_fingerprint": "f" * 64})

    def offer(self, runtime, revision=1):
        staged = StagedBlob.prepare_stream(lambda: io.BytesIO(b"abc"), self.root / "staged",
            artifact_binding(self.body["manifest"]), size=3, digest=self.body["manifest"]["sha256"])
        return {"type": "artifact_blob_offer", "version": 1, "manifest": self.body["manifest"],
                "transport_revision": revision, "blob_offer": {
                    "version": 1, "relay": self.body["origin"], "private": staged.private, "read_token": "a" * 64}}

    def test_start_stop_and_receipt_wake_without_journal_create_nothing(self):
        adapter.start(self.bridge)
        adapter.wake(self.bridge)
        adapter.stop(self.bridge)
        self.assertFalse(adapter._root(self.bridge).exists())
        self.assertNotIn(adapter._root(self.bridge), adapter._runtimes)

    def test_start_recovers_existing_jobs_without_enabling_disabled_relay(self):
        path = adapter._root(self.bridge) / "artifact-jobs.sqlite3"
        journal = BlobArtifactJournal(path)
        journal.enqueue(self.body, now=0)
        old = journal.claim_due(1, now=0)[0]
        adapter.start(self.bridge)
        runtime = adapter._get(self.bridge)
        deadline = time.monotonic() + 5
        while journal.current(old) and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse(journal.current(old))
        adapter.stop(self.bridge)
        self.assertTrue(runtime.sender.wait_stopped(5))
        self.assertEqual({"pending": 1}, journal.snapshot())
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_live_settings_do_not_confuse_upload_opt_in_with_output_capability(self):
        runtime = adapter._get(self.bridge)
        self.save()
        settings.private_settings(self.bridge, self.routes[0], requested=True)
        self.assertFalse(runtime.settings(self.body)["enabled"])
        self.enable()
        self.assertTrue(runtime.settings(self.body)["enabled"])
        self.save(provisioning_token="d" * 64)
        self.assertEqual("d" * 64, runtime.settings(self.body)["provisioning_token"])

    def test_slow_initialization_does_not_block_start_or_receipt_wakeup_and_stop_fences_it(self):
        BlobArtifactJournal(adapter._root(self.bridge) / "artifact-jobs.sqlite3")
        entered, release = threading.Event(), threading.Event()
        original = adapter.BlobArtifactRuntime
        def delayed(bridge):
            entered.set()
            if not release.wait(5):
                raise RuntimeError("test did not release initializer")
            return original(bridge)
        with patch.object(adapter, "BlobArtifactRuntime", side_effect=delayed):
            try:
                before = time.monotonic()
                adapter.start(self.bridge)
                self.assertLess(time.monotonic() - before, .5)
                self.assertTrue(entered.wait(2))
                before = time.monotonic()
                adapter.wake(self.bridge)
                adapter.stop(self.bridge)
                self.assertLess(time.monotonic() - before, .5)
            finally:
                release.set()
            self.assertTrue(adapter.wait_stopped(self.bridge, 5))
        self.assertIsNone(adapter._get(self.bridge).sender._thread)

    def test_initialization_failure_retries_off_the_mqtt_thread(self):
        BlobArtifactJournal(adapter._root(self.bridge) / "artifact-jobs.sqlite3")
        original = adapter.BlobArtifactRuntime
        attempts = []
        completed = threading.Event()
        def initialize(bridge):
            attempts.append(threading.current_thread().name)
            if len(attempts) == 1:
                raise OSError("temporary disk failure")
            runtime = original(bridge)
            runtime.sender.start = Mock(side_effect=lambda: completed.set() is None)
            return runtime
        with patch.object(adapter, "BlobArtifactRuntime", side_effect=initialize):
            adapter.start(self.bridge)
            self.assertTrue(completed.wait(5))
        self.assertEqual(2, len(attempts))
        self.assertTrue(all(name == "galaxyssi-blob-output-init" for name in attempts))

    def test_restart_waits_for_old_worker_instead_of_losing_recovery_request(self):
        runtime = adapter._get(self.bridge)
        release, restarted = threading.Event(), threading.Event()
        old = threading.Thread(target=lambda: release.wait(5), daemon=True)
        runtime.sender._thread = old
        old.start()
        runtime.sender.stop()
        with patch.object(runtime.sender, "_run", side_effect=restarted.set):
            try:
                adapter.start(self.bridge)
                self.assertFalse(restarted.wait(.1))
                self.assertIs(old, runtime.sender._thread)
            finally:
                release.set()
                old.join(5)
            self.assertTrue(restarted.wait(5))
        adapter.stop(self.bridge)
        self.assertTrue(adapter.wait_stopped(self.bridge, 5))

    def test_actual_mqtt_start_hook_keeps_ordinary_workers_independent_of_blob_initialization(self):
        import blob_input_bridge
        import mqtt_bridge
        BlobArtifactJournal(adapter._root(self.bridge) / "artifact-jobs.sqlite3")
        entered, release = threading.Event(), threading.Event()
        original = adapter.BlobArtifactRuntime
        def delayed(bridge):
            entered.set()
            if not release.wait(5):
                raise RuntimeError("test did not release initializer")
            return original(bridge)
        was_stopped = mqtt_bridge.mqtt_lifecycle_stop_event.is_set()
        with ExitStack() as stack:
            stack.enter_context(patch.object(mqtt_bridge, "DATA_DIR", self.root))
            stack.enter_context(patch.object(blob_input_bridge, "start"))
            helpers = {name: stack.enter_context(patch.object(mqtt_bridge, name)) for name in (
                "_ensure_task_event_publisher", "_ensure_delivery_ack_publisher", "_ensure_presence_thread",
                "_ensure_outbound_retry_thread", "_ensure_codex_warm_thread", "_ensure_transport_probe_thread",
                "_ensure_mqtt_worker", "_ensure_mqtt_supervisor")}
            stack.enter_context(patch.object(adapter, "BlobArtifactRuntime", side_effect=delayed))
            try:
                mqtt_bridge.start_background()
                self.assertTrue(entered.wait(2))
                self.assertFalse(release.is_set())
                for helper in helpers.values():
                    helper.assert_called_once_with()
            finally:
                adapter.stop(mqtt_bridge)
                release.set()
                self.assertTrue(adapter.wait_stopped(mqtt_bridge, 5))
                if was_stopped:
                    mqtt_bridge.mqtt_lifecycle_stop_event.set()

    def test_disable_pauses_and_reenable_resumes_without_identity_or_origin_failure(self):
        self.enable()
        runtime = adapter._get(self.bridge)
        self.save(enabled=False)
        with self.assertRaisesRegex(BlobError, "relay_disabled"):
            runtime.sender._settings(self.body)
        self.assertEqual("phone-0", runtime.sender._settings(self.body, require_relay=False)["source_id"])
        self.enable()
        self.assertTrue(runtime.sender._settings(self.body)["enabled"])

    def test_identity_replacement_revocation_and_desktop_change_block_old_delivery(self):
        runtime = adapter._get(self.bridge)
        peer = self.peers[self.routes[0]]
        for changes in ({"signal_name": "new-phone"}, {"identity_fingerprint": "0" * 64},
                        {"local_identity_fingerprint": "0" * 64}, {"revoked": True},
                        {"client_route_id": self.routes[1]}):
            with self.subTest(changes=changes), patch.dict(peer, changes), self.assertRaisesRegex(BlobError, "identity_changed"):
                runtime.peer(self.body)
        with patch.object(self.bridge, "desktop_id", return_value="other"), self.assertRaisesRegex(BlobError, "identity_changed"):
            runtime.peer(self.body)

    def test_offer_has_stable_envelope_id_and_explicit_scope_without_source_path_or_provision_token(self):
        runtime = adapter._get(self.bridge)
        offer = self.offer(runtime)
        runtime.publish_offer(self.body, offer)
        first = self.bridge._publish_to_registered_client.call_args.args[2]
        runtime.publish_offer(self.body, offer)
        self.assertEqual(first, self.bridge._publish_to_registered_client.call_args.args[2])
        for key in ("task_id", "turn_id", "conversation_id", "execution_generation", "client_route_id"):
            self.assertEqual(self.body["manifest"][key], first[key])
        self.assertNotIn("source_relative", first)
        self.assertNotIn("provisioning_token", first)
        runtime.publish_offer(self.body, {**offer, "transport_revision": 2})
        self.assertNotEqual(first["message_id"], self.bridge._publish_to_registered_client.call_args.args[2]["message_id"])

    def test_failed_control_queue_is_not_reported_as_success_or_reencrypted(self):
        runtime = adapter._get(self.bridge)
        offer = self.offer(runtime)
        self.bridge.outbound_status.return_value = "failed"
        with self.assertRaisesRegex(BlobError, "control_delivery_exhausted"):
            runtime.publish_offer(self.body, offer)
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_failed_task_observation_is_durable_without_agent_task_lookup(self):
        runtime = adapter._get(self.bridge)
        self.assertTrue(runtime.observe_failure(self.body, "artifact_source_missing"))
        paths = list((runtime.root / "incidents").glob("*.secure.json"))
        self.assertEqual(1, len(paths))
        incident = read_secure_json(paths[0], purpose=adapter._INCIDENT_PURPOSE).value
        self.assertEqual(self.body["manifest"], incident["manifest"])
        self.assertNotIn(self.body["manifest"]["artifact_uri"], paths[0].read_text())
        first = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertTrue(first["blob_publication"])
        runtime.observe_failure(self.body, "artifact_source_missing")
        self.assertEqual(first, self.bridge._publish_to_registered_client.call_args.args[2])
        self.assertEqual(1, len(list((runtime.root / "incidents").glob("*.secure.json"))))

    def test_failed_incident_write_does_not_mark_failure_observed_or_publish(self):
        runtime = adapter._get(self.bridge)
        with patch.object(adapter, "write_secure_json", side_effect=OSError("disk full")), self.assertRaises(OSError):
            runtime.observe_failure(self.body, "artifact_source_missing")
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_revoked_peer_keeps_local_failure_but_never_receives_old_scope(self):
        runtime = adapter._get(self.bridge)
        self.peers.clear()
        self.assertTrue(runtime.observe_failure(self.body, "artifact_blob_identity_changed"))
        self.assertEqual(1, len(list((runtime.root / "incidents").glob("*.secure.json"))))
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_quarantine_does_not_invent_scope_or_send_private_diagnostics(self):
        runtime = adapter._get(self.bridge)
        runtime.observe_quarantine("1" * 64, "secret-token\nraw diagnostic")
        path = runtime.root / "incidents" / ("1" * 64 + ".secure.json")
        value = read_secure_json(path, purpose=adapter._INCIDENT_PURPOSE).value
        self.assertEqual("artifact_blob_transfer_failed", value["code"])
        self.assertNotIn("manifest", value)
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_enqueue_commits_held_batch_before_starting_worker(self):
        self.enable()
        runtime = adapter._get(self.bridge)
        observed = []
        with patch.object(runtime.sender, "start", side_effect=lambda: observed.append(runtime.sender.journal.snapshot()) is None):
            batch = adapter.enqueue(self.bridge, [self.body])
            self.assertEqual(batch, adapter.enqueue(self.bridge, [self.body]))
        self.assertEqual([{"held": 1}, {"held": 1}], observed)

    def test_real_bridge_persists_control_ciphertext_when_broker_is_offline(self):
        import link_delivery
        import mqtt_bridge
        runtime = adapter._get(self.bridge)
        offer = self.offer(runtime)
        self.bridge.client = None
        self.bridge._publish_to_registered_client = mqtt_bridge._publish_to_registered_client
        self.bridge.outbound_status = link_delivery.outbound_status
        with ExitStack() as stack:
            stack.enter_context(patch.object(link_delivery, "DB_PATH", self.root / "delivery.sqlite3"))
            stack.enter_context(patch.object(mqtt_bridge, "desktop_id", self.bridge.desktop_id))
            stack.enter_context(patch.object(mqtt_bridge, "_topics_for_client", return_value=Mock(send="isolated-test-topic")))
            encrypt = stack.enter_context(patch.object(mqtt_bridge, "encrypt_signal_payload", return_value={"body": "ciphertext"}))
            stack.enter_context(patch.object(mqtt_bridge, "transport_timing", Mock()))
            self.assertTrue(runtime.publish_offer(self.body, offer))
            envelope = encrypt.call_args.args[0]
            self.assertEqual("queued", link_delivery.outbound_status(self.routes[0], envelope["message_id"]))
            self.assertTrue(runtime.publish_offer(self.body, offer))
            self.assertEqual(1, encrypt.call_count)
            self.assertEqual(self.body["manifest"]["conversation_id"], envelope["conversation_id"])
