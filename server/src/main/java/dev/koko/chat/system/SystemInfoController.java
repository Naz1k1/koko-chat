package dev.koko.chat.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP 适配层，只接收系统信息请求并委托 Service，不直接访问网络连接或数据库。 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {
    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    /** 供桌面端识别服务和读取监听地址；该公开探针不授予任何聊天权限。 */
    @GetMapping("/info")
    public SystemInfo info() {
        return service.info();
    }
}
