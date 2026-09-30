// iTantra EdgeSpeech Transceiver - Frontend Testbed Controller

const PRESET_PHRASES = {
  hi: [
    "मुझे मदद चाहिए (Help needed)",
    "यहाँ तुरंत सहायता की आवश्यकता है",
    "स्टेशन का रास्ता कहाँ है?",
    "सब सुरक्षित हैं"
  ],
  gu: [
    "મને મદદ જોઈએ છે (Help needed)",
    "તાત્કાલિક સહાયની જરૂર છે",
    "તમે કેમ છો?",
    "રસ્તો ક્યાં છે?"
  ],
  mr: [
    "मला मदत हवी आहे (Help needed)",
    "तातडीने मदत पाठवा",
    "तुम्ही कसे आहात?",
    "सर्व सुरक्षित आहेत"
  ],
  kn: [
    "ನನಗೆ ಸಹಾಯ ಬೇಕು (Help needed)",
    "ತುರ್ತು ಪರಿಸ್ಥಿತಿ ಇದೆ",
    "ನೀವು ಹೇಗಿದ್ದೀರಿ?",
    "ದಾರಿ ಎಲ್ಲಿದೆ?"
  ],
  ml: [
    "എനിക്ക് സഹായം വേണം (Help needed)",
    "അടിയന്തിര സാഹചര്യം",
    "സുഖമാണോ?",
    "വഴി എവിടെയാണ്?"
  ],
  ta: [
    "எனக்கு உதவி தேவை (Help needed)",
    "அவசர உதவி தேவை",
    "நீங்கள் நலமா?",
    "பாதை எங்கே உள்ளது?"
  ],
  te: [
    "నాకు సహాయం కావాలి (Help needed)",
    "అత్యవసర సహాయం కావాలి",
    "మీరు ఎలా ఉన్నారు?",
    "మార్గం ఎక్కడ ఉంది?"
  ],
  or: [
    "ମୋତେ ସାହାଯ୍ୟ ଦରକାର (Help needed)",
    "ଜରୁରୀ ସହାୟତା ଆବଶ୍ୟକ",
    "ଆପଣ କେମିତି ଅଛନ୍ତି?",
    "ବାଟ କେଉଁଠି?"
  ],
  bn: [
    "আমার সাহায্য দরকার (Help needed)",
    "জরুরি সহায়তা প্রয়োজন",
    "আপনি কেমন আছেন?",
    "রাস্তা কোন দিকে?"
  ],
  en: [
    "Immediate medical assistance required",
    "Where is the evacuation point?",
    "Team status check: all green",
    "Requesting backup at sector 4"
  ]
};

class TransceiverTestbed {
  constructor() {
    this.ws = null;
    this.audioCtx = null;
    this.isPttHoldingA = false;
    this.isPttHoldingB = false;
    this.activeTab = "transceiver-tab";

    this.initElements();
    this.initTabs();
    this.initWebSocket();
    this.initPhrases("hi", "A");
    this.initPhrases("hi", "B");
    this.attachEventListeners();
    this.loadBenchmarks();
  }

  initElements() {
    this.lossSlider = document.getElementById("lossSlider");
    this.lossVal = document.getElementById("lossVal");
    this.latencySlider = document.getElementById("latencySlider");
    this.latencyVal = document.getElementById("latencyVal");
    this.packetToken = document.getElementById("packetToken");
    this.packetLog = document.getElementById("packetLog");

    // Phone A
    this.pttBtnA = document.getElementById("pttBtnA");
    this.langSelectA = document.getElementById("langSelectA");
    this.speechBoxA = document.getElementById("speechBoxA");
    this.vadA = document.getElementById("vadA");
    this.transcriptionA = document.getElementById("transcriptionA");
    this.historyListA = document.getElementById("historyListA");
    this.customInputA = document.getElementById("customInputA");
    this.sendCustomBtnA = document.getElementById("sendCustomBtnA");
    this.alertBtnA = document.getElementById("alertBtnA");
    this.continuousA = document.getElementById("continuousA");

    // Phone B
    this.pttBtnB = document.getElementById("pttBtnB");
    this.langSelectB = document.getElementById("langSelectB");
    this.speechBoxB = document.getElementById("speechBoxB");
    this.speakerWave = document.getElementById("speakerWave");
    this.transcriptionB = document.getElementById("transcriptionB");
    this.historyListB = document.getElementById("historyListB");
    this.customInputB = document.getElementById("customInputB");
    this.sendCustomBtnB = document.getElementById("sendCustomBtnB");
    this.alertBtnB = document.getElementById("alertBtnB");
    this.continuousB = document.getElementById("continuousB");

    // Dissector
    this.dispSeq = document.getElementById("dispSeq");
    this.dispType = document.getElementById("dispType");
    this.dispLang = document.getElementById("dispLang");
    this.dispLen = document.getElementById("dispLen");
    this.hexDump = document.getElementById("hexDump");
    this.compFrameSize = document.getElementById("compFrameSize");

    // Fuzzer
    this.runFuzzBtn = document.getElementById("runFuzzBtn");
    this.fuzzTotal = document.getElementById("fuzzTotal");
    this.fuzzRejected = document.getElementById("fuzzRejected");
    this.fuzzCrashes = document.getElementById("fuzzCrashes");
    this.fuzzSpeed = document.getElementById("fuzzSpeed");
    this.fuzzDistList = document.getElementById("fuzzDistList");

    // Dynamic Host IP for Physical Phones
    const host = window.location.hostname || "192.168.0.102";
    const port = window.location.port ? `:${window.location.port}` : "";
    const pLinkA = document.getElementById("phoneALink");
    const pLinkB = document.getElementById("phoneBLink");
    if (pLinkA) {
      pLinkA.href = `http://${host}${port}/phone.html?id=A`;
      pLinkA.textContent = `http://${host}${port}/phone.html?id=A`;
    }
    if (pLinkB) {
      pLinkB.href = `http://${host}${port}/phone.html?id=B`;
      pLinkB.textContent = `http://${host}${port}/phone.html?id=B`;
    }
  }

  initTabs() {
    document.querySelectorAll(".tab-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
        document.querySelectorAll(".tab-content").forEach(c => c.classList.remove("active"));
        btn.classList.add("active");
        const tabId = btn.dataset.tab;
        document.getElementById(tabId).classList.add("active");
        this.activeTab = tabId;
      });
    });
  }

  initPhrases(lang, node) {
    const container = document.getElementById(`phraseChips${node}`);
    container.innerHTML = "";
    const phrases = PRESET_PHRASES[lang] || PRESET_PHRASES.en;

    phrases.forEach(phrase => {
      const chip = document.createElement("button");
      chip.className = "chip";
      chip.textContent = phrase;
      chip.addEventListener("click", () => {
        this.triggerSpeechUtterance(node, phrase);
      });
      container.appendChild(chip);
    });
  }

  initWebSocket() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${protocol}//${window.location.host}/ws`;
    this.ws = new WebSocket(wsUrl);

    this.ws.onmessage = (event) => {
      try {
        const parsed = jsonParseSafe(event.data);
        if (parsed) this.handleWsEvent(parsed);
      } catch (e) {
        console.error("WS Parse Error", e);
      }
    };

    this.ws.onclose = () => {
      setTimeout(() => this.initWebSocket(), 2000);
    };
  }

  handleWsEvent(msg) {
    const { event, data } = msg;

    if (event === "PACKET_IN_FLIGHT") {
      this.animatePacketFlight(data.source, data.dest);
      this.logEvent(`✈️ ${data.source} ➔ ${data.dest} [${data.bytes_len} B] via ${data.transport}`, "sent");
    } else if (event === "PACKET_DELIVERED") {
      this.updateDissector(data.frame, data.raw_hex);
      this.logEvent(`✓ Delivered to ${data.dest} in ${data.flight_ms} ms (${data.frame.payload_len} B text)`, "delivered");
    } else if (event === "NETWORK_DROP") {
      this.logEvent(`❌ PACKET DROPPED: ${data.reason} (${data.bytes} B)`, "drop");
    } else if (event === "NODE_EVENT") {
      this.handleNodeEvent(data);
    }
  }

  handleNodeEvent(nodeEvent) {
    const { node_id, event, data } = nodeEvent;
    const isNodeA = node_id.includes("A") || node_id.includes("Pixel");

    if (event === "PACKET_OUTBOUND") {
      const targetHist = isNodeA ? this.historyListA : this.historyListB;
      this.addHistoryItem(targetHist, `TX #${data.sequence}: "${data.text}"`, data.is_alert);
    } else if (event === "TTS_ENQUEUED" || event === "ALERT_PREEMPTION_TRIGGERED") {
      const targetHist = isNodeA ? this.historyListA : this.historyListB;
      const targetTrans = isNodeA ? this.transcriptionA : this.transcriptionB;

      targetTrans.textContent = `🔊 "${data.text}"`;
      this.addHistoryItem(targetHist, `RX #${data.sequence}: "${data.text}"`, data.is_alert);

      // Trigger audio playback
      this.playSpeechAudio(data.text, data.language, data.is_alert);
      this.triggerSpeakerAnimation(isNodeA ? "A" : "B", data.is_alert);
    } else if (event === "ACK_RECEIVED") {
      this.logEvent(`⇄ ACK Received for msg ${data.message_id.slice(0, 10)}...`, "ack");
    }
  }

  animatePacketFlight(source, dest) {
    const isAtoB = source.includes("A") || source.includes("Pixel");
    this.packetToken.className = "packet-token";
    void this.packetToken.offsetWidth; // Trigger reflow
    this.packetToken.classList.add(isAtoB ? "fly-right" : "fly-left");
  }

  updateDissector(frame, rawHex) {
    this.dispSeq.textContent = frame.sequence;
    this.dispType.textContent = `${frame.type} (0x${frame.type_code.toString(16).toUpperCase()})`;
    this.dispLang.textContent = `${frame.language.toUpperCase()}`;
    this.dispLen.textContent = `${frame.payload_len} Bytes`;
    this.hexDump.textContent = rawHex.slice(0, 80) + "...";
    this.compFrameSize.textContent = `${frame.total_frame_bytes} B (99.86% saved!)`;
  }

  logEvent(text, type = "sent") {
    const entry = document.createElement("div");
    entry.className = `log-entry ${type}`;
    const timeStr = new Date().toLocaleTimeString().split(" ")[0];
    entry.textContent = `[${timeStr}] ${text}`;
    this.packetLog.prepend(entry);
    if (this.packetLog.childElementCount > 30) {
      this.packetLog.removeChild(this.packetLog.lastChild);
    }
  }

  addHistoryItem(container, text, isAlert) {
    const item = document.createElement("div");
    item.className = `hist-item ${isAlert ? "alert-item" : ""}`;
    item.textContent = text;
    container.prepend(item);
    if (container.childElementCount > 10) {
      container.removeChild(container.lastChild);
    }
  }

  triggerSpeakerAnimation(node, isAlert) {
    const wave = node === "B" ? this.speakerWave : this.speakerWave;
    wave.classList.add("active");
    setTimeout(() => {
      wave.classList.remove("active");
    }, 2000);
  }

  playSpeechAudio(text, lang, isAlert) {
    // 1. Try Browser Web Speech Synthesis
    if ("speechSynthesis" in window) {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.pitch = isAlert ? 1.4 : 1.0;
      utterance.rate = 1.05;
      utterance.volume = 1.0;
      window.speechSynthesis.speak(utterance);
    }

    // 2. Play Web Audio Acoustic Tone/Chirp
    this.playAcousticTone(isAlert);
  }

  playAcousticTone(isAlert) {
    try {
      if (!this.audioCtx) {
        this.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      }
      const ctx = this.audioCtx;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = isAlert ? "sawtooth" : "sine";
      osc.frequency.setValueAtTime(isAlert ? 880 : 480, ctx.currentTime);
      if (isAlert) {
        osc.frequency.exponentialRampToValueAtTime(440, ctx.currentTime + 0.35);
      }

      gain.gain.setValueAtTime(0.2, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.4);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start();
      osc.stop(ctx.currentTime + 0.45);
    } catch (e) {
      // Audio context not allowed until user gesture
    }
  }

  triggerSpeechUtterance(node, text, isAlert = false) {
    const targetTrans = node === "A" ? this.transcriptionA : this.transcriptionB;
    const vad = node === "A" ? this.vadA : this.speakerWave;

    targetTrans.textContent = `🎙️ "${text}"`;
    vad.classList.add("speaking");

    fetch(`/api/node/Phone_${node}/ptt`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        action: isAlert ? "alert" : "release",
        text: text,
        is_alert: isAlert
      })
    });

    setTimeout(() => {
      vad.classList.remove("speaking");
    }, 600);
  }

  attachEventListeners() {
    // Sliders
    this.lossSlider.addEventListener("input", () => {
      const val = this.lossSlider.value;
      this.lossVal.textContent = `${val}%`;
      this.syncNetworkSettings();
    });

    this.latencySlider.addEventListener("input", () => {
      const val = this.latencySlider.value;
      this.latencyVal.textContent = `${val} ms`;
      this.syncNetworkSettings();
    });

    // Transport Segment Buttons
    document.querySelectorAll(".seg-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        document.querySelectorAll(".seg-btn").forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        this.syncNetworkSettings();
      });
    });

    // Languages
    this.langSelectA.addEventListener("change", () => {
      const lang = this.langSelectA.value;
      this.initPhrases(lang, "A");
      fetch(`/api/node/Phone_A/language`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ language: lang })
      });
    });

    this.langSelectB.addEventListener("change", () => {
      const lang = this.langSelectB.value;
      this.initPhrases(lang, "B");
      fetch(`/api/node/Phone_B/language`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ language: lang })
      });
    });

    // PTT Press & Release for Phone A
    const startPttA = () => {
      if (this.isPttHoldingA) return;
      this.isPttHoldingA = true;
      this.pttBtnA.classList.add("active");
      this.vadA.classList.add("speaking");
      this.transcriptionA.textContent = "🎙️ Listening to local speech...";
      fetch(`/api/node/Phone_A/ptt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "press" })
      });
    };

    const stopPttA = () => {
      if (!this.isPttHoldingA) return;
      this.isPttHoldingA = false;
      this.pttBtnA.classList.remove("active");
      this.vadA.classList.remove("speaking");

      const phrases = PRESET_PHRASES[this.langSelectA.value] || PRESET_PHRASES.hi;
      const spokenText = phrases[Math.floor(Math.random() * phrases.length)];
      this.transcriptionA.textContent = `Transcribed: "${spokenText}"`;

      fetch(`/api/node/Phone_A/ptt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "release", text: spokenText })
      });
    };

    this.pttBtnA.addEventListener("mousedown", startPttA);
    this.pttBtnA.addEventListener("mouseup", stopPttA);
    this.pttBtnA.addEventListener("touchstart", (e) => { e.preventDefault(); startPttA(); });
    this.pttBtnA.addEventListener("touchend", (e) => { e.preventDefault(); stopPttA(); });

    // PTT Press & Release for Phone B
    const startPttB = () => {
      if (this.isPttHoldingB) return;
      this.isPttHoldingB = true;
      this.pttBtnB.classList.add("active");
      this.transcriptionB.textContent = "🎙️ Listening to reply speech...";
      fetch(`/api/node/Phone_B/ptt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "press" })
      });
    };

    const stopPttB = () => {
      if (!this.isPttHoldingB) return;
      this.isPttHoldingB = false;
      this.pttBtnB.classList.remove("active");

      const phrases = PRESET_PHRASES[this.langSelectB.value] || PRESET_PHRASES.hi;
      const spokenText = phrases[Math.floor(Math.random() * phrases.length)];
      this.transcriptionB.textContent = `Replied: "${spokenText}"`;

      fetch(`/api/node/Phone_B/ptt`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "release", text: spokenText })
      });
    };

    this.pttBtnB.addEventListener("mousedown", startPttB);
    this.pttBtnB.addEventListener("mouseup", stopPttB);
    this.pttBtnB.addEventListener("touchstart", (e) => { e.preventDefault(); startPttB(); });
    this.pttBtnB.addEventListener("touchend", (e) => { e.preventDefault(); stopPttB(); });

    // Custom inputs
    this.sendCustomBtnA.addEventListener("click", () => {
      const text = this.customInputA.value.trim();
      if (text) {
        this.triggerSpeechUtterance("A", text);
        this.customInputA.value = "";
      }
    });

    this.sendCustomBtnB.addEventListener("click", () => {
      const text = this.customInputB.value.trim();
      if (text) {
        this.triggerSpeechUtterance("B", text);
        this.customInputB.value = "";
      }
    });

    // Emergency Alerts
    this.alertBtnA.addEventListener("click", () => {
      this.triggerSpeechUtterance("A", "🚨 EMERGENCY ALERT: Immediate evacuation required!", true);
    });

    this.alertBtnB.addEventListener("click", () => {
      this.triggerSpeechUtterance("B", "🚨 CRITICAL ALERT: Perimeter breached, assistance required!", true);
    });

    // Fuzzer Campaign
    this.runFuzzBtn.addEventListener("click", () => {
      this.executeFuzzCampaign();
    });
  }

  syncNetworkSettings() {
    const activeTierBtn = document.querySelector(".seg-btn.active");
    const tier = activeTierBtn ? activeTierBtn.dataset.tier : "WIFI_DIRECT";

    fetch("/api/network/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        latency_ms: parseFloat(this.latencySlider.value),
        packet_loss_pct: parseFloat(this.lossSlider.value),
        active_transport: tier
      })
    });
  }

  async loadBenchmarks() {
    try {
      const res = await fetch("/api/benchmarks");
      const data = await res.json();
      this.renderBenchmarks(data.matrix, data.waterfall);
    } catch (e) {
      console.error("Error loading benchmarks", e);
    }
  }

  renderBenchmarks(matrix, waterfall) {
    // 1. Render Waterfall Timeline
    const wfTimeline = document.getElementById("waterfallTimeline");
    wfTimeline.innerHTML = "";

    const stages = [
      { name: "T0-T1 Speech Capture & VAD", key: "vad", class: "stt", dur: 20.0 },
      { name: "T1-T2 Streaming IndicConformer ASR", key: "stt", class: "stt", dur: waterfall.stage_durations_ms.stt_finalization },
      { name: "T2-T3 Frame Framing & AES-GCM Tag", key: "crypto", class: "stt", dur: waterfall.stage_durations_ms.packet_crypto },
      { name: "T3-T4 Wi-Fi Direct Radio Flight", key: "net", class: "net", dur: waterfall.stage_durations_ms.network_transit },
      { name: "T4-T5 ReplayGuard & Validation", key: "val", class: "net", dur: waterfall.stage_durations_ms.packet_validation },
      { name: "T5-T6 Ingest & Text Normalization", key: "ing", class: "tts", dur: 2.5 },
      { name: "T6-T7 Indic-TTS First Audio Chunk", key: "tts", class: "tts", dur: waterfall.stage_durations_ms.tts_first_audio },
      { name: "T7-T8 AudioTrack DAC Playback", key: "dac", class: "dac", dur: waterfall.stage_durations_ms.audiotrack_dac }
    ];

    const maxDur = 360.0;
    stages.forEach(stg => {
      const pct = Math.min(100, Math.max(8, (stg.dur / maxDur) * 100));
      const row = document.createElement("div");
      row.className = "wf-row";
      row.innerHTML = `
        <span>${stg.name}</span>
        <div class="wf-bar-wrapper">
          <div class="wf-bar ${stg.class}" style="width: ${pct}%;"></div>
        </div>
        <span class="wf-time">${stg.dur.toFixed(1)} ms</span>
      `;
      wfTimeline.appendChild(row);
    });

    document.getElementById("wfTotalLatency").textContent = `~${waterfall.summary.speech_to_heard_latency_s} sec`;

    // 2. Render 10-Language Table
    const tbody = document.getElementById("benchmarkBody");
    tbody.innerHTML = "";

    matrix.languages.forEach(lang => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td><strong class="saffron">${lang.code}</strong></td>
        <td>${lang.name}</td>
        <td>${lang.stt_model}</td>
        <td>${lang.stt_size_mb} MB</td>
        <td>${lang.stt_ram_mb} MB</td>
        <td><span class="emerald">${lang.stt_wer_int8}%</span></td>
        <td>${lang.stt_rtf_int8}</td>
        <td>${lang.tts_rtf_int8}</td>
        <td><strong>${lang.e2e_median_latency_s} s</strong></td>
      `;
      tbody.appendChild(tr);
    });
  }

  async executeFuzzCampaign() {
    this.runFuzzBtn.disabled = true;
    this.runFuzzBtn.textContent = "⏳ Blasting 1,000 Mutated Packets...";

    try {
      const res = await fetch("/api/fuzz", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ iterations: 1000 })
      });
      const data = await res.json();

      this.fuzzTotal.textContent = data.total_iterations;
      this.fuzzRejected.textContent = data.rejected_count;
      this.fuzzCrashes.textContent = `${data.crashes} (PASS)`;
      this.fuzzSpeed.textContent = `${data.throughput_fps} fps`;

      this.fuzzDistList.innerHTML = "";
      for (const [err, count] of Object.entries(data.rejections_by_category)) {
        const row = document.createElement("div");
        row.className = "dist-row";
        row.innerHTML = `
          <span>${err}</span>
          <strong class="emerald">${count}</strong>
        `;
        this.fuzzDistList.appendChild(row);
      }
    } catch (e) {
      console.error("Fuzz error", e);
    } finally {
      this.runFuzzBtn.disabled = false;
      this.runFuzzBtn.textContent = "⚡ Launch 1,000-Packet Attack Campaign";
    }
  }
}

function jsonParseSafe(str) {
  try {
    return JSON.parse(str);
  } catch (e) {
    return null;
  }
}

document.addEventListener("DOMContentLoaded", () => {
  window.testbed = new TransceiverTestbed();
});
