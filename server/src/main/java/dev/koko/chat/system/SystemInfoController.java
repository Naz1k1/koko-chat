package dev.koko.chat.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemInfoController {
    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return service.info();
    }
}
