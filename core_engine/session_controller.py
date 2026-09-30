"""
iTantra Session Controller & State Machine
Governs peer discovery, authentication, heartbeat liveness, transport tier fallback, and degradation states.
"""

import time
from enum import Enum
from typing import Optional, Callable, Dict, Any


class ConnectionState(str, Enum):
    DISCONNECTED = "DISCONNECTED"
    DISCOVERING = "DISCOVERING"
    CONNECTING = "CONNECTING"
    AUTHENTICATING = "AUTHENTICATING"
    CONNECTED = "CONNECTED"
    DEGRADED = "DEGRADED"
    RECONNECTING = "RECONNECTING"


class TransportTier(str, Enum):
    WIFI_DIRECT = "WIFI_DIRECT"       # Tier 1: Direct P2P link (High speed, long range)
    LOCAL_WIFI = "LOCAL_WIFI"         # Tier 2: Existing LAN via NSD (mDNS)
    BLUETOOTH = "BLUETOOTH"           # Tier 3: RFCOMM fallback link
    OFFLINE = "OFFLINE"               # Tier 4: Store-and-forward queue only


class SessionController:
    def __init__(
        self,
        node_id: str,
        heartbeat_interval_sec: float = 2.0,
        heartbeat_timeout_sec: float = 6.0,
        degradation_threshold_loss_pct: float = 15.0,
    ):
        self.node_id = node_id
        self.state = ConnectionState.DISCONNECTED
        self.active_transport = TransportTier.WIFI_DIRECT
        self.peer_node_id: Optional[str] = None
        self.session_id: int = 0
        
        self.heartbeat_interval_sec = heartbeat_interval_sec
        self.heartbeat_timeout_sec = heartbeat_timeout_sec
        self.degradation_threshold_loss_pct = degradation_threshold_loss_pct

        self.last_heartbeat_sent: float = 0.0
        self.last_heartbeat_received: float = 0.0
        
        # Telemetry
        self.rtt_ms: float = 25.0
        self.packet_loss_pct: float = 0.0
        self.total_packets_sent: int = 0
        self.total_packets_lost: int = 0

        self.on_state_change: Optional[Callable[[ConnectionState, TransportTier], None]] = None

    def set_state(self, new_state: ConnectionState):
        if self.state != new_state:
            old_state = self.state
            self.state = new_state
            if self.on_state_change:
                self.on_state_change(new_state, self.active_transport)

    def start_discovery(self):
        self.set_state(ConnectionState.DISCOVERING)

    def peer_discovered(self, peer_id: str, transport: TransportTier):
        self.peer_node_id = peer_id
        self.active_transport = transport
        self.set_state(ConnectionState.CONNECTING)

    def handshake_started(self, session_id: int):
        self.session_id = session_id
        self.set_state(ConnectionState.AUTHENTICATING)

    def authentication_successful(self):
        now = time.time()
        self.last_heartbeat_received = now
        self.set_state(ConnectionState.CONNECTED)

    def record_heartbeat_received(self, rtt_ms: float):
        self.last_heartbeat_received = time.time()
        # Exponential moving average for smooth RTT tracking
        self.rtt_ms = (0.8 * self.rtt_ms) + (0.2 * rtt_ms)

    def check_health(self) -> ConnectionState:
        """Periodic health check driven by timer or event loop."""
        if self.state not in (ConnectionState.CONNECTED, ConnectionState.DEGRADED):
            return self.state

        now = time.time()
        silence = now - self.last_heartbeat_received

        if silence > self.heartbeat_timeout_sec:
            # Missed heartbeats beyond threshold -> trigger fallback
            self.trigger_transport_fallback()
            return self.state

        # Check packet loss degradation
        if self.total_packets_sent > 10:
            loss_rate = (self.total_packets_lost / self.total_packets_sent) * 100.0
            self.packet_loss_pct = round(loss_rate, 1)
            if self.packet_loss_pct >= self.degradation_threshold_loss_pct:
                self.set_state(ConnectionState.DEGRADED)
            else:
                self.set_state(ConnectionState.CONNECTED)

        return self.state

    def trigger_transport_fallback(self):
        """Hysteresis-based transport demotion to prevent thrashing."""
        if self.active_transport == TransportTier.WIFI_DIRECT:
            self.active_transport = TransportTier.LOCAL_WIFI
            self.set_state(ConnectionState.RECONNECTING)
        elif self.active_transport == TransportTier.LOCAL_WIFI:
            self.active_transport = TransportTier.BLUETOOTH
            self.set_state(ConnectionState.RECONNECTING)
        elif self.active_transport == TransportTier.BLUETOOTH:
            self.active_transport = TransportTier.OFFLINE
            self.set_state(ConnectionState.DISCONNECTED)

    def manual_switch_transport(self, transport: TransportTier):
        self.active_transport = transport
        if transport == TransportTier.OFFLINE:
            self.set_state(ConnectionState.DISCONNECTED)
        else:
            self.set_state(ConnectionState.CONNECTED)

    def get_status_summary(self) -> Dict[str, Any]:
        return {
            "node_id": self.node_id,
            "peer_id": self.peer_node_id,
            "session_id": f"0x{self.session_id:016X}",
            "state": self.state.value,
            "transport": self.active_transport.value,
            "rtt_ms": round(self.rtt_ms, 1),
            "packet_loss_pct": self.packet_loss_pct,
            "packets_sent": self.total_packets_sent,
            "packets_lost": self.total_packets_lost,
        }
