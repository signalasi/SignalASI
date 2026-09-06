from dataclasses import replace
import time
import unittest

from attachment_request_broker import AttachmentRequestBroker, AttachmentRequestError
from blob_failures import TERMINAL_BLOB_ERRORS, failure_observation
from input_attachment_transfer import AttachmentTransferReceipt
from link_protocol import new_route_id


class BlobFailureObservationTest(unittest.TestCase):
    def setUp(self):
        self.broker = AttachmentRequestBroker()
        self.identity = dict(client_route_id=new_route_id(), conversation_id="conversation",
                             task_id="task", turn_id="turn", contact_id="codex", source_message_id="42")

    def receipt(self, request, **changes):
        return AttachmentTransferReceipt(
            **{**self.identity, **changes}, transfer_id="a" * 64, sha256="b" * 64,
            attachment_id="image", attachment_request_id=request["request_id"],
            name="image.jpg", mime_type="image/jpeg", size_bytes=123,
            status="failed", error_code="blob_expired")

    def request(self, publish):
        return self.broker.request(**self.identity, attachment_ids=["image"],
                                   reason="Inspect the original image", publish=publish, timeout_seconds=20)

    def test_terminal_receipt_unblocks_model_observation_without_timeout(self):
        def publish(request):
            self.assertTrue(self.broker.accept_receipt(self.receipt(request)))
            return True
        before = time.monotonic()
        with self.assertRaisesRegex(AttachmentRequestError, "blob_expired.*No verified attachment"):
            self.request(publish)
        self.assertLess(time.monotonic() - before, 1)

    def test_every_identity_field_is_required_before_accepting_failure(self):
        def publish(request):
            for field in self.identity:
                with self.subTest(field=field):
                    self.assertFalse(self.broker.accept_receipt(self.receipt(request, **{field: "other"})))
            self.assertFalse(self.broker.accept_receipt(replace(self.receipt(request), attachment_id="other")))
            self.assertFalse(self.broker.accept_receipt(replace(self.receipt(request), attachment_request_id="other")))
            self.assertTrue(self.broker.accept_receipt(self.receipt(request)))
            return True
        with self.assertRaisesRegex(AttachmentRequestError, "blob_expired"):
            self.request(publish)

    def test_failure_is_sticky_against_late_success_and_result(self):
        def publish(request):
            failed = self.receipt(request)
            self.assertTrue(self.broker.accept_receipt(failed))
            self.assertFalse(self.broker.accept_receipt(replace(failed, status="stored", error_code="")))
            self.assertFalse(self.broker.accept_result({**request, "status": "stored"},
                                                      client_route_id=request["client_route_id"]))
            return True
        with self.assertRaisesRegex(AttachmentRequestError, "blob_expired"):
            self.request(publish)

    def test_completed_success_is_not_replaced_by_late_failure(self):
        def publish(request):
            receipt = self.receipt(request)
            self.assertTrue(self.broker.accept_receipt(replace(receipt, status="stored", error_code="")))
            self.assertFalse(self.broker.accept_receipt(receipt))
            return True
        self.assertEqual("image", self.request(publish)[0]["id"])

    def test_arbitrary_provider_text_is_not_forwarded_as_error(self):
        def publish(request):
            invalid = replace(self.receipt(request), error_code="secret-token /private/path")
            self.assertFalse(self.broker.accept_receipt(invalid))
            with self.assertRaises(ValueError):
                invalid.payload()
            self.assertTrue(self.broker.accept_receipt(self.receipt(request)))
            return True
        with self.assertRaisesRegex(AttachmentRequestError, "blob_expired"):
            self.request(publish)

    def test_failure_codes_are_bounded_and_transient_errors_are_not_terminal(self):
        for code in TERMINAL_BLOB_ERRORS:
            self.assertLess(len(failure_observation(code)), 300)
        for code in ("relay_timeout", "relay_connection_failed", "chunk_not_ready", "body_timeout",
                     "relay_storage_capacity", "paired_identity_unavailable", "transfer_cancelled", "unknown"):
            self.assertNotIn(code, TERMINAL_BLOB_ERRORS)
            with self.assertRaises(ValueError):
                failure_observation(code)

    def test_phone_source_failure_result_also_returns_typed_observation(self):
        from attachment_request_broker import AttachmentTransferFailed
        def publish(request):
            self.assertFalse(self.broker.accept_result({**request, "status": "failed", "error_code": "secret"},
                                                      client_route_id=request["client_route_id"]))
            self.assertTrue(self.broker.accept_result(
                {**request, "status": "failed", "error_code": "blob_source_missing", "error": "private path"},
                client_route_id=request["client_route_id"]))
            return True
        with self.assertRaises(AttachmentTransferFailed) as raised:
            self.request(publish)
        self.assertEqual("blob_source_missing", raised.exception.code)
        self.assertNotIn("private path", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
