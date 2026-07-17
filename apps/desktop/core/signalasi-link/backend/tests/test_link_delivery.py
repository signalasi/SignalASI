from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import link_delivery


class LinkDeliveryTest(unittest.TestCase):
    def test_broker_accepted_messages_are_not_replayed_on_restart(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                link_delivery.queue_outbound("client", "message", "topic", "wire")
                self.assertEqual(1, len(link_delivery.pending_outbound()))

                link_delivery.mark_outbound_published("client", "message")

                self.assertEqual([], link_delivery.pending_outbound())
                self.assertEqual("published", link_delivery.outbound_status("client", "message"))
                link_delivery.acknowledge_outbound("client", "message")
                self.assertIsNone(link_delivery.outbound_status("client", "message"))

    def test_transport_epoch_clears_obsolete_outbox_only_once(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            database = Path(temporary) / "delivery.db"
            with patch.object(link_delivery, "DB_PATH", database):
                link_delivery.queue_outbound("client", "old", "topic", "wire")
                self.assertTrue(link_delivery.ensure_transport_epoch("v2"))
                self.assertEqual([], link_delivery.pending_outbound())

                link_delivery.queue_outbound("client", "current", "topic", "wire")
                self.assertFalse(link_delivery.ensure_transport_epoch("v2"))
                self.assertEqual("current", link_delivery.pending_outbound()[0]["message_id"])


if __name__ == "__main__":
    unittest.main()
