'use strict';

const ICE_CONFIG = {
  iceServers: [{ urls: 'turn:localhost:3478', username: 'javartc', credential: 'javartc' }]
};

const MEDIA_CONSTRAINTS = {
  video: { width: { min: 160, max: 640 }, height: { min: 120, max: 480 } },
  audio: false
};

// ── Debug bridge (console → server, SSE commands ← server) ───────────────────

const _nativeLog   = console.log.bind(console);
const _nativeWarn  = console.warn.bind(console);
const _nativeError = console.error.bind(console);

function remoteLog(level, args) {
  const msg  = args.map(a => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ');
  fetch('/api/debug/log', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ level, msg })
  }).catch(() => {});  // fire-and-forget
}

console.log   = (...a) => { _nativeLog(...a);   remoteLog('log',   a); };
console.warn  = (...a) => { _nativeWarn(...a);  remoteLog('warn',  a); };
console.error = (...a) => { _nativeError(...a); remoteLog('error', a); };

window.addEventListener('unhandledrejection', e =>
  console.error('Unhandled rejection:', e.reason));
window.addEventListener('error', e =>
  console.error('Uncaught error:', e.message, e.filename + ':' + e.lineno));

function connectSse() {
  const es = new EventSource('/api/debug/events');
  es.addEventListener('cmd', e => {
    try {
      const { cmd, ...params } = JSON.parse(e.data);
      console.log('SSE command received:', cmd, params);
      if (cmd === 'call')  startCall();
      if (cmd === 'state') reportState();
      if (cmd === 'stats') reportStats();
    } catch (err) { console.error('SSE parse error', err); }
  });
  es.onerror = () => setTimeout(connectSse, 3000);  // reconnect on error
}

function reportState() {
  if (!pc) { console.log('state: no RTCPeerConnection'); return; }
  console.log('state: connection=' + pc.connectionState
    + ' ice=' + pc.iceConnectionState
    + ' gathering=' + pc.iceGatheringState
    + ' signaling=' + pc.signalingState);
  pc.getTransceivers().forEach((t, i) => {
    console.log('  transceiver[' + i + '] direction=' + t.direction
      + ' currentDirection=' + t.currentDirection
      + ' mid=' + t.mid);
    if (t.receiver && t.receiver.track) {
      const tr = t.receiver.track;
      console.log('  receiver track: kind=' + tr.kind + ' id=' + tr.id
        + ' readyState=' + tr.readyState + ' muted=' + tr.muted + ' enabled=' + tr.enabled);
    }
  });
}

async function reportStats() {
  if (!pc) { console.log('stats: no RTCPeerConnection'); return; }
  const report = await pc.getStats();
  report.forEach(s => {
    if (s.type === 'inbound-rtp' && s.kind === 'video') {
      console.log('inbound-rtp video: bytesReceived=' + s.bytesReceived
        + ' packetsReceived=' + s.packetsReceived
        + ' framesDecoded=' + s.framesDecoded
        + ' framesDropped=' + s.framesDropped
        + ' frameWidth=' + s.frameWidth + ' frameHeight=' + s.frameHeight);
    }
    if (s.type === 'outbound-rtp' && s.kind === 'video') {
      console.log('outbound-rtp video: bytesSent=' + s.bytesSent
        + ' packetsSent=' + s.packetsSent
        + ' framesSent=' + s.framesSent);
    }
    if (s.type === 'candidate-pair' && s.state === 'succeeded') {
      console.log('candidate-pair succeeded: bytesSent=' + s.bytesSent
        + ' bytesReceived=' + s.bytesReceived + ' nominated=' + s.nominated);
    }
  });
}

// ── WebSocket ─────────────────────────────────────────────────────────────────

let ws;
let wsReady = false;
let wsQueue = [];

function connectWs() {
  ws = new WebSocket('ws://' + location.host + '/rtc');
  ws.onopen  = () => { wsReady = true;  wsQueue.forEach(m => ws.send(m)); wsQueue = []; };
  ws.onclose = () => { wsReady = false; console.warn('WebSocket closed, reconnecting...'); setTimeout(connectWs, 2000); };
  ws.onerror = e  => console.error('WebSocket error', e);
  ws.onmessage = handleServerMessage;
}

function wsSend(obj) {
  const s = JSON.stringify(obj);
  if (wsReady) {
    ws.send(s);
  } else {
    wsQueue.push(s);
    if (!ws || ws.readyState === WebSocket.CLOSED) connectWs();
  }
}

// ── WebRTC ────────────────────────────────────────────────────────────────────

let pc;

async function startCall() {
  if (pc) pc.close();
  pc = new RTCPeerConnection(ICE_CONFIG);

  pc.ontrack = evt => {
    const remoteVideo = document.getElementById('remoteVideo');
    console.log('ontrack: kind=' + evt.track.kind + ' id=' + evt.track.id
      + ' readyState=' + evt.track.readyState + ' streams=' + evt.streams.length);
    evt.track.onmute     = () => console.log('Remote track MUTED');
    evt.track.onunmute   = () => console.log('Remote track UNMUTED');
    evt.track.onended    = () => console.log('Remote track ENDED');
    if (remoteVideo.srcObject !== evt.streams[0]) {
      remoteVideo.srcObject = evt.streams[0];
      console.log('Remote srcObject set');
    }
    // Video element lifecycle events
    remoteVideo.onloadedmetadata = () => console.log('remoteVideo: loadedmetadata'
      + ' size=' + remoteVideo.videoWidth + 'x' + remoteVideo.videoHeight);
    remoteVideo.onplay    = () => console.log('remoteVideo: play');
    remoteVideo.onplaying = () => console.log('remoteVideo: PLAYING ✓');
    remoteVideo.onwaiting = () => console.log('remoteVideo: waiting (buffering)');
    remoteVideo.onstalled = () => console.log('remoteVideo: stalled');
    remoteVideo.onerror   = () => console.error('remoteVideo: ERROR', remoteVideo.error);
    // Auto-report stats every 3 s so we can see bytes flowing without manual cmd
    const statsInterval = setInterval(reportStats, 3000);
    pc.addEventListener('connectionstatechange', () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
        clearInterval(statsInterval);
      }
    });
  };

  const gatheredCandidates = [];
  pc.onicecandidate = evt => {
    if (evt.candidate) gatheredCandidates.push(evt.candidate.toJSON());
  };

  pc.onconnectionstatechange = () => console.log('Connection state:', pc.connectionState);
  pc.oniceconnectionstatechange = () => console.log('ICE state:', pc.iceConnectionState);
  pc.onicegatheringstatechange = () => console.log('Gathering state:', pc.iceGatheringState);
  pc.onsignalingstatechange = () => console.log('Signaling state:', pc.signalingState);

  try {
    const stream = await navigator.mediaDevices.getUserMedia(MEDIA_CONSTRAINTS);
    stream.getTracks().forEach(t => pc.addTrack(t, stream));
    document.getElementById('localVideo').srcObject = stream;

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    await waitForIceGathering(pc);
    console.log('ICE gathering complete, sending offer with', gatheredCandidates.length, 'candidates');

    wsSend({ type: 'offer', sdp: pc.localDescription.sdp, candidates: gatheredCandidates });
  } catch (err) {
    console.error('Call setup error', err);
  }
}

function waitForIceGathering(pc) {
  return new Promise(resolve => {
    if (pc.iceGatheringState === 'complete') { resolve(); return; }
    pc.onicegatheringstatechange = () => {
      if (pc.iceGatheringState === 'complete') resolve();
    };
  });
}

function handleServerMessage(evt) {
  const data = JSON.parse(evt.data);
  console.log('Server message type:', data.type, 'candidates:', (data.candidates || []).length);
  if (data.type === 'answer' && data.sdp) {
    // Log direction lines so we can verify sendrecv
    const dirLines = data.sdp.split('\n').filter(l =>
      l.startsWith('a=sendrecv') || l.startsWith('a=recvonly') ||
      l.startsWith('a=sendonly') || l.startsWith('a=inactive') ||
      l.startsWith('m='));
    console.log('SDP answer direction lines:', dirLines.map(l => l.trim()).join(' | '));
    pc.setRemoteDescription(new RTCSessionDescription(data))
      .then(() => {
        console.log('Remote description set OK');
        if (data.candidates && data.candidates.length) {
          return Promise.all(data.candidates.map(c =>
            pc.addIceCandidate(new RTCIceCandidate(c))
              .catch(e => console.warn('addIceCandidate error', e))
          ));
        }
      })
      .catch(e => console.error('setRemoteDescription failed', e));
  }
}

// ── Init ──────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  connectSse();
  connectWs();
  document.getElementById('callBtn').addEventListener('click', startCall);
});
