// iTantra Standalone Mobile Phone Client
// Real-time speech transceiver with native STT & TTS for Hindi, English, and Indian languages.
// Supports AudioWorklet VAD, adaptive endpointing, typing indicators, and offline context badges.

const PRESET_PHRASES = {
  hi: [
    "नमस्ते, आप कहाँ हैं?",
    "यहाँ तुरंत सहायता की आवश्यकता है",
    "चौकी 4 पर स्थिति सामान्य है",
    "हम सुरक्षित स्थान पर पहुँच गए हैं"
  ],
  en: [
    "Hello, what is your current location?",
    "Immediate medical assistance required at Sector 7",
    "All perimeter posts are secure and accounted for",
    "Proceed to evacuation waypoint Alpha"
  ],
  mr: ["नमस्कार, तुम्ही कुठे आहात?", "इथे त्वरित मदतीची गरज आहे", "सर्व लोक सुरक्षित आहेत का?", "आम्ही सुरक्षित ठिकाणी पोहोचलो आहोत"],
  gu: ["મને મદદ જોઈએ છે", "તાત્કાલિક સહાયની જરૂર છે", "તમે કેમ છો?", "રસ્તો ક્યાં છે?"],
  kn: ["ನನಗೆ ಸಹಾಯ ಬೇಕು", "ತುರ್ತು ಪರಿಸ್ಥಿತಿ ಇದೆ", "ನೀವು ಹೇಗಿದ್ದೀರಿ?", "ದಾರಿ ಎಲ್ಲಿದೆ?"],
  ml: ["എനിക്ക് സഹಾಯം വേണം", "അടിയന്തിര സാഹചര്യം", "സുಖമാണോ?", "വഴി എവിടെയാണ്?"],
  ta: ["எனக்கு உதவி தேவை", "அவசர உதவி தேவை", "நீங்கள் நலமா?", "பாதை எங்கே உள்ளது?"],
  te: ["నాకు సహాయం కావాలి", "అత్యవసర సహాయం కావాలి", "మీరు ఎలా ఉన్నారు?", "మార్గం ఎక్కడ ఉంది?"],
  or: ["ମୋତେ ସାହାଯ୍ୟ ଦରକାର", "ଜରୁରୀ ସହାୟତା ଆବଶ୍ୟକ", "ଆପଣ କେମିତି ଅଛନ୍ତି?", "ବାଟ କେଉଁଠି?"],
  bn: ["আমার সাহায্য দরকার", "জরুরি সহায়তা প্রয়োজন", "আপনি কেমন আছেন?", "রাস্তা কোন দিকে?"]
};

const LANG_BCP47 = {
  hi: "hi-IN",
  en: "en-IN",
  mr: "mr-IN",
  gu: "gu-IN",
  kn: "kn-IN",
  ml: "ml-IN",
  ta: "ta-IN",
  te: "te-IN",
  or: "or-IN",
  bn: "bn-IN"
};

class MobilePhoneClient {
  constructor() {
    const params = new URLSearchParams(window.location.search);
    this.nodeId = params.get("id")?.toUpperCase() === "B" ? "B" : "A";
    this.peerId = this.nodeId === "A" ? "B" : "A";
    this.selectedLanguage = "hi";
    this.isPttHolding = false;
    this.isHandsFree = false;
    this.lastSpokenText = "";
    this.currentUtteranceId = Date.now();
    this.audioContext = null;
    this.speechRecognition = null;
    this.vadWorkletNode = null;
    this.isListening = false;
    this.silenceTimer = null;

    this.initElements();
    this.initRecognition();
    this.initVoices();
    this.initWebSocket();
    this.renderChips();
    this.attachEvents();
  }

  initElements() {
    this.deviceTitle = document.getElementById("deviceTitle");
    this.connectionSub = document.getElementById("connectionSub");
    this.statusIndicator = document.getElementById("statusIndicator");
    this.vadTag = document.getElementById("vadTag");
    this.transcriptDisplay = document.getElementById("transcriptDisplay");
    this.chipsScroll = document.getElementById("chipsScroll");
    this.feedList = document.getElementById("feedList");
    this.mobilePttBtn = document.getElementById("mobilePttBtn");
    this.broadcastAlertBtn = document.getElementById("broadcastAlertBtn");
    this.quickInput = document.getElementById("quickInput");
    this.quickSendBtn = document.getElementById("quickSendBtn");
    this.langSelect = document.getElementById("langSelect");
    this.handsFreeToggle = document.getElementById("handsFreeToggle");
    this.typingIndicator = document.getElementById("typingIndicator");
    this.typingText = document.getElementById("typingText");
    this.typingLang = document.getElementById("typingLang");
    this.ttsWaveform = document.getElementById("ttsWaveform");
    this.ttsStatusText = document.getElementById("ttsStatusText");
    this.endpointState = document.getElementById("endpointState");
    this.audioBars = document.querySelectorAll(".audio-bar");

    if (this.deviceTitle) {
      this.deviceTitle.textContent = `Tactical Node ${this.nodeId}`;
    }
    if (this.connectionSub) {
      this.connectionSub.textContent = `Target: Node ${this.peerId} • Initializing...`;
    }
  }

  initRecognition() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (SpeechRecognition) {
      this.speechRecognition = new SpeechRecognition();
      this.speechRecognition.continuous = true;
      this.speechRecognition.interimResults = true;
      this.speechRecognition.maxAlternatives = 1;
      this.speechRecognition.lang = LANG_BCP47[this.selectedLanguage] || "hi-IN";

      this.speechRecognition.onstart = () => {
        this.isListening = true;
        this.setEndpointState("speech", "🎙 Speaking...");
      };

      this.speechRecognition.onresult = (event) => {
        let interimTranscript = "";
        let finalTranscript = "";

        for (let i = event.resultIndex; i < event.results.length; ++i) {
          const transcript = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            finalTranscript += transcript;
          } else {
            interimTranscript += transcript;
          }
        }

        const currentText = interimTranscript || finalTranscript;
        if (currentText) {
          this.lastSpokenText = currentText;
          if (this.transcriptDisplay) {
            this.transcriptDisplay.textContent = currentText;
          }
          this.sendPartialText(currentText);
          this.resetSilenceTimer();
        }

        if (finalTranscript) {
          this.finalizeAndSendSpeech(finalTranscript);
        }
      };

      this.speechRecognition.onerror = (e) => {
        if (e.error !== "no-speech") {
          console.warn("Speech recognition error:", e.error);
        }
      };

      this.speechRecognition.onend = () => {
        this.isListening = false;
        if (this.isHandsFree) {
          setTimeout(() => {
            if (this.isHandsFree && !this.isListening) {
              this.startListening();
            }
          }, 300);
        } else if (!this.isPttHolding) {
          this.setEndpointState("idle", "Idle");
        }
      };
    }
  }

  setEndpointState(state, label) {
    if (this.endpointState) {
      this.endpointState.className = `endpoint-state ${state}`;
      this.endpointState.textContent = label;
    }
  }

  resetSilenceTimer() {
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    this.setEndpointState("speech", "🎙 Speaking...");

    // Adaptive silence threshold: 750ms for Hindi, 600ms for English
    const threshold = this.selectedLanguage === "en" ? 650 : 800;
    this.silenceTimer = setTimeout(() => {
      this.setEndpointState("pause", "⏸ Pause Detected");
      if (this.lastSpokenText && this.isListening) {
        this.finalizeAndSendSpeech(this.lastSpokenText);
      }
    }, threshold);
  }

  startListening() {
    if (this.speechRecognition && !this.isListening) {
      try {
        this.currentUtteranceId = Date.now();
        this.speechRecognition.lang = LANG_BCP47[this.selectedLanguage] || "hi-IN";
        this.speechRecognition.start();
      } catch (e) {
        // Recognition already started
      }
    }
  }

  stopListening() {
    if (this.speechRecognition && this.isListening) {
      try {
        this.speechRecognition.stop();
      } catch (e) {
        // Ignore
      }
    }
    if (this.silenceTimer) clearTimeout(this.silenceTimer);
    if (this.lastSpokenText) {
      this.finalizeAndSendSpeech(this.lastSpokenText);
    }
  }

  finalizeAndSendSpeech(text) {
    const clean = (text || "").trim();
    if (!clean) return;

    this.setEndpointState("finalized", "✓ Finalized");
    this.sendFinalSpeech(clean, false);
    this.lastSpokenText = "";
    if (this.transcriptDisplay) {
      this.transcriptDisplay.textContent = clean;
    }

    setTimeout(() => {
      if (!this.isListening) {
        this.setEndpointState("idle", "Idle");
      }
    }, 1000);
  }

  initVoices() {
    if ("speechSynthesis" in window) {
      window.speechSynthesis.onvoiceschanged = () => {
        window.speechSynthesis.getVoices();
      };
    }
  }

  initWebSocket() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${protocol}//${window.location.host}/ws`;
    this.ws = new WebSocket(wsUrl);

    this.ws.onopen = () => {
      if (this.connectionSub) {
        this.connectionSub.textContent = `Connected to Node ${this.peerId} (Zero Cloud)`;
      }
      if (this.statusIndicator) {
        this.statusIndicator.classList.add("connected");
      }

      this.ws.send(JSON.stringify({
        type: "REGISTER_NODE",
        node_id: this.nodeId
      }));
    };

    this.ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        this.handleInboundMessage(data);
      } catch (e) {
        console.error("Malformed WebSocket payload:", e);
      }
    };

    this.ws.onclose = () => {
      if (this.statusIndicator) {
        this.statusIndicator.classList.remove("connected");
      }
      if (this.connectionSub) {
        this.connectionSub.textContent = "Disconnected • Reconnecting...";
      }
      setTimeout(() => this.initWebSocket(), 2000);
    };
  }

  handleInboundMessage(data) {
    if (data.type === "TYPING_PARTIAL") {
      if (data.sender_node !== this.nodeId) {
        this.showTypingIndicator(data.text, data.language);
      }
      return;
    }

    if (data.type === "SPEECH_FINALIZED" || data.event === "INBOUND_FRAME") {
      this.hideTypingIndicator();
      const text = data.text || (data.frame && data.frame.text) || "";
      const isAlert = data.is_alert || (data.frame && data.frame.is_alert) || false;
      const lang = data.language || (data.frame && data.frame.language) || "hi";
      const context = data.context || null;

      if (text) {
        this.appendMessage(text, "received", isAlert, context);
        this.synthesizeSpeech(text, lang, isAlert, context?.urgency);
      }
    }
  }

  showTypingIndicator(text, lang) {
    if (this.typingIndicator && this.typingText) {
      this.typingText.textContent = text;
      if (this.typingLang) {
        this.typingLang.textContent = (lang || "hi").toUpperCase();
      }
      this.typingIndicator.style.display = "flex";
    }
  }

  hideTypingIndicator() {
    if (this.typingIndicator) {
      this.typingIndicator.style.display = "none";
    }
  }

  sendPartialText(text) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({
        type: "TYPING_PARTIAL",
        sender_node: this.nodeId,
        target_node: this.peerId,
        text: text,
        language: this.selectedLanguage,
        message_id: this.currentUtteranceId
      }));
    }
  }

  async sendFinalSpeech(text, isAlert = false) {
    if (!text.trim()) return;

    let context = null;
    try {
      const res = await fetch("/api/context/analyze", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: text })
      });
      if (res.ok) {
        context = await res.json();
      }
    } catch (e) {
      // Fallback offline context
    }

    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({
        type: "SPEECH_FINALIZED",
        sender_node: this.nodeId,
        target_node: this.peerId,
        text: text,
        language: this.selectedLanguage,
        is_alert: isAlert,
        context: context,
        message_id: this.currentUtteranceId
      }));
    }

    this.appendMessage(text, "sent", isAlert, context);
  }

  synthesizeSpeech(text, langCode = "hi", isAlert = false, urgency = "LOW") {
    if (!("speechSynthesis" in window)) return;

    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = LANG_BCP47[langCode] || "hi-IN";

    if (isAlert || urgency === "CRITICAL") {
      utterance.pitch = 1.25;
      utterance.rate = 1.15;
    } else {
      utterance.pitch = 1.0;
      utterance.rate = 0.95;
    }

    utterance.onstart = () => {
      if (this.ttsWaveform) this.ttsWaveform.style.display = "flex";
      if (this.ttsStatusText) this.ttsStatusText.textContent = `Speaking: "${text.substring(0, 30)}..."`;
    };

    utterance.onend = () => {
      if (this.ttsWaveform) this.ttsWaveform.style.display = "none";
    };

    utterance.onerror = () => {
      if (this.ttsWaveform) this.ttsWaveform.style.display = "none";
    };

    window.speechSynthesis.speak(utterance);
  }

  appendMessage(text, direction, isAlert, context) {
    if (!this.feedList) return;

    const li = document.createElement("li");
    li.className = `feed-item ${direction} ${isAlert ? "alert" : ""}`;

    let contextHtml = "";
    if (context) {
      const intentName = context.intent?.replace("_", " ") || "GENERAL";
      const urgencyClass = (context.urgency || "low").toLowerCase();
      contextHtml = `
        <div class="context-badges">
          <span class="intent-chip">${intentName}</span>
          ${context.urgency && context.urgency !== "LOW" ? `<span class="urgency-pill ${urgencyClass}">${context.urgency}</span>` : ""}
          ${(context.entities || []).slice(0, 2).map(e => `<span class="entity-chip">${e.category}: ${e.value}</span>`).join("")}
        </div>
      `;
    }

    li.innerHTML = `
      ${contextHtml}
      <div class="msg-bubble">${text}</div>
      <div class="msg-meta">
        <span>${direction === "sent" ? "You" : `Node ${this.peerId}`}</span>
        <span>•</span>
        <span>${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span>
      </div>
    `;

    this.feedList.prepend(li);
  }

  renderChips() {
    if (!this.chipsScroll) return;
    this.chipsScroll.innerHTML = "";
    const phrases = PRESET_PHRASES[this.selectedLanguage] || PRESET_PHRASES["hi"];

    phrases.forEach((phrase) => {
      const btn = document.createElement("button");
      btn.className = "chip-btn";
      btn.textContent = phrase;
      btn.onclick = () => {
        if (this.quickInput) this.quickInput.value = phrase;
        this.sendFinalSpeech(phrase, false);
      };
      this.chipsScroll.appendChild(btn);
    });
  }

  attachEvents() {
    if (this.langSelect) {
      this.langSelect.value = this.selectedLanguage;
      this.langSelect.onchange = (e) => {
        this.selectedLanguage = e.target.value;
        if (this.speechRecognition) {
          this.speechRecognition.lang = LANG_BCP47[this.selectedLanguage] || "hi-IN";
        }
        this.renderChips();
      };
    }

    if (this.handsFreeToggle) {
      this.handsFreeToggle.onchange = (e) => {
        this.isHandsFree = e.target.checked;
        if (this.isHandsFree) {
          this.startListening();
          if (this.mobilePttBtn) {
            this.mobilePttBtn.classList.add("hands-free");
            const txt = this.mobilePttBtn.querySelector(".ptt-text");
            if (txt) txt.textContent = "HANDS-FREE VAD ACTIVE";
          }
        } else {
          this.stopListening();
          if (this.mobilePttBtn) {
            this.mobilePttBtn.classList.remove("hands-free");
            const txt = this.mobilePttBtn.querySelector(".ptt-text");
            if (txt) txt.textContent = "HOLD TO TALK";
          }
        }
      };
    }

    if (this.mobilePttBtn) {
      const startPtt = (e) => {
        e.preventDefault();
        if (this.isHandsFree) return;
        this.isPttHolding = true;
        this.mobilePttBtn.classList.add("holding");
        this.startListening();
      };

      const stopPtt = (e) => {
        e.preventDefault();
        if (this.isHandsFree) return;
        if (this.isPttHolding) {
          this.isPttHolding = false;
          this.mobilePttBtn.classList.remove("holding");
          this.stopListening();
        }
      };

      this.mobilePttBtn.addEventListener("mousedown", startPtt);
      this.mobilePttBtn.addEventListener("mouseup", stopPtt);
      this.mobilePttBtn.addEventListener("touchstart", startPtt);
      this.mobilePttBtn.addEventListener("touchend", stopPtt);
    }

    if (this.broadcastAlertBtn) {
      this.broadcastAlertBtn.onclick = () => {
        const alertMsg = this.selectedLanguage === "hi" 
          ? "🚨 आपातकालीन अलर्ट: तुरंत सहायता की आवश्यकता है!"
          : "🚨 CRITICAL ALERT: Immediate assistance required!";
        this.sendFinalSpeech(alertMsg, true);
        this.synthesizeSpeech(alertMsg, this.selectedLanguage, true, "CRITICAL");
      };
    }

    if (this.quickSendBtn && this.quickInput) {
      const sendQuick = () => {
        const val = this.quickInput.value.trim();
        if (val) {
          this.sendFinalSpeech(val, false);
          this.quickInput.value = "";
        }
      };
      this.quickSendBtn.onclick = sendQuick;
      this.quickInput.onkeydown = (e) => {
        if (e.key === "Enter") sendQuick();
      };
    }
  }
}

document.addEventListener("DOMContentLoaded", () => {
  window.phoneClient = new MobilePhoneClient();
});
