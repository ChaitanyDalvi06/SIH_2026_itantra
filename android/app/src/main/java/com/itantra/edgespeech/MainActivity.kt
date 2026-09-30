package com.itantra.edgespeech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.itantra.edgespeech.audio.AudioCaptureService
import com.itantra.edgespeech.audio.AudioPlaybackEngine
import com.itantra.edgespeech.communication.*
import com.itantra.edgespeech.persistence.AppDatabase
import com.itantra.edgespeech.persistence.MessageEntity
import com.itantra.edgespeech.persistence.OfflineQueueManager
import com.itantra.edgespeech.persistence.QueueStatus
import com.itantra.edgespeech.protocol.FrameSerializer
import com.itantra.edgespeech.protocol.Language
import com.itantra.edgespeech.protocol.PacketType
import com.itantra.edgespeech.speech.*
import com.itantra.edgespeech.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var playbackEngine: AudioPlaybackEngine
    private lateinit var sttEngine: STTEngine
    private lateinit var ttsEngine: TTSEngine
    private lateinit var modelManager: ModelManager
    private lateinit var languageManager: LanguageManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var sessionController: SessionController
    private lateinit var offlineQueueManager: OfflineQueueManager
    private lateinit var udpDiscovery: UdpBroadcastDiscovery
    private var audioCaptureService: AudioCaptureService? = null

    private val audioServiceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as? AudioCaptureService.LocalBinder
            audioCaptureService = binder?.getService()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            audioCaptureService = null
        }
    }

    private val sharedSecret = "iTantra_Secured_Key_2026_Prod01!".toByteArray(Charsets.UTF_8)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            startAudioService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize core engines
        playbackEngine = AudioPlaybackEngine(this)
        sttEngine = STTEngine(this)
        ttsEngine = TTSEngine(this, playbackEngine)
        modelManager = ModelManager(this)
        languageManager = LanguageManager(sttEngine, ttsEngine, modelManager, this)

        // Ensure microphone exclusive access for SpeechRecognizer
        sttEngine.onMicrophoneStateChange = { acquiringMic ->
            if (acquiringMic) {
                audioCaptureService?.pauseCapture()
            } else {
                audioCaptureService?.resumeCapture()
            }
        }

        val wifiDirect = WifiDirectTransport(this)
        val lanNsd = LanNsdTransport(this)
        val bluetooth = BluetoothTransport()
        connectionManager = ConnectionManager(this, wifiDirect, lanNsd, bluetooth)

        val localNodeId = "Node_" + (System.currentTimeMillis() % 10000)
        sessionController = SessionController(localNodeId, connectionManager)

        val db = AppDatabase.getDatabase(this)
        offlineQueueManager = OfflineQueueManager(db.messageDao(), connectionManager)

        // Remote peer typing state - StateFlow guarantees immediate Compose recomposition across threads
        val remotePeerTypingTextFlow = MutableStateFlow("")
        val remotePeerLanguageFlow = MutableStateFlow(Language.ENGLISH)

        // Observe connection state safely via StateFlow
        lifecycleScope.launch {
            connectionManager.connectionState.collect { state ->
                Log.i("MainActivity", "Observed connection state: $state")
                if (state == ConnectionState.CONNECTED) {
                    offlineQueueManager.flushNow()
                }
            }
        }

        // Start UDP Auto-Discovery Beacon with Gateway auto-detect
        udpDiscovery = UdpBroadcastDiscovery(this, localNodeId).apply {
            onPeerDiscovered = { peerId, peerIp, peerPort ->
                runOnUiThread {
                    if (connectionManager.connectionState.value == ConnectionState.DISCONNECTED) {
                        val isClient = peerId == "Gateway_Host" || peerId == "Hotspot_Host"
                        val isDesignatedConnector = isClient || (localNodeId > peerId)

                        if (isDesignatedConnector) {
                            Log.i("MainActivity", "Designated connector initiating to $peerIp:$peerPort (peerId: $peerId)")
                            connectionManager.connectToPeer(peerIp, peerPort)
                            sessionController.startSession(peerId, 0x1122334455667788L)
                        } else {
                            Log.i("MainActivity", "Peer discovered ($peerId), waiting for incoming connection on ServerSocket")
                        }
                    }
                }
            }
            startDiscovery()
        }

        // Inbound Frame Dispatcher (Zero delay, direct audio synthesis)
        connectionManager.onFrameReceived = { frameBytes ->
            try {
                val frame = FrameSerializer.deserialize(frameBytes, sharedSecret)
                Log.i("MainActivity", "Received frame type: ${frame.packetType}, text: ${frame.text}")

                when (frame.packetType) {
                    PacketType.TEXT_PARTIAL -> {
                        remotePeerTypingTextFlow.value = frame.text
                        remotePeerLanguageFlow.value = frame.language
                    }

                    PacketType.TEXT_FINAL, PacketType.ALERT -> {
                        val contextResult = ContextEngine.analyze(frame.text)

                        // Synthesize speech immediately through loudspeaker
                        ttsEngine.synthesizeAndPlay(
                            text = frame.text,
                            language = frame.language,
                            isAlert = frame.isAlert,
                            urgency = contextResult.urgency
                        )

                        lifecycleScope.launch {
                            db.messageDao().insertMessage(
                                MessageEntity(
                                    messageId = frame.messageId,
                                    sessionId = 0L, // 0L denotes inbound from peer
                                    sequence = frame.sequence,
                                    timestamp = frame.timestamp,
                                    languageCode = frame.language.code,
                                    isAlert = frame.isAlert,
                                    text = frame.text,
                                    rawBytes = frameBytes,
                                    status = QueueStatus.ACKED
                                )
                            )
                            // Keep live transcript visible until Room DB emits new message
                            delay(150L)
                            remotePeerTypingTextFlow.value = ""
                        }
                    }

                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error decoding frame: ${e.message}")
            }
        }

        checkAndRequestPermissions()

        setContent {
            ITantraTheme {
                var showDiagnostics by remember { mutableStateOf(false) }
                var showPairing by remember { mutableStateOf(false) }
                var isContinuousMode by remember { mutableStateOf(false) }
                var currentPartialText by remember { mutableStateOf("") }

                val connectionState by connectionManager.connectionState.collectAsState()
                val activeTier by connectionManager.activeTier.collectAsState()
                val selectedLanguage by languageManager.activeLanguage.collectAsState()
                val recentMessages by db.messageDao().observeRecentMessages().collectAsState(initial = emptyList())

                val audioLevel by sttEngine.audioLevelFlow.collectAsState()
                val endpointState by sttEngine.endpointStateFlow.collectAsState()
                val pauseProgress by sttEngine.pauseProgressFlow.collectAsState()
                val isTtsSpeaking by ttsEngine.isSpeakingFlow.collectAsState()
                val currentlySpeakingText by ttsEngine.currentlySpeakingTextFlow.collectAsState()
                val remotePeerTypingText by remotePeerTypingTextFlow.collectAsState()
                val remotePeerLanguage by remotePeerLanguageFlow.collectAsState()

                val coroutineScope = rememberCoroutineScope()

                // Function to send a finalized text message
                val sendFinalMessage: (String, Boolean) -> Unit = { textToSend, isAlert ->
                    if (textToSend.isNotBlank()) {
                        coroutineScope.launch {
                            val frame = sessionController.buildTextFrame(textToSend, selectedLanguage, isAlert = isAlert)
                            val serialized = FrameSerializer.serialize(frame, sharedSecret)
                            val sent = connectionManager.sendFrame(serialized)
                            Log.i("MainActivity", "Sent frame: '$textToSend', result: $sent")

                            db.messageDao().insertMessage(
                                MessageEntity(
                                    messageId = frame.messageId,
                                    sessionId = 1L, // 1L denotes outbound from local user
                                    sequence = frame.sequence,
                                    timestamp = frame.timestamp,
                                    languageCode = frame.language.code,
                                    isAlert = isAlert,
                                    text = textToSend,
                                    rawBytes = serialized,
                                    status = if (sent) QueueStatus.ACKED else QueueStatus.QUEUED
                                )
                            )

                            if (!sent) {
                                offlineQueueManager.enqueueOutboundMessage(
                                    messageId = frame.messageId,
                                    sessionId = frame.sessionId,
                                    sequence = frame.sequence,
                                    languageCode = frame.language.code,
                                    text = textToSend,
                                    rawBytes = serialized,
                                    isAlert = isAlert
                                )
                            }
                        }
                    }
                }

                LaunchedEffect(selectedLanguage) {
                    sttEngine.loadLanguageModel(selectedLanguage)
                }

                // Listen to STT live streaming flow
                LaunchedEffect(Unit) {
                    sttEngine.transcriptionFlow.collect { result ->
                        currentPartialText = result.text

                        if (!result.isFinal) {
                            // Stream live partial text to remote peer
                            val partialFrame = sessionController.buildPartialTextFrame(
                                text = result.text,
                                language = result.language,
                                messageId = result.messageId
                            )
                            try {
                                val bytes = FrameSerializer.serialize(partialFrame, sharedSecret)
                                connectionManager.sendFrame(bytes)
                            } catch (_: Exception) {}
                        } else {
                            // Utterance finalized!
                            sendFinalMessage(result.text, false)
                            currentPartialText = ""
                        }
                    }
                }

                if (showDiagnostics) {
                    DiagnosticsScreen(
                        activeTier = activeTier,
                        rttMs = connectionManager.getActiveRtt(),
                        packetLossPct = 0.1f,
                        onBack = { showDiagnostics = false }
                    )
                } else {
                    MainTransceiverScreen(
                        connectionState = connectionState,
                        activeTier = activeTier,
                        selectedLanguage = selectedLanguage,
                        onLanguageSelected = { lang -> languageManager.switchLanguage(lang) },
                        isContinuousMode = isContinuousMode,
                        onToggleContinuousMode = { enabled ->
                            isContinuousMode = enabled
                            sttEngine.isContinuousMode = enabled
                            if (enabled) {
                                currentPartialText = "🎙 [Hands-Free Call Active - Speak freely]"
                                sttEngine.startListening()
                            } else {
                                sttEngine.stopListening()
                                currentPartialText = ""
                            }
                        },
                        audioLevel = audioLevel,
                        endpointState = endpointState,
                        pauseProgress = pauseProgress,
                        isTtsSpeaking = isTtsSpeaking,
                        currentlySpeakingText = currentlySpeakingText,
                        remotePeerTypingText = remotePeerTypingText,
                        remotePeerLanguage = remotePeerLanguage,
                        onPttPress = {
                            if (!isContinuousMode) {
                                currentPartialText = "🎙 [Listening... Speak now]"
                                sttEngine.startListening()
                            }
                        },
                        onPttRelease = {
                            if (!isContinuousMode) {
                                currentPartialText = "⏳ [Transmitting...]"
                                sttEngine.stopListening()
                            }
                        },
                        onBroadcastAlert = { alertMsg ->
                            sendFinalMessage(alertMsg, true)
                            ttsEngine.synthesizeAndPlay(alertMsg, selectedLanguage, isAlert = true)
                        },
                        onQuickReplySelected = { replyText ->
                            sendFinalMessage(replyText, false)
                        },
                        onOpenDiagnostics = { showDiagnostics = true },
                        onOpenPairing = { showPairing = true },
                        onReplayMessage = { text, lang ->
                            ttsEngine.synthesizeAndPlay(text, lang)
                        },
                        recentMessages = recentMessages,
                        currentPartialText = currentPartialText
                    )
                }

                if (showPairing) {
                    PairingDialog(
                        localNodeId = "${sessionController.nodeId} (${udpDiscovery.getLocalIpAddress()})",
                        pairingCode = udpDiscovery.getLocalIpAddress(),
                        onDismiss = { showPairing = false },
                        onConfirmPairing = { targetIpOrCode ->
                            val ip = if (targetIpOrCode.count { it == '.' } == 3) targetIpOrCode.trim() else "10.189.20.233"
                            connectionManager.connectToPeer(ip, 8988)
                            showPairing = false
                            Toast.makeText(this, "Connecting to $ip...", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            startAudioService()
        }
    }

    private fun startAudioService() {
        val intent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, audioServiceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        udpDiscovery.stopDiscovery()
        connectionManager.wifiDirect.close()
        connectionManager.lanNsd.close()
        sttEngine.destroy()
        ttsEngine.shutdown()
        playbackEngine.release()
        try {
            unbindService(audioServiceConnection)
        } catch (_: Exception) {}
    }
}
