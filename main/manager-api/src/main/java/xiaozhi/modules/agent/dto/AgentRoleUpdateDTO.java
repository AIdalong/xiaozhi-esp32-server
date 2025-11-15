package xiaozhi.modules.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色更新对象")
public class AgentRoleUpdateDTO {
    String agent_id;
    String name;
    String role_prompt;
    String voice_type;
}
