package xiaozhi.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色更新结果")
public class AgentRoleUpdateResponseDTO {

    String agent_id;
    String configure_time;
    String voice_url;
}
