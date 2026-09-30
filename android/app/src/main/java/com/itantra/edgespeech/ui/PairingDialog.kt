package com.itantra.edgespeech.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PairingDialog(
    localNodeId: String,
    pairingCode: String = "192.168.43.1",
    onDismiss: () -> Unit,
    onConfirmPairing: (String) -> Unit
) {
    var remoteCodeInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Direct Peer Connection", fontWeight = FontWeight.Bold, color = TextPrimaryDark)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Both devices connect automatically on the same Wi-Fi or Hotspot. If needed, you can enter the peer's IP address manually:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryMuted
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCardAlt, RoundedCornerShape(10.dp))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "YOUR DEVICE IP",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondaryMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            pairingCode,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TechBluePrimary
                        )
                    }
                }

                OutlinedTextField(
                    value = remoteCodeInput,
                    onValueChange = { remoteCodeInput = it },
                    label = { Text("Peer IP (e.g. 192.168.43.1)") },
                    placeholder = { Text("192.168.43.1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmPairing(remoteCodeInput) },
                colors = ButtonDefaults.buttonColors(containerColor = TechBluePrimary)
            ) {
                Text("Connect Directly")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondaryMuted)
            }
        }
    )
}
