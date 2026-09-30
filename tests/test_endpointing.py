"""
Unit tests for Adaptive Endpoint Detection
"""

import unittest
import time
from core_engine.adaptive_endpoint import AdaptiveEndpointDetector, EndpointState


class TestAdaptiveEndpoint(unittest.TestCase):
    def setUp(self):
        self.detector = AdaptiveEndpointDetector(
            min_utterance_ms=200,
            short_silence_threshold_ms=100,
            stable_silence_threshold_ms=200,
            long_silence_threshold_ms=400,
        )

    def test_silence_to_speech_transition(self):
        state, finalize = self.detector.on_frame(is_speech=False)
        self.assertEqual(state, EndpointState.IDLE)
        self.assertFalse(finalize)

        state, finalize = self.detector.on_frame(is_speech=True, current_partial_text="नमस्ते")
        self.assertEqual(state, EndpointState.SPEECH)
        self.assertFalse(finalize)

    def test_short_pause_does_not_finalize(self):
        self.detector.on_frame(is_speech=True, current_partial_text="मुझे")
        time.sleep(0.05)
        # 50ms silence should enter POSSIBLE_END but NOT finalize
        state, finalize = self.detector.on_frame(is_speech=False, current_partial_text="मुझे")
        self.assertEqual(state, EndpointState.POSSIBLE_END)
        self.assertFalse(finalize)

        # Resumed speech returns to SPEECH
        state, finalize = self.detector.on_frame(is_speech=True, current_partial_text="मुझे मदद चाहिए")
        self.assertEqual(state, EndpointState.SPEECH)
        self.assertFalse(finalize)

    def test_punctuation_terminal_cue_finalizes(self):
        self.detector.on_frame(is_speech=True, current_partial_text="स्टेशन कहाँ है?")
        time.sleep(0.25)
        # Sentence ends with '?' and silence >= 100ms
        self.detector.on_frame(is_speech=False, current_partial_text="स्टेशन कहाँ है?")
        time.sleep(0.12)
        state, finalize = self.detector.on_frame(is_speech=False, current_partial_text="स्टेशन कहाँ है?")
        self.assertTrue(finalize)


if __name__ == "__main__":
    unittest.main()
