package dev.koko.chat.ops;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import static dev.koko.chat.ops.OperationsService.*;

/** 所有接口由 OpsFilter 验证独立凭据；POST 返回持久化操作，发布完成不等于设备已收。 */
@RestController @Profile("local") @RequestMapping("/internal/ops")
public class OperationsController {
    private final OperationsService service;private final OperationsMonitor monitor;
    public OperationsController(OperationsService service,OperationsMonitor monitor) { this.service=service;this.monitor=monitor; }
    @GetMapping("/status") public ResponseEntity<OperationsMonitor.Snapshot> status() {
        var snapshot=monitor.snapshot();boolean stale=snapshot.collectedAt().isBefore(Instant.now().minusSeconds(90));
        return ResponseEntity.status(stale?503:200).body(snapshot);
    }
    @GetMapping(value="/metrics",produces="text/plain;version=0.0.4;charset=utf-8") public String metrics() { return monitor.prometheus(); }
    @GetMapping("/dead-letters") public Page list(@RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit) { return service.list(after,limit); }
    @PostMapping("/dead-letters/{id}/replays") public ResponseEntity<Action> replay(@PathVariable String id,@RequestBody Command command) { return ResponseEntity.accepted().body(service.submit(id,"REPLAY",command)); }
    @PostMapping("/dead-letters/{id}/acknowledgements") public Action acknowledge(@PathVariable String id,@RequestBody Command command) { return service.submit(id,"ACK",command); }
    @GetMapping("/actions/{id}") public Action action(@PathVariable String id) { return service.action(id); }
}
