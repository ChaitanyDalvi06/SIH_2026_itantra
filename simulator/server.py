"""
iTantra EdgeSpeech Transceiver - Live Simulator & Evaluation Testbed Server
FastAPI + WebSocket backend coordinating dual virtual nodes (Phone A & Phone B),
network condition injection (loss, latency, jitter, transport switching),
cryptographic verification, and real-time telemetry streaming.

Enhanced with:
- Real-time WebSocket text relay for instant peer-to-peer speech delivery
- Partial text (typing indicator) relay between peers
- Contextual understanding endpoint (intent, urgency, entities)
- Language-specific endpoint configuration API
"""

import asyncio
import json
import time
import random
from typing import Dict, Any, List, Set
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from pathlib import Path

from core_engine.protocol import BinaryFrame, PacketType, LanguageCode, LANGUAGE_MAP, REVERSE_LANG_MAP
from core_engine.simulated_transceiver import SimulatedTransceiverNode
from core_engine.session_controller import ConnectionState, TransportTier
from core_engine.adaptive_endpoint import get_endpoint_config
from core_engine.fuzzer import ProtocolFuzzer
from benchmarks.latency_benchmarks import calculate_pipeline_latency
from simulator.context_engine import analyze_text

app = FastAPI(title="iTantra EdgeSpeech Simulator")
root_dir = Path(__file__).parent
public_dir = root_dir / "public"

# Shared Cryptographic Key for Authenticated Transceiver Pair
SHARED_SECRET = b"iTantra_Secured_Key_2026_Prod01!"

# Dual Phone Instances
node_a = SimulatedTransceiverNode(node_id="Phone_A_Pixel", secret_key=SHARED_SECRET)
node_b = SimulatedTransceiverNode(node_id="Phone_B_Galaxy", secret_key=SHARED_SECRET)

# Active WebSockets — mapped by node ID for targeted messaging
connected_clients: Set[WebSocket] = set()
# Track which WebSocket belongs to which phone node
client_node_map: Dict[WebSocket, str] = {}

# Network Simulation Parameters
network_config = {
    "latency_ms": 25.0,
    "packet_loss_pct": 0.0,
    "jitter_ms": 5.0,
    "reorder_probability": 0.0,
    "active_transport": "WIFI_DIRECT",
}


async def broadcast_ws(event: str, data: Any):
    msg = json.dumps({"event": event, "data": data, "timestamp": time.time()})
    disconnected = set()
    for ws in connected_clients:
        try:
            await ws.send_text(msg)
        except Exception:
            disconnected.add(ws)
    for ws in disconnected:
        connected_clients.discard(ws)
        client_node_map.pop(ws, None)


async def send_to_peer(sender_node_id: str, event: str, data: Any):
    """Send a message only to the peer (not back to the sender)."""
    msg = json.dumps({"event": event, "data": data, "timestamp": time.time()})
    disconnected = set()
    for ws in connected_clients:
        mapped_node = client_node_map.get(ws)
        # Send to clients that are NOT the sender
        if mapped_node and mapped_node != sender_node_id:
            try:
                await ws.send_text(msg)
            except Exception:
                disconnected.add(ws)
        # Also send to unregistered clients (dashboard viewers)
        elif mapped_node is None:
            try:
                await ws.send_text(msg)
            except Exception:
                disconnected.add(ws)
    for ws in disconnected:
        connected_clients.discard(ws)
        client_node_map.pop(ws, None)


main_loop = None

@app.on_event("startup")
def on_startup():
    global main_loop
    main_loop = asyncio.get_running_loop()
    # Pre-connect nodes
    node_a.session_controller.peer_discovered("Phone_B_Galaxy", TransportTier.WIFI_DIRECT)
    node_b.session_controller.peer_discovered("Phone_A_Pixel", TransportTier.WIFI_DIRECT)
    node_a.session_controller.authentication_successful()
    node_b.session_controller.authentication_successful()


def make_network_hook(source_node: SimulatedTransceiverNode, dest_node: SimulatedTransceiverNode):
    def hook(frame_bytes: bytes, sender_id: str):
        global main_loop
        if main_loop and main_loop.is_running():
            asyncio.run_coroutine_threadsafe(simulate_network_flight(frame_bytes, source_node, dest_node), main_loop)
        else:
            try:
                loop = asyncio.get_running_loop()
                loop.create_task(simulate_network_flight(frame_bytes, source_node, dest_node))
            except Exception:
                pass
    return hook

node_a.network_send_hook = make_network_hook(node_a, node_b)
node_b.network_send_hook = make_network_hook(node_b, node_a)


def make_event_listener(node_name: str):
    def listener(event: Dict[str, Any]):
        global main_loop
        if main_loop and main_loop.is_running():
            asyncio.run_coroutine_threadsafe(broadcast_ws("NODE_EVENT", event), main_loop)
        else:
            try:
                loop = asyncio.get_running_loop()
                loop.create_task(broadcast_ws("NODE_EVENT", event))
            except Exception:
                pass
    return listener

node_a.event_listener = make_event_listener("Phone_A")
node_b.event_listener = make_event_listener("Phone_B")


async def simulate_network_flight(frame_bytes: bytes, source: SimulatedTransceiverNode, dest: SimulatedTransceiverNode):
    # Check Offline state
    if network_config["active_transport"] == "OFFLINE":
        await broadcast_ws("NETWORK_DROP", {
            "source": source.node_id,
            "dest": dest.node_id,
            "reason": "LINK_OFFLINE",
            "bytes": len(frame_bytes),
        })
        return

    # Check simulated packet loss
    loss_roll = random.uniform(0.0, 100.0)
    if loss_roll < network_config["packet_loss_pct"]:
        source.session_controller.total_packets_lost += 1
        await broadcast_ws("NETWORK_DROP", {
            "source": source.node_id,
            "dest": dest.node_id,
            "reason": "SIMULATED_PACKET_LOSS",
            "loss_pct": network_config["packet_loss_pct"],
            "bytes": len(frame_bytes),
        })
        return

    # Simulated latency + jitter
    base_lat = network_config["latency_ms"]
    jitter = random.uniform(-network_config["jitter_ms"], network_config["jitter_ms"])
    flight_ms = max(2.0, base_lat + jitter)

    await broadcast_ws("PACKET_IN_FLIGHT", {
        "source": source.node_id,
        "dest": dest.node_id,
        "flight_ms": round(flight_ms, 1),
        "transport": network_config["active_transport"],
        "bytes_len": len(frame_bytes),
    })

    await asyncio.sleep(flight_ms / 1000.0)

    # Ingress at destination
    ingress_time = time.time()
    dest.receive_bytes(frame_bytes, ingress_time)

    # Parse for inspector
    try:
        parsed = BinaryFrame.deserialize(frame_bytes, secret_key=SHARED_SECRET)
        await broadcast_ws("PACKET_DELIVERED", {
            "source": source.node_id,
            "dest": dest.node_id,
            "frame": parsed.to_dict(),
            "raw_hex": frame_bytes.hex().upper(),
            "flight_ms": round(flight_ms, 1),
        })
    except Exception as e:
        pass


@app.get("/api/state")
def get_state():
    return {
        "node_a": node_a.session_controller.get_status_summary(),
        "node_b": node_b.session_controller.get_status_summary(),
        "network": network_config,
    }


@app.post("/api/network/config")
def update_network_config(config: Dict[str, Any]):
    for k in ["latency_ms", "packet_loss_pct", "jitter_ms", "active_transport"]:
        if k in config:
            network_config[k] = config[k]

    # Update session controller transport tiers
    tier = TransportTier[network_config["active_transport"]]
    node_a.session_controller.manual_switch_transport(tier)
    node_b.session_controller.manual_switch_transport(tier)

    return {"status": "ok", "network": network_config}


@app.post("/api/node/{node_id}/ptt")
async def handle_ptt(node_id: str, payload: Dict[str, Any]):
    node = node_a if "A" in node_id or "Pixel" in node_id else node_b
    action = payload.get("action", "release")
    text = payload.get("text", "")
    language = payload.get("language")
    is_alert = payload.get("is_alert", False)
    context = payload.get("context")

    if language:
        node.set_language(language)

    if action == "press":
        node.start_ptt()
    elif action == "update_partial":
        node.update_streaming_stt(text, is_speech=True)
    elif action == "release":
        node.release_ptt(final_text=text)
    elif action == "alert":
        node.broadcast_alert(text or "EMERGENCY ALERT: Immediate action required!")

    # If context was provided by client, broadcast it alongside the text event
    if context and text:
        sender_id = "A" if "A" in node_id or "Pixel" in node_id else "B"
        await send_to_peer(sender_id, "SPEECH_WITH_CONTEXT", {
            "sender": sender_id,
            "text": text,
            "language": language or "hi",
            "context": context,
            "is_alert": is_alert,
        })

    return {"status": "ok"}


@app.post("/api/node/{node_id}/language")
async def handle_language(node_id: str, payload: Dict[str, Any]):
    node = node_a if "A" in node_id or "Pixel" in node_id else node_b
    lang = payload.get("language", "hi")
    node.set_language(lang)
    return {"status": "ok", "language": lang}


@app.post("/api/node/{node_id}/continuous")
async def handle_continuous(node_id: str, payload: Dict[str, Any]):
    node = node_a if "A" in node_id or "Pixel" in node_id else node_b
    enabled = payload.get("enabled", False)
    node.is_continuous_mode = enabled
    return {"status": "ok", "continuous_mode": enabled}


@app.get("/api/endpoint/config/{language}")
def get_endpoint_language_config(language: str):
    """Returns language-specific endpointing configuration for client-side adaptive endpoint detector."""
    config = get_endpoint_config(language)
    return {"status": "ok", "config": config}


@app.post("/api/context/analyze")
async def analyze_context(payload: Dict[str, Any]):
    """
    Analyze text for contextual understanding.
    Returns intent, urgency, entities, confidence.
    """
    text = payload.get("text", "")
    language = payload.get("language", "hi")
    result = analyze_text(text, language)
    return {"status": "ok", "context": result}


@app.post("/api/fuzz")
def run_fuzzer(payload: Dict[str, Any] = None):
    count = (payload or {}).get("iterations", 1000)
    fuzzer = ProtocolFuzzer()
    results = fuzzer.run_fuzz_campaign(iterations=count)
    return results


@app.get("/api/benchmarks")
def get_benchmarks():
    matrix_path = root_dir.parent / "benchmarks" / "model_matrix.json"
    with open(matrix_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    waterfall = calculate_pipeline_latency(speech_duration_sec=2.0, device_profile="low_end")
    return {
        "matrix": data,
        "waterfall": waterfall,
    }


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_clients.add(websocket)
    try:
        # Send initial full state
        await websocket.send_text(json.dumps({
            "event": "INITIAL_STATE",
            "data": get_state(),
            "timestamp": time.time()
        }))
        while True:
            raw = await websocket.receive_text()
            try:
                msg = json.loads(raw)
                await handle_ws_message(websocket, msg)
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        connected_clients.discard(websocket)
        client_node_map.pop(websocket, None)


async def handle_ws_message(websocket: WebSocket, msg: Dict[str, Any]):
    """
    Handle incoming WebSocket messages from clients.
    Supports real-time text relay, typing indicators, and node registration.
    """
    event = msg.get("event", "")
    data = msg.get("data", {})

    if event == "REGISTER_NODE":
        # Client identifies itself as Phone A or Phone B
        node_id = data.get("node_id", "")
        client_node_map[websocket] = node_id

    elif event == "TYPING_PARTIAL":
        # Relay partial STT text to peer as typing indicator
        sender = data.get("sender", "")
        partial_text = data.get("partial_text", "")
        language = data.get("language", "hi")
        await send_to_peer(sender, "PEER_TYPING", {
            "sender": sender,
            "partial_text": partial_text,
            "language": language,
        })

    elif event == "SPEECH_FINALIZED":
        # Real-time relay of finalized speech text to peer
        sender = data.get("sender", "")
        text = data.get("text", "")
        language = data.get("language", "hi")
        is_alert = data.get("is_alert", False)
        context = data.get("context", {})

        # If no context provided by client, compute server-side
        if not context:
            context = analyze_text(text, language)

        # Route through the transceiver node for protocol processing
        node = node_a if sender == "A" else node_b
        node.set_language(language)
        if is_alert:
            node.broadcast_alert(text)
        else:
            node.release_ptt(final_text=text)

        # Also send the speech + context directly via WebSocket for instant display
        await send_to_peer(sender, "PEER_SPEECH_RECEIVED", {
            "sender": sender,
            "text": text,
            "language": language,
            "is_alert": is_alert,
            "context": context,
            "timestamp": time.time(),
        })

    elif event == "TYPING_STOPPED":
        # Clear typing indicator on peer
        sender = data.get("sender", "")
        await send_to_peer(sender, "PEER_TYPING_STOPPED", {
            "sender": sender,
        })


# Mount static assets
app.mount("/", StaticFiles(directory=str(public_dir), html=True), name="static")


if __name__ == "__main__":
    import uvicorn
    # Pre-connect nodes
    node_a.session_controller.peer_discovered("Phone_B_Galaxy", TransportTier.WIFI_DIRECT)
    node_b.session_controller.peer_discovered("Phone_A_Pixel", TransportTier.WIFI_DIRECT)
    node_a.session_controller.authentication_successful()
    node_b.session_controller.authentication_successful()
    uvicorn.run("simulator.server:app", host="0.0.0.0", port=8000, reload=False)
