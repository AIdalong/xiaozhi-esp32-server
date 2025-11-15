package xiaozhi.modules.agent.service.impl;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import lombok.AllArgsConstructor;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dto.*;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentPluginMapping;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.vo.AgentInfoVO;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dto.DeviceManualAddDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.model.dto.ModelProviderDTO;
import xiaozhi.modules.model.dto.VoiceDTO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.model.service.ModelProviderService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.sys.enums.SuperAdminEnum;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;

@Service
@AllArgsConstructor
public class AgentServiceImpl extends BaseServiceImpl<AgentDao, AgentEntity> implements AgentService {
    private final AgentDao agentDao;
    private final DeviceDao deviceDao;
    private final TimbreService timbreModelService;
    private final ModelConfigService modelConfigService;
    private final RedisUtils redisUtils;
    private final DeviceService deviceService;
    private final AgentPluginMappingService agentPluginMappingService;
    private final AgentChatHistoryService agentChatHistoryService;
    private final AgentTemplateService agentTemplateService;
    private final ModelProviderService modelProviderService;

    @Override
    public PageData<AgentEntity> adminAgentList(Map<String, Object> params) {
        IPage<AgentEntity> page = agentDao.selectPage(
                getPage(params, "agent_name", true),
                new QueryWrapper<>());
        return new PageData<>(page.getRecords(), page.getTotal());
    }

    @Override
    public AgentInfoVO getAgentById(String id) {
        AgentInfoVO agent = agentDao.selectAgentInfoById(id);

        if (agent == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }

        if (agent.getMemModelId() != null && agent.getMemModelId().equals(Constant.MEMORY_NO_MEM)) {
            agent.setChatHistoryConf(Constant.ChatHistoryConfEnum.IGNORE.getCode());
            if (agent.getChatHistoryConf() == null) {
                agent.setChatHistoryConf(Constant.ChatHistoryConfEnum.RECORD_TEXT_AUDIO.getCode());
            }
        }
        // 无需额外查询插件列表，已通过SQL查询出来
        return agent;
    }

    @Override
    public boolean insert(AgentEntity entity) {
        // 如果ID为空，自动生成一个UUID作为ID
        if (entity.getId() == null || entity.getId().trim().isEmpty()) {
            entity.setId(UUID.randomUUID().toString().replace("-", ""));
        }

        // 如果智能体编码为空，自动生成一个带前缀的编码
        if (entity.getAgentCode() == null || entity.getAgentCode().trim().isEmpty()) {
            entity.setAgentCode("AGT_" + System.currentTimeMillis());
        }

        // 如果排序字段为空，设置默认值0
        if (entity.getSort() == null) {
            entity.setSort(0);
        }

        return super.insert(entity);
    }

    @Override
    public void deleteAgentByUserId(Long userId) {
        UpdateWrapper<AgentEntity> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId);
        baseDao.delete(wrapper);
    }

    @Override
    public List<AgentDTO> getUserAgents(Long userId) {
        QueryWrapper<AgentEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        List<AgentEntity> agents = agentDao.selectList(wrapper);
        return agents.stream().map(agent -> {
            AgentDTO dto = new AgentDTO();
            dto.setId(agent.getId());
            dto.setAgentName(agent.getAgentName());
            dto.setSystemPrompt(agent.getSystemPrompt());

            // 获取 TTS 模型名称
            dto.setTtsModelName(modelConfigService.getModelNameById(agent.getTtsModelId()));

            // 获取 LLM 模型名称
            dto.setLlmModelName(modelConfigService.getModelNameById(agent.getLlmModelId()));

            // 获取 VLLM 模型名称
            dto.setVllmModelName(modelConfigService.getModelNameById(agent.getVllmModelId()));

            // 获取记忆模型名称
            dto.setMemModelId(agent.getMemModelId());

            // 获取 TTS 音色名称
            dto.setTtsVoiceName(timbreModelService.getTimbreNameById(agent.getTtsVoiceId()));

            // 获取智能体最近的最后连接时长
            dto.setLastConnectedAt(deviceService.getLatestLastConnectionTime(agent.getId()));

            // 获取设备数量
            dto.setDeviceCount(getDeviceCountByAgentId(agent.getId()));
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public Integer getDeviceCountByAgentId(String agentId) {
        if (StringUtils.isBlank(agentId)) {
            return 0;
        }

        // 先从Redis中获取
        Integer cachedCount = (Integer) redisUtils.get(RedisKeys.getAgentDeviceCountById(agentId));
        if (cachedCount != null) {
            return cachedCount;
        }

        // 如果Redis中没有，则从数据库查询
        Integer deviceCount = agentDao.getDeviceCountByAgentId(agentId);

        // 将结果存入Redis
        if (deviceCount != null) {
            redisUtils.set(RedisKeys.getAgentDeviceCountById(agentId), deviceCount, 60);
        }

        return deviceCount != null ? deviceCount : 0;
    }

    @Override
    public AgentEntity getDefaultAgentByMacAddress(String macAddress) {
        if (StringUtils.isEmpty(macAddress)) {
            return null;
        }
        return agentDao.getDefaultAgentByMacAddress(macAddress);
    }

    @Override
    public boolean checkAgentPermission(String agentId, Long userId) {
        if (SecurityUser.getUser() == null || SecurityUser.getUser().getId() == null) {
            return false;
        }
        // 获取智能体信息
        AgentEntity agent = getAgentById(agentId);
        if (agent == null) {
            return false;
        }

        // 如果是超级管理员，直接返回true
        if (SecurityUser.getUser().getSuperAdmin() == SuperAdminEnum.YES.value()) {
            return true;
        }

        // 检查是否是智能体的所有者
        return userId.equals(agent.getUserId());
    }

    // 根据id更新智能体信息
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAgentById(String agentId, AgentUpdateDTO dto) {
        // 先查询现有实体
        AgentEntity existingEntity = this.getAgentById(agentId);
        if (existingEntity == null) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }

        // 只更新提供的非空字段
        if (dto.getAgentName() != null) {
            existingEntity.setAgentName(dto.getAgentName());
        }
        if (dto.getAgentCode() != null) {
            existingEntity.setAgentCode(dto.getAgentCode());
        }
        if (dto.getAsrModelId() != null) {
            existingEntity.setAsrModelId(dto.getAsrModelId());
        }
        if (dto.getVadModelId() != null) {
            existingEntity.setVadModelId(dto.getVadModelId());
        }
        if (dto.getLlmModelId() != null) {
            existingEntity.setLlmModelId(dto.getLlmModelId());
        }
        if (dto.getVllmModelId() != null) {
            existingEntity.setVllmModelId(dto.getVllmModelId());
        }
        if (dto.getTtsModelId() != null) {
            existingEntity.setTtsModelId(dto.getTtsModelId());
        }
        if (dto.getTtsVoiceId() != null) {
            existingEntity.setTtsVoiceId(dto.getTtsVoiceId());
        }
        if (dto.getMemModelId() != null) {
            existingEntity.setMemModelId(dto.getMemModelId());
        }
        if (dto.getIntentModelId() != null) {
            existingEntity.setIntentModelId(dto.getIntentModelId());
        }
        if (dto.getSystemPrompt() != null) {
            existingEntity.setSystemPrompt(dto.getSystemPrompt());
        }
        if (dto.getSummaryMemory() != null) {
            existingEntity.setSummaryMemory(dto.getSummaryMemory());
        }
        if (dto.getChatHistoryConf() != null) {
            existingEntity.setChatHistoryConf(dto.getChatHistoryConf());
        }
        if (dto.getLangCode() != null) {
            existingEntity.setLangCode(dto.getLangCode());
        }
        if (dto.getLanguage() != null) {
            existingEntity.setLanguage(dto.getLanguage());
        }
        if (dto.getSort() != null) {
            existingEntity.setSort(dto.getSort());
        }

        // 更新函数插件信息
        List<AgentUpdateDTO.FunctionInfo> functions = dto.getFunctions();
        if (functions != null) {
            // 1. 收集本次提交的 pluginId
            List<String> newPluginIds = functions.stream()
                    .map(AgentUpdateDTO.FunctionInfo::getPluginId)
                    .toList();

            // 2. 查询当前agent现有的所有映射
            List<AgentPluginMapping> existing = agentPluginMappingService.list(
                    new QueryWrapper<AgentPluginMapping>()
                            .eq("agent_id", agentId));
            Map<String, AgentPluginMapping> existMap = existing.stream()
                    .collect(Collectors.toMap(AgentPluginMapping::getPluginId, Function.identity()));

            // 3. 构造所有要 保存或更新 的实体
            List<AgentPluginMapping> allToPersist = functions.stream().map(info -> {
                AgentPluginMapping m = new AgentPluginMapping();
                m.setAgentId(agentId);
                m.setPluginId(info.getPluginId());
                m.setParamInfo(JsonUtils.toJsonString(info.getParamInfo()));
                AgentPluginMapping old = existMap.get(info.getPluginId());
                if (old != null) {
                    // 已存在，设置id表示更新
                    m.setId(old.getId());
                }
                return m;
            }).toList();

            // 4. 拆分：已有ID的走更新，无ID的走插入
            List<AgentPluginMapping> toUpdate = allToPersist.stream()
                    .filter(m -> m.getId() != null)
                    .toList();
            List<AgentPluginMapping> toInsert = allToPersist.stream()
                    .filter(m -> m.getId() == null)
                    .toList();

            if (!toUpdate.isEmpty()) {
                agentPluginMappingService.updateBatchById(toUpdate);
            }
            if (!toInsert.isEmpty()) {
                agentPluginMappingService.saveBatch(toInsert);
            }

            // 5. 删除本次不在提交列表里的插件映射
            List<Long> toDelete = existing.stream()
                    .filter(old -> !newPluginIds.contains(old.getPluginId()))
                    .map(AgentPluginMapping::getId)
                    .toList();
            if (!toDelete.isEmpty()) {
                agentPluginMappingService.removeBatchByIds(toDelete);
            }
        }

        // 设置更新者信息
        UserDetail user = SecurityUser.getUser();
        existingEntity.setUpdater(user.getId());
        existingEntity.setUpdatedAt(new Date());

        // 更新记忆策略
        if (existingEntity.getMemModelId() == null || existingEntity.getMemModelId().equals(Constant.MEMORY_NO_MEM)) {
            // 删除所有记录
            agentChatHistoryService.deleteByAgentId(existingEntity.getId(), true, true);
            existingEntity.setSummaryMemory("");
        } else if (existingEntity.getChatHistoryConf() != null && existingEntity.getChatHistoryConf() == 1) {
            // 删除音频数据
            agentChatHistoryService.deleteByAgentId(existingEntity.getId(), true, false);
        }

        boolean b = validateLLMIntentParams(dto.getLlmModelId(), dto.getIntentModelId());
        if (!b) {
            throw new RenException(ErrorCode.LLM_INTENT_PARAMS_MISMATCH);
        }
        this.updateById(existingEntity);
    }

    /**
     * 验证大语言模型和意图识别的参数是否符合匹配
     * 
     * @param llmModelId    大语言模型id
     * @param intentModelId 意图识别id
     * @return T 匹配 : F 不匹配
     */
    private boolean validateLLMIntentParams(String llmModelId, String intentModelId) {
        if (StringUtils.isBlank(llmModelId)) {
            return true;
        }
        ModelConfigEntity llmModelData = modelConfigService.selectById(llmModelId);
        String type = llmModelData.getConfigJson().get("type").toString();
        // 如果查询大语言模型是openai或者ollama，意图识别选参数都可以
        if ("openai".equals(type) || "ollama".equals(type)) {
            return true;
        }
        // 除了openai和ollama的类型，不可以选择id为Intent_function_call（函数调用）的意图识别
        return !"Intent_function_call".equals(intentModelId);
    }

    public static boolean isValidMac(String mac) {
        if (mac == null || mac.length() != 17) {
            return false;
        }
        // 正则：忽略大小写，6 组 [0-9A-F]{2}，分隔符为 “-” 或 “:”
        return mac.matches("(?i)^([0-9A-F]{2}[:]){5}[0-9A-F]{2}$");
    }

    public static boolean isInWhiteList(String appVersion){
        ArrayList<String> names = new ArrayList<>(Arrays.asList(
                "LUKKA"
        ));
        if(names.contains(appVersion))
            return true;
        else
            return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentCreateByDeviceResponseDTO createAgentByDevice(AgentCreateByDeviceDTO dto){

        // 判断设备型号是否为空
        if(dto.getDevice_model() == null || dto.getDevice_model().length() == 0){
            throw new RenException(ErrorCode.DEVICE_EMPTY);
        }
        // 判断固件版本不能为空
        if(dto.getFirmware_version() == null || dto.getFirmware_version().length() == 0){
            throw new RenException(ErrorCode.FIRMWARE_VERSION_EMPTY);
        }

        // 判断MAC地址格式
        if(!isValidMac(dto.getMac_address())){
            throw new RenException(ErrorCode.MAC_ADDRESS_FORMAT_ERROR);
        }

        // 判断是否有重复的MAC地址
        AgentEntity agent = getDefaultAgentByMacAddress(dto.getMac_address().toUpperCase());

        if(agent != null){
            throw new RenException(ErrorCode.DEVICE_DUPLICATE);
        }

        // 判断是否在白名单中
        if(!isInWhiteList(dto.getDevice_model())){
            throw new RenException(ErrorCode.DEVICE_NOT_IN_WHITELIST);
        }


        // 设备是否注册
        // 设备注册了，部分信息不正确
        DeviceEntity deviceEntity = deviceService.getDeviceByMacAddress(dto.getMac_address().toUpperCase());

        if(deviceEntity == null){
            throw new RenException(ErrorCode.DEVICE_NOT_IN_SYSTEM);
        }

        if(!deviceEntity.getBoard().equals(dto.getDevice_model()) || !deviceEntity.getAppVersion().equals(dto.getFirmware_version())){
            throw new RenException(ErrorCode.DEVICE_INFORMATION_ERROR);
        }


        AgentCreateDTO agentCreateDTO = new AgentCreateDTO();
        agentCreateDTO.setAgentName(dto.getMac_address().toUpperCase());
        String agentId = createAgent(agentCreateDTO);

        UserDetail user = SecurityUser.getUser();

        Date currentTime = new Date();
        deviceEntity.setAgentId(agentId);
        deviceEntity.setUserId(user.getId());
        deviceEntity.setCreator(user.getId());
        deviceEntity.setAutoUpdate(1);
        deviceEntity.setUpdater(user.getId());
        deviceEntity.setUpdateDate(currentTime);
        deviceEntity.setLastConnectedAt(currentTime);

        deviceDao.updateById(deviceEntity);



        AgentCreateByDeviceResponseDTO deviceResponseDTO = new AgentCreateByDeviceResponseDTO();
        deviceResponseDTO.setAgent_id(agentId);
        deviceResponseDTO.setAgent_name(dto.getMac_address());
        deviceResponseDTO.setDevice_id(dto.getMac_address());
        deviceResponseDTO.setCreate_time(new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()));

        // 添加：清除智能体设备数量缓存
        redisUtils.delete(RedisKeys.getAgentDeviceCountById(agentId));

        return deviceResponseDTO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentRoleUpdateResponseDTO updateAgentRoleById(String agentId, AgentRoleUpdateDTO dto){
        TimbreDetailsVO timbreDetailsVO = timbreModelService.get(dto.getVoice_type());
        // AgentId没有写
        if(dto.getAgent_id()== null || dto.getAgent_id().length() == 0){
            throw new RenException(ErrorCode.AGENT_ID_EMPTY);
        }
        AgentInfoVO agentInfoVO=  getAgentById(dto.getAgent_id());
        // Agent不存在
        if(agentInfoVO == null){
            throw new RenException(ErrorCode.AGENT_NOT_EXIST);
        }
        // 不存在对应的音色
        if(timbreDetailsVO == null){
            throw new RenException(ErrorCode.VOICE_ERROR);
        }
        // 名字长度是否正确
        if(!(dto.getName().codePointCount(0,dto.getName().length())>=2 && dto.getName().codePointCount(0,dto.getName().length())<=5)){
            throw new RenException(ErrorCode.NAME_LENGTH_ERROR);
        }
        // role_prompt 是否正确
        if(dto.getRole_prompt() == null || dto.getRole_prompt().length() == 0){
            throw new RenException(ErrorCode.ROLE_PROMPT_EMPTY);
        }
        AgentUpdateDTO new_dto = new AgentUpdateDTO();

        new_dto.setAsrModelId("ASR_FunASR");
        new_dto.setIntentModelId("Intent_intent_llm");
        new_dto.setVadModelId("VAD_SileroVAD");
        new_dto.setLlmModelId("LLM_DoubaoLLM");
        new_dto.setVllmModelId("VLLM_ChatGLMVLLM");
        new_dto.setMemModelId("Memory_mem_local_short");
        new_dto.setAgentName(dto.getName());
        new_dto.setTtsVoiceId(dto.getVoice_type());
        new_dto.setTtsModelId(timbreDetailsVO.getTtsModelId());
        new_dto.setSystemPrompt(dto.getRole_prompt());

        updateAgentById(dto.getAgent_id(),new_dto);

        AgentRoleUpdateResponseDTO agentRoleUpdateResponseDTO = new AgentRoleUpdateResponseDTO();
        agentRoleUpdateResponseDTO.setAgent_id(dto.getAgent_id());
        agentRoleUpdateResponseDTO.setVoice_url(timbreDetailsVO.getVoiceDemo());
        agentRoleUpdateResponseDTO.setConfigure_time(new Date().toString());

        return agentRoleUpdateResponseDTO;

    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createAgent(AgentCreateDTO dto) {
        // 转换为实体
        AgentEntity entity = ConvertUtils.sourceToTarget(dto, AgentEntity.class);

        // 获取默认模板
        AgentTemplateEntity template = agentTemplateService.getDefaultTemplate();
        if (template != null) {
            // 设置模板中的默认值
            entity.setAsrModelId(template.getAsrModelId());
            entity.setVadModelId(template.getVadModelId());
            entity.setLlmModelId(template.getLlmModelId());
            entity.setVllmModelId(template.getVllmModelId());
            entity.setTtsModelId(template.getTtsModelId());

            if (template.getTtsVoiceId() == null && template.getTtsModelId() != null) {
                ModelConfigEntity ttsModel = modelConfigService.selectById(template.getTtsModelId());
                if (ttsModel != null && ttsModel.getConfigJson() != null) {
                    Map<String, Object> config = ttsModel.getConfigJson();
                    String voice = (String) config.get("voice");
                    if (StringUtils.isBlank(voice)) {
                        voice = (String) config.get("speaker");
                    }
                    VoiceDTO timbre = timbreModelService.getByVoiceCode(template.getTtsModelId(), voice);
                    if (timbre != null) {
                        template.setTtsVoiceId(timbre.getId());
                    }
                }
            }

            entity.setTtsVoiceId(template.getTtsVoiceId());
            entity.setMemModelId(template.getMemModelId());
            entity.setIntentModelId(template.getIntentModelId());
            entity.setSystemPrompt(template.getSystemPrompt());
            entity.setSummaryMemory(template.getSummaryMemory());
            entity.setChatHistoryConf(template.getChatHistoryConf());
            entity.setLangCode(template.getLangCode());
            entity.setLanguage(template.getLanguage());
        }

        // 设置用户ID和创建者信息
        UserDetail user = SecurityUser.getUser();
        entity.setUserId(user.getId());
        entity.setCreator(user.getId());
        entity.setCreatedAt(new Date());

        // 保存智能体
        insert(entity);

        // 设置默认插件
        List<AgentPluginMapping> toInsert = new ArrayList<>();
        // 播放音乐、查天气、查新闻
        String[] pluginIds = new String[] { "SYSTEM_PLUGIN_MUSIC", "SYSTEM_PLUGIN_WEATHER",
                "SYSTEM_PLUGIN_NEWS_NEWSNOW" };
        for (String pluginId : pluginIds) {
            ModelProviderDTO provider = modelProviderService.getById(pluginId);
            if (provider == null) {
                continue;
            }
            AgentPluginMapping mapping = new AgentPluginMapping();
            mapping.setPluginId(pluginId);

            Map<String, Object> paramInfo = new HashMap<>();
            List<Map<String, Object>> fields = JsonUtils.parseObject(provider.getFields(), List.class);
            if (fields != null) {
                for (Map<String, Object> field : fields) {
                    paramInfo.put((String) field.get("key"), field.get("default"));
                }
            }
            mapping.setParamInfo(JsonUtils.toJsonString(paramInfo));
            mapping.setAgentId(entity.getId());
            toInsert.add(mapping);
        }
        // 保存默认插件
        agentPluginMappingService.saveBatch(toInsert);
        return entity.getId();
    }
}
