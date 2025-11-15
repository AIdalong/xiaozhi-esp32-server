package xiaozhi.modules.agent.dto;

import lombok.Data;

@Data
public class AgentCreateByDeviceResponseDTO {
    String device_id;
    String agent_id;
    String agent_name;
    String create_time;
}
