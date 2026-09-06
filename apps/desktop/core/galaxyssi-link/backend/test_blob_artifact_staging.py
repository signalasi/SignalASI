import hashlib
import io
from pathlib import Path
import tempfile
import tracemalloc
import unittest

from blob_crypto import STATE_FILE, StagedBlob
from blob_protocol import BlobError, CHUNK_BYTES, sha256

BINDING = {"transfer_id": "a" * 64, "task_id": "task"}


class RepeatingStream(io.RawIOBase):
    def __init__(self, size, *, partial=65521):
        self.remaining = size
        self.partial = partial
        self.maximum_requested = 0
        self.position = 0

    def read(self, size=-1):
        if size < 0 or size > CHUNK_BYTES:
            raise AssertionError("Unbounded artifact read")
        self.maximum_requested = max(self.maximum_requested, size)
        count = min(size, self.partial, self.remaining)
        self.remaining -= count
        self.position += count
        return b"X" * count


class BlobArtifactStagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="blob-artifact-staging-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_152_mib_stream_uses_bounded_memory_and_reopens_authenticated_chunks(self):
        size = 152 * CHUNK_BYTES
        digest = hashlib.sha256()
        block = b"X" * CHUNK_BYTES
        for _ in range(152):
            digest.update(block)
        stream = RepeatingStream(size)
        tracemalloc.start()
        try:
            staged = StagedBlob.prepare_stream(lambda: stream, self.root / "large", BINDING,
                                                size=size, digest=digest.hexdigest())
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertTrue(stream.closed)
        self.assertLessEqual(stream.maximum_requested, CHUNK_BYTES)
        self.assertLess(peak, 12 * CHUNK_BYTES)
        self.assertEqual(152, len(staged.public["chunks"]))
        reopened = StagedBlob.open(staged.directory, BINDING)
        actual = hashlib.sha256()
        total = 0
        for plain in reopened.plaintext(BINDING):
            self.assertLessEqual(len(plain), CHUNK_BYTES)
            total += len(plain)
            actual.update(plain)
        self.assertEqual(size, total)
        self.assertEqual(digest.hexdigest(), actual.hexdigest())

    def test_short_reads_and_empty_stream_roundtrip_without_plaintext_files(self):
        for size in (0, 1, CHUNK_BYTES + 7):
            with self.subTest(size=size):
                raw = b"X" * size
                staged = StagedBlob.prepare_stream(lambda: RepeatingStream(size, partial=17),
                    self.root / str(size), BINDING, size=size, digest=sha256(raw))
                self.assertEqual(raw, b"".join(staged.plaintext(BINDING)))
                if size > 256:
                    for path in staged.directory.iterdir():
                        self.assertNotIn(b"X" * 256, path.read_bytes())

    def test_truncated_extended_or_changed_stream_has_no_valid_checkpoint(self):
        for number, (raw, size, digest) in enumerate(((b"short", 9, sha256(b"short")),
                                                    (b"too long", 3, sha256(b"too")),
                                                    (b"wrong", 5, sha256(b"right")))):
            target = self.root / str(number)
            stream = io.BytesIO(raw)
            with self.subTest(number=number), self.assertRaises(BlobError):
                StagedBlob.prepare_stream(lambda: stream, target, BINDING, size=size, digest=digest)
            self.assertTrue(stream.closed)
            self.assertFalse((target / STATE_FILE).exists())

    def test_cancel_during_staging_never_publishes_a_partial_checkpoint(self):
        stream = RepeatingStream(2 * CHUNK_BYTES)
        target = self.root / "cancelled"
        with self.assertRaises(BlobError) as caught:
            StagedBlob.prepare_stream(lambda: stream, target, BINDING,
                size=2 * CHUNK_BYTES, digest=sha256(b"X" * (2 * CHUNK_BYTES)),
                cancel=lambda: stream.position >= CHUNK_BYTES)
        self.assertEqual("transfer_cancelled", caught.exception.code)
        self.assertTrue(stream.closed)
        self.assertFalse((target / STATE_FILE).exists())

    def test_existing_directory_never_reuses_an_old_nonce_or_key(self):
        raw = b"old"
        staged = StagedBlob.prepare_stream(lambda: io.BytesIO(raw), self.root / "same", BINDING,
                                           size=len(raw), digest=sha256(raw))
        previous = (staged.directory / STATE_FILE).read_bytes()
        with self.assertRaises(FileExistsError):
            StagedBlob.prepare_stream(lambda: io.BytesIO(b"new"), staged.directory, BINDING,
                                     size=3, digest=sha256(b"new"))
        self.assertEqual(previous, (staged.directory / STATE_FILE).read_bytes())
