from __future__ import annotations

import base64
import json
import unittest

import mqtt_wire_chunking


def _wire_payload(size: int = 180_000) -> str:
    return json.dumps(
        {
            "scheme": "signal",
            "from": "signalasi:phone",
            "to": "desktop_test",
            "body": "x" * size,
        },
        separators=(",", ":"),
    )


class MqttWireChunkingTests(unittest.TestCase):
    def test_small_encrypted_wire_payload_remains_direct(self) -> None:
        wire = _wire_payload(100)
        self.assertEqual([wire], mqtt_wire_chunking.encode_wire_payload(wire))

    def test_large_payload_round_trips_out_of_order_with_duplicates(self) -> None:
        wire = _wire_payload()
        packets = mqtt_wire_chunking.encode_wire_payload(wire)
        self.assertGreater(len(packets), 2)
        self.assertTrue(all(len(packet.encode("utf-8")) <= mqtt_wire_chunking.MAX_PACKET_BYTES for packet in packets))

        assembler = mqtt_wire_chunking.MqttWireChunkAssembler()
        decoded = [json.loads(packet) for packet in packets]
        self.assertIsNone(assembler.accept("route", decoded[-1]))
        self.assertIsNone(assembler.accept("route", decoded[-1]))
        result = None
        for packet in decoded[:-1]:
            result = assembler.accept("route", packet)
        self.assertEqual(wire, result)

    def test_modified_chunk_is_rejected_before_reassembly(self) -> None:
        packet = json.loads(mqtt_wire_chunking.encode_wire_payload(_wire_payload())[0])
        chunk = bytearray(base64.b64decode(packet["data"]))
        chunk[0] ^= 0x01
        packet["data"] = base64.b64encode(chunk).decode("ascii")
        with self.assertRaisesRegex(ValueError, "chunk integrity"):
            mqtt_wire_chunking.MqttWireChunkAssembler().accept("route", packet)

    def test_modified_transfer_is_rejected_by_whole_payload_hash(self) -> None:
        packets = [
            json.loads(packet)
            for packet in mqtt_wire_chunking.encode_wire_payload(_wire_payload())
        ]
        last = packets[-1]
        chunk = bytearray(base64.b64decode(last["data"]))
        chunk[-1] ^= 0x01
        last["data"] = base64.b64encode(chunk).decode("ascii")
        last["chunk_sha256"] = mqtt_wire_chunking._sha256(bytes(chunk))

        assembler = mqtt_wire_chunking.MqttWireChunkAssembler()
        with self.assertRaisesRegex(ValueError, "transfer integrity"):
            for packet in packets:
                assembler.accept("route", packet)

    def test_conflicting_duplicate_is_rejected(self) -> None:
        packet = json.loads(mqtt_wire_chunking.encode_wire_payload(_wire_payload())[0])
        assembler = mqtt_wire_chunking.MqttWireChunkAssembler()
        self.assertIsNone(assembler.accept("route", packet))
        duplicate = dict(packet)
        changed = base64.b64decode(duplicate["data"])[:-1] + b"y"
        duplicate["data"] = base64.b64encode(changed).decode("ascii")
        duplicate["chunk_sha256"] = mqtt_wire_chunking._sha256(changed)
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            assembler.accept("route", duplicate)


if __name__ == "__main__":
    unittest.main()
