import copy
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path
import secrets
import sqlite3
import tempfile
import unittest

from blob_protocol import (
    BlobError, CHUNK_BYTES, MAX_CHUNKS, canonical, expand_bitmap, manifest,
    missing_bitmap, sha256,
)
from blob_store import BlobStore


class BlobProtocolTest(unittest.TestCase):
    def test_bitmap_crosses_byte_boundaries_and_rejects_padding(self):
        values = [0, 7, 8, 9, 1023]
        self.assertEqual(values, expand_bitmap(missing_bitmap(values, MAX_CHUNKS), MAX_CHUNKS))
        for bad in ("", "ff", "0000", "0x", "AA"):
            with self.subTest(bad=bad), self.assertRaises(BlobError):
                expand_bitmap(bad, 1)

    def test_public_manifest_rejects_metadata_and_invalid_layout(self):
        valid = {"version": 1, "chunks": [{"sha256": "a" * 64, "size": 16}]}
        self.assertEqual(valid, manifest(valid))
        invalid = [None, {}, {**valid, "filename": "private.txt"}, {**valid, "version": True},
                   {**valid, "chunks": []}, {**valid, "chunks": valid["chunks"] * (MAX_CHUNKS + 1)},
                   {**valid, "chunks": valid["chunks"] * 2}]
        for chunk in ({"sha256": "../x", "size": 16}, {"sha256": "a" * 64, "size": True},
                      {"sha256": "a" * 64, "size": 15},
                      {"sha256": "a" * 64, "size": CHUNK_BYTES + 17}):
            invalid.append({"version": 1, "chunks": [chunk]})
        for value in invalid:
            with self.subTest(value=str(value)[:100]), self.assertRaises(BlobError):
                manifest(value)


class BlobStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "relay.sqlite3"
        self.now = 1000.0
        self.store = BlobStore(self.path, clock=lambda: self.now)
        self.session, self.read, self.write = secrets.token_hex(16), secrets.token_hex(32), secrets.token_hex(32)
        self.data = secrets.token_bytes(117)
        self.public = {"version": 1, "chunks": [{"sha256": sha256(self.data), "size": len(self.data)}]}
        self.store.create(self.session, self.public, self.read, self.write)

    def test_idempotent_creation_does_not_reset_expiry_or_chunks(self):
        first = self.store.status(self.session, self.write)
        self.store.put(self.session, self.write, 0, self.data)
        self.now += 100
        again = self.store.create(self.session, self.public, self.read, self.write)
        self.assertEqual(first["expires"], again["expires"])
        self.assertTrue(self.store.status(self.session, self.write)["complete"])

    def test_capabilities_are_separate_and_hashed_at_rest(self):
        for call in (lambda: self.store.put(self.session, self.read, 0, self.data),
                     lambda: self.store.status(self.session, self.read),
                     lambda: self.store.get_manifest(self.session, self.write),
                     lambda: self.store.delete(self.session, self.read)):
            with self.assertRaises(BlobError) as error:
                call()
            self.assertEqual(404, error.exception.status)
        db_bytes = self.path.read_bytes()
        self.assertNotIn(self.read.encode(), db_bytes)
        self.assertNotIn(self.write.encode(), db_bytes)

    def test_creation_collision_and_equal_capabilities_rejected(self):
        changed = copy.deepcopy(self.public)
        changed["chunks"][0]["sha256"] = "b" * 64
        for public, read, write in ((changed, self.read, self.write),
                                    (self.public, secrets.token_hex(32), self.write)):
            with self.assertRaises(BlobError) as error:
                self.store.create(self.session, public, read, write)
            self.assertEqual(409, error.exception.status)
        with self.assertRaises(BlobError):
            self.store.create(secrets.token_hex(16), self.public, self.read, self.read)

    def test_binary_put_duplicate_restart_and_wrong_hash(self):
        self.assertEqual("01", self.store.status(self.session, self.write)["missing_bitmap"])
        self.assertTrue(self.store.put(self.session, self.write, 0, self.data))
        self.assertFalse(self.store.put(self.session, self.write, 0, self.data))
        reopened = BlobStore(self.path, clock=lambda: self.now)
        self.assertEqual(self.data, reopened.get(self.session, self.read, 0))
        self.assertEqual("00", reopened.status(self.session, self.write)["missing_bitmap"])
        with self.assertRaises(BlobError):
            reopened.put(self.session, self.write, 0, self.data[:-1] + bytes([self.data[-1] ^ 1]))
        self.assertEqual(self.data, reopened.get(self.session, self.read, 0))

    def test_concurrent_duplicate_writes_commit_once(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            values = list(pool.map(lambda _: self.store.put(self.session, self.write, 0, self.data), range(24)))
        self.assertEqual(1, sum(values))
        with closing(sqlite3.connect(self.path)) as db, db:
            self.assertEqual(len(self.data), db.execute("SELECT bytes FROM usage").fetchone()[0])

    def test_corruption_invalidates_and_sender_can_repair(self):
        self.store.put(self.session, self.write, 0, self.data)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("UPDATE chunks SET data=?", (b"broken",))
        with self.assertRaisesRegex(BlobError, "corrupt_chunk_requires_repair"):
            self.store.get(self.session, self.read, 0)
        self.assertEqual("01", self.store.status(self.session, self.write)["missing_bitmap"])
        self.store.put(self.session, self.write, 0, self.data)
        self.assertEqual(self.data, self.store.get(self.session, self.read, 0))

    def test_cas_references_keep_shared_ciphertext_until_last_delete(self):
        self.store.put(self.session, self.write, 0, self.data)
        other = secrets.token_hex(16)
        self.store.create(other, self.public, self.read, self.write)
        self.assertTrue(self.store.status(other, self.write)["complete"])
        self.store.delete(self.session, self.write)
        self.assertEqual(0, self.store.collect()["chunks"])
        self.assertEqual(self.data, self.store.get(other, self.read, 0))
        self.store.delete(other, self.write)
        self.assertEqual(len(self.data), self.store.collect()["reclaimed_bytes"])

    def test_quota_rejects_atomically_without_false_completion(self):
        self.store.quota_bytes = len(self.data) - 1
        with self.assertRaises(BlobError) as error:
            self.store.put(self.session, self.write, 0, self.data)
        self.assertEqual(507, error.exception.status)
        self.assertFalse(self.store.status(self.session, self.write)["complete"])
        self.store.max_sessions = 1
        with self.assertRaises(BlobError):
            self.store.create(secrets.token_hex(16), self.public, self.read, self.write)

    def test_expiry_and_bounded_collection(self):
        self.store.put(self.session, self.write, 0, self.data)
        self.now += 8 * 86400
        with self.assertRaises(BlobError) as error:
            self.store.get(self.session, self.read, 0)
        self.assertEqual(410, error.exception.status)
        self.assertEqual({"expired_sessions": 1, "chunks": 1, "reclaimed_bytes": len(self.data)},
                         self.store.collect(batch=1))

    def test_sparse_out_of_order_chunks_survive_reopen(self):
        chunks = [secrets.token_bytes(CHUNK_BYTES + 16), secrets.token_bytes(CHUNK_BYTES + 16), b"z" * 18]
        public = {"version": 1, "chunks": [{"sha256": sha256(data), "size": len(data)} for data in chunks]}
        session = secrets.token_hex(16)
        self.store.create(session, public, self.read, self.write)
        for index in (2, 0):
            self.store.put(session, self.write, index, chunks[index])
        reopened = BlobStore(self.path, clock=lambda: self.now)
        self.assertEqual("02", reopened.status(session, self.write)["missing_bitmap"])
        self.store.put(session, self.write, 1, chunks[1])
        self.assertTrue(reopened.status(session, self.write)["complete"])

    def test_control_queries_do_not_select_ciphertext(self):
        self.store.put(self.session, self.write, 0, self.data)
        result = self.store.status(self.session, self.write)
        self.assertEqual(sha256(canonical(self.public)), result["root"])
        self.assertLess(len(canonical(result)), 300)
        self.assertNotIn("data", result)
