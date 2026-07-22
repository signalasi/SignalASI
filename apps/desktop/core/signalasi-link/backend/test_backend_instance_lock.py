import os
import unittest
import uuid

from backend_instance_lock import BackendInstanceAlreadyRunning, BackendInstanceLock


class BackendInstanceLockTest(unittest.TestCase):
    def test_only_one_process_owner_can_hold_the_external_service_lock(self) -> None:
        name = f"SignalASI.Backend.Test.{os.getpid()}.{uuid.uuid4()}"
        first = BackendInstanceLock(name)
        second = BackendInstanceLock(name)
        replacement = BackendInstanceLock(name)
        try:
            first.acquire()
            with self.assertRaises(BackendInstanceAlreadyRunning):
                second.acquire()
            first.release()
            replacement.acquire()
        finally:
            replacement.release()
            second.release()
            first.release()


if __name__ == "__main__":
    unittest.main()
