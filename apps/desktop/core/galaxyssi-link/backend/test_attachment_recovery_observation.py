import unittest

from attachment_recovery_observation import observe_attachment_recovery
from attachment_request_broker import AttachmentRequestError, AttachmentTransferFailed
from model_recovery import ModelRecoveryAction, ModelRecoveryDecision


class AttachmentRecoveryObservationTest(unittest.TestCase):
    def setUp(self):
        self.decision = ModelRecoveryDecision(ModelRecoveryAction.REQUEST_ATTACHMENT, attachment_ids=("image",))
        self.events = []

    def test_verified_paths_keep_existing_success_follow_up(self):
        observation = observe_attachment_recovery(lambda: ["/task/input/photo.jpg"], on_failure=self.events.append)
        self.assertEqual(("/task/input/photo.jpg",), observation.paths)
        self.assertEqual([], self.events)
        self.assertIn("Available verified files:\n- /task/input/photo.jpg", observation.follow_up(self.decision))

    def test_transfer_failure_becomes_next_model_observation_not_success_or_exception(self):
        def failed():
            raise AttachmentTransferFailed("blob_expired")
        observation = observe_attachment_recovery(failed, on_failure=self.events.append)
        self.assertEqual((), observation.paths)
        self.assertEqual(["blob_expired"], self.events)
        prompt = observation.follow_up(self.decision)
        self.assertIn("blob_expired", prompt)
        self.assertIn("No verified attachment was delivered", prompt)
        self.assertNotIn("restored the attachment", prompt)

    def test_unrelated_runtime_and_journal_failures_are_not_swallowed(self):
        for error in (OSError("disk unavailable"), RuntimeError("scope mismatch"), AttachmentRequestError("timeout")):
            with self.subTest(kind=type(error).__name__):
                def failed():
                    raise error
                with self.assertRaises(type(error)):
                    observe_attachment_recovery(failed, on_failure=self.events.append)
        self.assertEqual([], self.events)

    def test_observation_persistence_must_succeed_before_model_continuation(self):
        def failed():
            raise AttachmentTransferFailed("blob_expired")
        def offline(_code):
            raise OSError("journal unavailable")
        with self.assertRaises(OSError):
            observe_attachment_recovery(failed, on_failure=offline)


if __name__ == "__main__":
    unittest.main()
