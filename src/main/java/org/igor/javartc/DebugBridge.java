package org.igor.javartc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Browser debug bridge.
 * <p>
 * Browser → server : {@code POST /api/debug/log}  {level, msg, data?}
 *                    printed to docker logs as  [BROWSER] ...
 * Server → browser : {@code GET  /api/debug/events}  SSE stream (EventSource)
 *                    {@code POST /api/debug/cmd}   {cmd:"call", ...}
 *                    forwards the payload to every subscribed tab as an SSE event.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugBridge {

    private static final Logger LOG = LoggerFactory.getLogger("BROWSER");
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 min

    private final ObjectMapper mapper;
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    public DebugBridge(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── SSE stream (server → browser) ──────────────────────────────────────────

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        long id = idSeq.incrementAndGet();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(id, emitter);
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(()    -> emitters.remove(id));
        emitter.onError(e ->      emitters.remove(id));
        LOG.info("SSE subscriber connected [{}] — {} tab(s) total", id, emitters.size());
        return emitter;
    }

    // ── Send a command to all subscribed browsers ───────────────────────────────

    @PostMapping("/cmd")
    public ResponseEntity<String> sendCommand(@RequestBody Map<String, Object> body) {
        if (emitters.isEmpty()) return ResponseEntity.ok("no browsers subscribed");
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (Exception e) { return ResponseEntity.badRequest().body("bad json"); }

        int sent = 0;
        for (var entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().name("cmd").data(json));
                sent++;
            } catch (Exception e) {
                emitters.remove(entry.getKey());
            }
        }
        return ResponseEntity.ok("sent to " + sent + " tab(s)");
    }

    // ── Receive browser logs ────────────────────────────────────────────────────

    @PostMapping("/log")
    public ResponseEntity<Void> receiveLog(@RequestBody Map<String, Object> body) {
        String level = String.valueOf(body.getOrDefault("level", "log"));
        String msg   = String.valueOf(body.getOrDefault("msg",   ""));
        Object data  = body.get("data");
        try {
            String line = data != null ? msg + " " + mapper.writeValueAsString(data) : msg;
            switch (level) {
                case "error" -> LOG.error("[BROWSER] {}", line);
                case "warn"  -> LOG.warn ("[BROWSER] {}", line);
                default      -> LOG.info ("[BROWSER] {}", line);
            }
        } catch (Exception ignored) {}
        return ResponseEntity.ok().build();
    }
}
