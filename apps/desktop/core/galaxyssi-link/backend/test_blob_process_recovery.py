"""Abrupt sender death after relay commit, using a real child process and HTTP."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from blob_crypto import StagedBlob
from blob_protocol import CHUNK_BYTES
from test_blob_crypto import BINDING
from test_blob_http import BlobHttpFixture


class BlobProcessRecoveryTest(BlobHttpFixture):
    def test_sender_death_after_accepted_chunks_recovers_without_reupload(self):
        self.source.write_bytes(b"x" * CHUNK_BYTES + b"y" * CHUNK_BYTES + b"z" * 23)
        script = """
import json, os, sys
from pathlib import Path
from blob_crypto import StagedBlob
from blob_client import BlobClient
from blob_protocol import CHUNK_BYTES
source, directory, origin, token, binding = sys.argv[1:]
staged = StagedBlob.prepare(Path(source), Path(directory), json.loads(binding))
def progress(done, total):
    if done >= 2 * CHUNK_BYTES:
        os._exit(73)
with BlobClient(origin, provisioning_token=token, allow_loopback_http=True, trust_env=False) as client:
    client.upload(staged, progress=progress)
os._exit(74)
"""
        staged_path = self.root / "sender-child"
        result = subprocess.run([sys.executable, "-c", script, str(self.source), str(staged_path),
                                 self.relay.origin, self.token, json.dumps(BINDING)],
                                cwd=Path(__file__).resolve().parent,
                                capture_output=True, timeout=45)
        self.assertEqual(73, result.returncode, result.stderr.decode(errors="replace"))
        staged = StagedBlob.open(staged_path, BINDING)
        self.assertEqual("04", self.store.status(staged.private["blob_id"], staged.remote["write_token"])["missing_bitmap"])
        before = {index: hashlib.sha256(staged.read_chunk(index)).hexdigest() for index in (0, 1)}
        progress = []
        offer = self.client.upload(staged, progress=lambda done, total: progress.append(done))
        self.assertEqual(2 * CHUNK_BYTES, progress[0])
        self.assertEqual([2 * CHUNK_BYTES, 2 * CHUNK_BYTES + 23], progress)
        received = self.client.download(offer, self.root / "receiver-child", BINDING)
        for index in (0, 1):
            self.assertEqual(before[index], hashlib.sha256(received.read_chunk(index)).hexdigest())
        self.assertEqual(self.source.read_bytes(), b"".join(received.plaintext(BINDING)))
