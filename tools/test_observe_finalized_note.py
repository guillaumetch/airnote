import hashlib
import struct
import unittest

from tools import observe_finalized_note as obs


class ObserveFinalizedNoteTests(unittest.TestCase):
    @staticmethod
    def range_bytes(start: int, count: int) -> bytes:
        return bytes(((start + i) & 0xFF) for i in range(count))

    def fixture_note_data(self, status=obs.STATUS_FUNDED):
        owner = b"\x02" + self.range_bytes(0x80, 32)
        body = b"".join(
            [
                bytes(32),  # funder is not part of mint core
                b"\x01",
                self.range_bytes(0x00, 32),
                self.range_bytes(0x60, 32),
                struct.pack("<Q", 5_000_000),
                owner,
                struct.pack("<Q", 1_700_000_000),
                struct.pack("<Q", 1_700_003_600),
                self.range_bytes(0xA0, 32),
                b"\x01",
                bytes([status]),
                bytes(32),
                struct.pack("<Q", 0),
                b"\xfe",  # note bump: irrelevant to mint core
                b"\xfd",  # vault bump: irrelevant to mint core
            ]
        )
        return obs.NOTE_DISCRIMINATOR + body

    def test_note_layout_matches_gate01_commitment(self):
        data = self.fixture_note_data()
        self.assertEqual(obs.NOTE_ACCOUNT_LEN, len(data))
        note = obs.parse_note(data)
        core = obs.mint_core(
            note,
            self.range_bytes(0x20, 32),
            self.range_bytes(0x40, 32),
        )
        self.assertEqual(240, len(core))
        self.assertEqual(
            "d6dfd317a4c51dde7d2b824fc09dbb8b6f9721efb9d6c9044f6a527c854ff650",
            hashlib.sha256(core).hexdigest(),
        )

    def test_redeemed_note_is_not_certifiable(self):
        with self.assertRaises(SystemExit):
            obs.parse_note(self.fixture_note_data(status=1))

    def test_base58_round_trip_preserves_leading_zeroes(self):
        raw = b"\x00\x00" + self.range_bytes(1, 30)
        self.assertEqual(raw, obs.b58decode(obs.b58encode(raw)))

    def test_create_note_discriminator_is_anchor_canonical(self):
        self.assertEqual(
            hashlib.sha256(b"global:create_note").digest()[:8],
            obs.CREATE_NOTE_DISCRIMINATOR,
        )


if __name__ == "__main__":
    unittest.main()
