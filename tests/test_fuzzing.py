"""
Automated Fuzzing Test Suite
Verifies that 1,000+ adversarially mutated frames cause ZERO unhandled exceptions or crashes.
"""

import unittest
from core_engine.fuzzer import ProtocolFuzzer


class TestFuzzer(unittest.TestCase):
    def test_adversarial_campaign_zero_crashes(self):
        fuzzer = ProtocolFuzzer()
        results = fuzzer.run_fuzz_campaign(iterations=1000)

        self.assertEqual(results["status"], "PASS")
        self.assertEqual(results["crashes"], 0)
        self.assertGreater(results["rejected_count"], 0)
        self.assertGreater(results["throughput_fps"], 100)


if __name__ == "__main__":
    unittest.main()
