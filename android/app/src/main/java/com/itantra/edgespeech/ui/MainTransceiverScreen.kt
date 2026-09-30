package com.itantra.edgespeech.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.edgespeech.communication.ConnectionState
import com.itantra.edgespeech.communication.TransportTier
import com.itantra.edgespeech.persistence.MessageEntity
import com.itantra.edgespeech.protocol.Language
import com.itantra.edgespeech.speech.EndpointState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTransceiverScreen(
    connectionState: ConnectionState,
    activeTier: TransportTier,
    selectedLanguage: Language,
    onLanguageSelected: (Language) -> Unit,
    isContinuousMode: Boolean,
    onToggleContinuousMode: (Boolean) -> Unit,
    audioLevel: Float,
    endpointState: EndpointState,
    pauseProgress: Float,
    isTtsSpeaking: Boolean,
    currentlySpeakingText: String,
    remotePeerTypingText: String,
    remotePeerLanguage: Language,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onBroadcastAlert: (String) -> Unit,
    onQuickReplySelected: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPairing: () -> Unit,
    onReplayMessage: (String, Language) -> Unit,
    recentMessages: List<MessageEntity>,
    currentPartialText: String
) {
    var isPttHolding by remember { mutableStateOf(false) }
    var showLangMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Smooth scroll to bottom on new messages or live transcripts
    val totalItemsCount = recentMessages.size + (if (remotePeerTypingText.isNotBlank()) 1 else 0) + (if (currentPartialText.isNotBlank() && !currentPartialText.startsWith("⏳")) 1 else 0)
    LaunchedEffect(totalItemsCount, remotePeerTypingText, currentPartialText) {
        if (totalItemsCount > 0) {
            listState.animateScrollToItem(totalItemsCount - 1)
        }
    }

    // PTT Pulse animation when actively held
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pttPulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pttScale"
    )

    Scaffold(
        containerColor = BackgroundLight,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceCard,
                    titleContentColor = TextPrimaryDark
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = null,
                            tint = TechBluePrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "EdgeSpeech",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.width(10.dp))

                        // Connection Status Pill
                        val isConnected = connectionState == ConnectionState.CONNECTED
                        val badgeBg = if (isConnected) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)
                        val badgeText = if (isConnected) Color(0xFF15803D) else Color(0xFFB45309)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = badgeBg,
                            modifier = Modifier.clickable { onOpenPairing() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(badgeText, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    if (isConnected) "Connected" else "Searching...",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = badgeText
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Language Selector Chip
                    Box {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = SurfaceCardAlt,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.clickable { showLangMenu = true }
                        ) {
                            Text(
                                "🌐 ${selectedLanguage.displayName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showLangMenu,
                            onDismissRequest = { showLangMenu = false }
                        ) {
                            Language.values().forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            lang.displayName,
                                            fontWeight = if (lang == selectedLanguage) FontWeight.Bold else FontWeight.Normal,
                                            color = if (lang == selectedLanguage) TechBluePrimary else TextPrimaryDark
                                        )
                                    },
                                    onClick = {
                                        onLanguageSelected(lang)
                                        showLangMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Diagnostics Info Button
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Diagnostics",
                            tint = TextSecondaryMuted
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Message List / Feed (anchored to bottom like modern chat apps)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                if (recentMessages.isEmpty() && remotePeerTypingText.isBlank() && currentPartialText.isBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Radio,
                            contentDescription = null,
                            tint = TechBluePrimary.copy(alpha = 0.4f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Ready to Transmit",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Hold the button to talk, or tap a quick reply below. Messages are spoken aloud instantly on the other phone.",
                            fontSize = 13.sp,
                            color = TextSecondaryMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(recentMessages) { msg ->
                            val isMe = msg.sessionId == 1L
                            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (msg.isAlert) Arrangement.Center else if (isMe) Arrangement.End else Arrangement.Start
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (msg.isAlert) Color(0xFFFEF2F2) else if (isMe) BubbleSent else BubbleReceived,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (msg.isAlert) Color(0xFFFCA5A5) else if (isMe) Color(0xFFBFDBFE) else BorderSubtle
                                    ),
                                    shadowElevation = 1.dp,
                                    modifier = Modifier
                                        .widthIn(max = 290.dp)
                                        .clickable {
                                            val lang = Language.fromCode(msg.languageCode)
                                            onReplayMessage(msg.text, lang)
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (msg.isAlert) {
                                                    Icon(
                                                        imageVector = Icons.Default.Warning,
                                                        contentDescription = null,
                                                        tint = CrimsonAlert,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        "EMERGENCY SOS",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = CrimsonAlert
                                                    )
                                                } else {
                                                    Text(
                                                        if (isMe) "You" else "Peer",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isMe) TechBluePrimary else TextSecondaryMuted
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    timeStr,
                                                    fontSize = 10.sp,
                                                    color = TextSecondaryMuted
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    imageVector = Icons.Default.VolumeUp,
                                                    contentDescription = "Replay",
                                                    tint = TechBluePrimary.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }

                                         Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            msg.text,
                                            fontSize = 14.sp,
                                            color = TextPrimaryDark,
                                            lineHeight = 19.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Live Incoming Peer Transcript Bubble (Streaming in Real-Time)
                        if (remotePeerTypingText.isNotBlank()) {
                            item(key = "live_peer_transcript") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFFFEF9C3),
                                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFACC15)),
                                        shadowElevation = 2.dp,
                                        modifier = Modifier.widthIn(max = 300.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .background(Color(0xFFCA8A04), CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        "Peer is speaking...",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF854D0E)
                                                    )
                                                }
                                                Text(
                                                    "LIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color(0xFFCA8A04)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                remotePeerTypingText,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF713F12),
                                                lineHeight = 19.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Live Local User Transcription Bubble (Speaking in Real-Time)
                        if (currentPartialText.isNotBlank() && !currentPartialText.startsWith("⏳")) {
                            item(key = "live_local_transcript") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color(0xFFDBEAFE),
                                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF93C5FD)),
                                        shadowElevation = 2.dp,
                                        modifier = Modifier.widthIn(max = 300.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .background(TechBluePrimary, CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        "You (Transcribing...)",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TechBluePrimary
                                                    )
                                                }
                                                Text(
                                                    "LIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = TechBluePrimary
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                currentPartialText,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimaryDark,
                                                lineHeight = 19.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Transceiver Deck
            Surface(
                color = SurfaceCard,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Quick Action Chips Row
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        val quickChips = listOf(
                            "✅ Understood",
                            "🆘 Need Assistance",
                            "📍 Position Clear",
                            "⏸ Stand By",
                            "👍 Roger That"
                        )
                        items(quickChips) { chip ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = SurfaceCardAlt,
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier.clickable {
                                    onQuickReplySelected(chip)
                                }
                            ) {
                                Text(
                                    chip,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimaryDark,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Main Push-To-Talk Center
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Continuous Hands-Free VAD Toggle
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isContinuousMode) Color(0xFFDCFCE7) else SurfaceCardAlt,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isContinuousMode) Color(0xFF86EFAC) else BorderSubtle
                            ),
                            modifier = Modifier
                                .clickable { onToggleContinuousMode(!isContinuousMode) }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "Hands-Free",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isContinuousMode) Color(0xFF15803D) else TextSecondaryMuted
                                )
                                Text(
                                    if (isContinuousMode) "ACTIVE" else "OFF",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isContinuousMode) Color(0xFF15803D) else TextSecondaryMuted
                                )
                            }
                        }

                        // Prominent Push-To-Talk Button with Instant Pointer Response
                        val buttonColor = when {
                            isPttHolding -> Color(0xFF1D4ED8)
                            isContinuousMode -> EmeraldConnected
                            else -> TechBluePrimary
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .scale(if (isPttHolding) pttPulseScale else 1.0f)
                                .clip(RoundedCornerShape(30.dp))
                                .background(buttonColor)
                                .pointerInput(isContinuousMode) {
                                    if (!isContinuousMode) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            isPttHolding = true
                                            onPttPress()

                                            waitForUpOrCancellation()
                                            isPttHolding = false
                                            onPttRelease()
                                        }
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isPttHolding) "RELEASE TO SEND" else if (isContinuousMode) "SPEAKING FREELY" else "HOLD TO TALK",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                        }

                        // SOS Emergency Broadcast Button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFEF2F2),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                            modifier = Modifier
                                .clickable {
                                    onBroadcastAlert("EMERGENCY: Immediate assistance requested!")
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = CrimsonAlert,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "SOS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CrimsonAlert
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
