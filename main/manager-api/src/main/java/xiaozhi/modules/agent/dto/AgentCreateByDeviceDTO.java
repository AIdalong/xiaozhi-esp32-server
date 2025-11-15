package xiaozhi.modules.agent.dto;

import lombok.Data;

@Data
public class AgentCreateByDeviceDTO {
    private String device_model;        // 设备型号
    private String firmware_version;   // 固件版本
    private String mac_address;   // Mac地址
}
