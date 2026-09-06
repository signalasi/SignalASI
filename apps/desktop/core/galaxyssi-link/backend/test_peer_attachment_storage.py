import hashlib
import tempfile
import unittest
from pathlib import Path

from peer_attachment_storage import PeerAttachmentError, PeerAttachmentStorage


class PeerAttachmentStorageTests(unittest.TestCase):
    def test_raw_storage_and_stream_across_chunk_boundaries(self):
        with tempfile.TemporaryDirectory() as root:
            source, destination = Path(root) / "source", Path(root) / "stored"
            data = bytes(range(256)) * 8193
            source.write_bytes(data)
            storage = PeerAttachmentStorage()
            size, digest = storage.store_file(source, destination, expected_sha256=hashlib.sha256(data).hexdigest())
            self.assertEqual(data, destination.read_bytes())
            self.assertEqual(data, b"".join(storage.read_stream(destination, expected_size=size, expected_sha256=digest)))
            self.assertEqual(data, source.read_bytes())

    def test_hash_failure_preserves_destination_and_cleans_staging(self):
        with tempfile.TemporaryDirectory() as root:
            source, destination = Path(root) / "source", Path(root) / "stored"
            source.write_bytes(b"actual")
            destination.write_bytes(b"existing")
            with self.assertRaises(PeerAttachmentError):
                PeerAttachmentStorage().store_file(source, destination, expected_sha256="0" * 64)
            self.assertEqual(b"existing", destination.read_bytes())
            self.assertFalse(list(Path(root).glob("*.tmp")))

    def test_truncated_or_modified_storage_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            source, destination = Path(root) / "source", Path(root) / "stored"
            source.write_bytes(b"original")
            storage = PeerAttachmentStorage()
            size, digest = storage.store_file(source, destination)
            for data in (b"changed!", b"short"):
                destination.write_bytes(data)
                with self.assertRaises(PeerAttachmentError):
                    b"".join(storage.read_stream(destination, expected_size=size, expected_sha256=digest))
