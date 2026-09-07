import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from peer_attachment_storage import PeerAttachmentError, PeerAttachmentStorage


class PeerAttachmentStorageTests(unittest.TestCase):
    def test_staging_name_does_not_expand_a_long_valid_destination(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / "source"
            source.write_bytes(b"image")
            parent = Path(root) / ("d" * max(1, 205 - len(str(Path(root))) - 1))
            destination = parent / ("0123456789abcdef" * 2 + ".sasi")
            opened = []
            original_open = Path.open

            def record_open(path, mode="r", *args, **kwargs):
                if mode == "xb":
                    opened.append(path)
                return original_open(path, mode, *args, **kwargs)

            with patch.object(Path, "open", record_open):
                PeerAttachmentStorage().store_file(source, destination)
            self.assertEqual(b"image", destination.read_bytes())
            self.assertEqual(1, len(opened))
            self.assertEqual(destination.parent, opened[0].parent)
            self.assertLessEqual(len(opened[0].name), 37)
            self.assertNotIn(destination.name, opened[0].name)
            self.assertFalse(opened[0].exists())

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
