/**
 * iTantra EdgeSpeech — Real-Time Voice Activity Detection AudioWorklet
 *
 * Runs in a separate audio rendering thread for zero-latency speech detection.
 * Computes RMS energy + zero-crossing rate per frame, auto-calibrates to ambient
 * noise in the first ~500ms, and posts speech/silence decisions to the main thread.
 *
 * Usage:
 *   audioContext.audioWorklet.addModule('vad_worklet.js');
 *   const vadNode = new AudioWorkletNode(audioContext, 'vad-processor');
 *   vadNode.port.onmessage = (e) => { /* { isSpeech, energy, zcr, threshold } */ };
 */

class VADProcessor extends AudioWorkletProcessor {
  constructor() {
    super();

    // Adaptive noise floor calibration
    this._calibrationFrames = 0;
    this._calibrationEnergies = [];
    this._calibrationDurationFrames = Math.ceil((sampleRate / 128) * 0.5); // ~500ms worth of frames
    this._isCalibrated = false;

    // Adaptive threshold
    this._noiseFloor = 0.005;       // Initial conservative noise floor
    this._speechThreshold = 0.015;  // Will be recalculated after calibration
    this._zcrSpeechMin = 0.05;      // Minimum ZCR to consider as speech (filters DC hum)
    this._zcrSpeechMax = 0.65;      // Maximum ZCR (above = noise/hiss, not speech)

    // Smoothing for energy (exponential moving average)
    this._smoothedEnergy = 0;
    this._smoothingFactor = 0.3;    // Higher = more responsive, lower = smoother

    // Hangover counter: keep reporting speech for a few frames after energy drops
    // This prevents choppy speech/silence toggling during natural micro-pauses within words
    this._hangoverFrames = 0;
    this._hangoverMax = Math.ceil((sampleRate / 128) * 0.08); // ~80ms hangover

    // Frame counter for throttling messages to main thread
    this._frameCount = 0;
    this._reportInterval = Math.max(1, Math.ceil((sampleRate / 128) * 0.02)); // ~20ms
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (!input || input.length === 0 || !input[0] || input[0].length === 0) {
      return true;
    }

    const samples = input[0]; // Mono channel
    const numSamples = samples.length;

    // Compute RMS energy
    let sumSquares = 0;
    for (let i = 0; i < numSamples; i++) {
      sumSquares += samples[i] * samples[i];
    }
    const rmsEnergy = Math.sqrt(sumSquares / numSamples);

    // Compute zero-crossing rate
    let zeroCrossings = 0;
    for (let i = 1; i < numSamples; i++) {
      if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
        zeroCrossings++;
      }
    }
    const zcr = zeroCrossings / (numSamples - 1);

    // Calibration phase: collect ambient noise energy samples
    if (!this._isCalibrated) {
      this._calibrationEnergies.push(rmsEnergy);
      this._calibrationFrames++;

      if (this._calibrationFrames >= this._calibrationDurationFrames) {
        // Compute noise floor as the median of calibration energies
        this._calibrationEnergies.sort((a, b) => a - b);
        const medianIdx = Math.floor(this._calibrationEnergies.length / 2);
        this._noiseFloor = this._calibrationEnergies[medianIdx];

        // Speech threshold = noise floor * multiplier (with minimum floor)
        this._speechThreshold = Math.max(0.008, this._noiseFloor * 3.5);
        this._isCalibrated = true;

        this.port.postMessage({
          type: 'calibrated',
          noiseFloor: this._noiseFloor,
          speechThreshold: this._speechThreshold,
        });
      }
    } else {
      // Slowly adapt noise floor during silence
      if (rmsEnergy < this._speechThreshold * 0.5) {
        this._noiseFloor = 0.95 * this._noiseFloor + 0.05 * rmsEnergy;
        this._speechThreshold = Math.max(0.008, this._noiseFloor * 3.5);
      }
    }

    // Exponential moving average for smoothed energy
    this._smoothedEnergy = this._smoothingFactor * rmsEnergy + (1 - this._smoothingFactor) * this._smoothedEnergy;

    // Speech decision
    const energyAboveThreshold = this._smoothedEnergy > this._speechThreshold;
    const zcrInSpeechRange = zcr >= this._zcrSpeechMin && zcr <= this._zcrSpeechMax;
    const rawIsSpeech = energyAboveThreshold && zcrInSpeechRange;

    // Apply hangover
    let isSpeech;
    if (rawIsSpeech) {
      isSpeech = true;
      this._hangoverFrames = this._hangoverMax;
    } else if (this._hangoverFrames > 0) {
      isSpeech = true;
      this._hangoverFrames--;
    } else {
      isSpeech = false;
    }

    // Throttle messages to main thread
    this._frameCount++;
    if (this._frameCount >= this._reportInterval) {
      this._frameCount = 0;
      this.port.postMessage({
        type: 'vad',
        isSpeech: isSpeech,
        energy: rmsEnergy,
        smoothedEnergy: this._smoothedEnergy,
        zcr: zcr,
        threshold: this._speechThreshold,
        noiseFloor: this._noiseFloor,
        isCalibrated: this._isCalibrated,
      });
    }

    return true;
  }
}

registerProcessor('vad-processor', VADProcessor);
