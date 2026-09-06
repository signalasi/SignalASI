import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from blob_crypto import STATE_FILE, StagedBlob, associated_data
from blob_protocol import BlobError, CHUNK_BYTES, canonical, sha256
from secure_state import SecureStateError

BINDING = {"client_route_id": "route-a", "conversation_id": "conversation-a",
           "task_id": "task-a", "turn_id": "turn-a", "attachment_id": "attachment-a"}


class BlobCryptoTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "private-source.txt"
        self.source.write_bytes(b"Sensitive example article content.\x00\xff" * 100)

    def prepare(self, name="sender"):
        return StagedBlob.prepare(self.source, self.root / name, BINDING)

    def test_shared_known_answer_vector(self):
        path = Path(__file__).resolve().parents[5] / "core/protocol/fixtures/blob-aead-v1.json"
        vector = json.loads(path.read_text())
        private = vector["private"]
        aad = associated_data(private, 0, private["size"])
        self.assertEqual(vector["aad_hex"], aad.hex())
        actual = AESGCM(bytes.fromhex(private["key"])).encrypt(
            bytes.fromhex(private["nonce_prefix"]) + b"\x00" * 4,
            bytes.fromhex(vector["plaintext_hex"]), aad)
        self.assertEqual(vector["ciphertext_hex"], actual.hex())
        staged = StagedBlob.receive(self.root / "vector", private, vector["manifest"], vector["binding"], {})
        staged.store_chunk(0, actual)
        self.assertEqual(bytes.fromhex(vector["plaintext_hex"]), b"".join(staged.plaintext(vector["binding"])))

    def test_plaintext_is_not_written_to_staging_or_manifest(self):
        staged = self.prepare()
        raw = self.source.read_bytes()
        for path in staged.directory.iterdir():
            if path.is_file():
                self.assertNotIn(b"Sensitive example article", path.read_bytes())
                self.assertNotIn(staged.private["key"].encode(), path.read_bytes())
        self.assertEqual(raw, b"".join(staged.plaintext(BINDING)))
        reopened = StagedBlob.open(staged.directory, BINDING)
        self.assertEqual(raw, b"".join(reopened.plaintext(BINDING)))

    def test_identical_plaintext_uses_distinct_keys_nonces_and_cas_hashes(self):
        left, right = self.prepare("left"), self.prepare("right")
        self.assertEqual(left.private["sha256"], right.private["sha256"])
        for field in ("blob_id", "key", "nonce_prefix"):
            self.assertNotEqual(left.private[field], right.private[field])
        self.assertNotEqual(left.public, right.public)

    def test_zero_byte_and_exact_chunk_boundary_roundtrip(self):
        for size in (0, 1, CHUNK_BYTES, CHUNK_BYTES + 1):
            with self.subTest(size=size):
                self.source.write_bytes(b"t" * size)
                staged = self.prepare(str(size))
                self.assertEqual(max(1, (size + CHUNK_BYTES - 1) // CHUNK_BYTES), len(staged.public["chunks"]))
                self.assertEqual(hashlib.sha256(b"t" * size).hexdigest(),
                                 hashlib.sha256(b"".join(staged.plaintext(BINDING))).hexdigest())

    def test_wrong_conversation_or_turn_cannot_open_or_decrypt(self):
        staged = self.prepare()
        for field in BINDING:
            binding = {**BINDING, field: "different"}
            with self.subTest(field=field), self.assertRaises(BlobError):
                StagedBlob.open(staged.directory, binding)
            with self.assertRaises(BlobError):
                list(staged.plaintext(binding))

    def test_wrong_key_and_aad_are_authenticated_not_guessed(self):
        staged = self.prepare()
        for field in ("key", "sha256", "nonce_prefix", "blob_id"):
            private = dict(staged.private)
            private[field] = "f" * len(private[field])
            changed = StagedBlob(staged.directory, private, staged.public)
            with self.subTest(field=field), self.assertRaises(InvalidTag):
                list(changed.plaintext(BINDING))

    def test_ciphertext_and_public_manifest_tampering_rejected(self):
        staged = self.prepare()
        path = staged.directory / "00000000.blob"
        path.write_bytes(b"wrong")
        self.assertFalse(staged.has_chunk(0))
        with self.assertRaises(BlobError):
            list(staged.plaintext(BINDING))
        public = copy.deepcopy(staged.public)
        public["chunks"][0]["sha256"] = "f" * 64
        with self.assertRaisesRegex(BlobError, "manifest_hash_mismatch"):
            StagedBlob(staged.directory, staged.private, public)

    def test_chunk_swap_and_reindexed_manifest_cannot_bypass_aad(self):
        self.source.write_bytes(b"a" * CHUNK_BYTES + b"b" * CHUNK_BYTES)
        staged = self.prepare()
        data = [staged.read_chunk(0), staged.read_chunk(1)]
        public = {"version": 1, "chunks": list(reversed(staged.public["chunks"]))}
        private = {**staged.private, "manifest_sha256": sha256(canonical(public))}
        changed = StagedBlob.receive(self.root / "swapped", private, public, BINDING, {})
        changed.store_chunk(0, data[1])
        changed.store_chunk(1, data[0])
        with self.assertRaises(InvalidTag):
            list(changed.plaintext(BINDING))

    def test_source_mutation_leaves_no_resumable_key_checkpoint(self):
        original = Path.open
        reads = 0
        def intercept(path, *args, **kwargs):
            nonlocal reads
            if path == self.source and args and args[0] == "rb":
                reads += 1
                if reads == 2:
                    self.source.write_bytes(b"changed" * 3)
            return original(path, *args, **kwargs)
        with patch.object(Path, "open", intercept), self.assertRaisesRegex(BlobError, "source_changed"):
            self.prepare()
        self.assertFalse((self.root / "sender" / STATE_FILE).exists())
        with self.assertRaises(FileExistsError):
            self.prepare()

    def test_corrupt_checkpoint_and_oversized_local_chunk_rejected(self):
        staged = self.prepare()
        (staged.directory / "00000000.blob").write_bytes(b"x" * (CHUNK_BYTES + 17))
        self.assertFalse(staged.has_chunk(0))
        (staged.directory / STATE_FILE).write_text('{"key":"plaintext"}')
        with self.assertRaises(SecureStateError):
            StagedBlob.open(staged.directory, BINDING)
