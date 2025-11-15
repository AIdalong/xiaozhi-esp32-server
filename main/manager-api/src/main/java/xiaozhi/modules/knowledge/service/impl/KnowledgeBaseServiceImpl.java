package xiaozhi.modules.knowledge.service.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.service.impl.BaseServiceImpl;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.knowledge.dao.KnowledgeBaseDao;
import xiaozhi.modules.knowledge.dto.KnowledgeBaseDTO;
import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;
import xiaozhi.modules.knowledge.service.KnowledgeBaseService;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;

@Service
@AllArgsConstructor
@Slf4j
public class KnowledgeBaseServiceImpl extends BaseServiceImpl<KnowledgeBaseDao, KnowledgeBaseEntity>
        implements KnowledgeBaseService {

    private final KnowledgeBaseDao knowledgeBaseDao;
    private final ModelConfigService modelConfigService;
    private final ModelConfigDao modelConfigDao;
    private final RedisUtils redisUtils;
    private RestTemplate restTemplate = new RestTemplate();

    @Override
    public KnowledgeBaseEntity selectById(Serializable datasetId) {
        if (datasetId == null) {
            return null;
        }

        // 先从Redis获取缓存
        String key = RedisKeys.getKnowledgeBaseCacheKey(datasetId.toString());
        KnowledgeBaseEntity cachedEntity = (KnowledgeBaseEntity) redisUtils.get(key);
        if (cachedEntity != null) {
            return cachedEntity;
        }

        // 如果缓存中没有，则从数据库获取
        KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(datasetId);
        if (entity == null) {
            return null;
        }

        // 存入Redis缓存
        redisUtils.set(key, entity);

        return entity;
    }

    @Override
    public PageData<KnowledgeBaseDTO> getPageList(KnowledgeBaseDTO knowledgeBaseDTO, Integer page, Integer limit) {
        long curPage = page;
        long pageSize = limit;
        Page<KnowledgeBaseEntity> pageInfo = new Page<>(curPage, pageSize);

        QueryWrapper<KnowledgeBaseEntity> queryWrapper = new QueryWrapper<>();

        // 添加查询条件
        if (knowledgeBaseDTO != null) {
            queryWrapper.like(StringUtils.isNotBlank(knowledgeBaseDTO.getName()), "name", knowledgeBaseDTO.getName());
            queryWrapper.eq(knowledgeBaseDTO.getStatus() != null, "status", knowledgeBaseDTO.getStatus());
            queryWrapper.eq("creator", knowledgeBaseDTO.getCreator());
        }

        // 添加排序规则：按创建时间降序
        queryWrapper.orderByDesc("created_at");

        IPage<KnowledgeBaseEntity> knowledgeBaseEntityIPage = knowledgeBaseDao.selectPage(pageInfo, queryWrapper);

        // 获取分页数据
        PageData<KnowledgeBaseDTO> pageData = getPageData(knowledgeBaseEntityIPage, KnowledgeBaseDTO.class);

        // 为每个知识库获取文档数量
        if (pageData != null && pageData.getList() != null) {
            for (KnowledgeBaseDTO knowledgeBase : pageData.getList()) {
                try {
                    Integer documentCount = getDocumentCountFromRAGFlow(knowledgeBase.getDatasetId(),
                            knowledgeBase.getRagModelId());
                    knowledgeBase.setDocumentCount(documentCount);
                } catch (Exception e) {
                    log.warn("获取知识库 {} 的文档数量失败: {}", knowledgeBase.getDatasetId(), e.getMessage());
                    knowledgeBase.setDocumentCount(0); // 设置默认值
                }
            }
        }

        return pageData;
    }

    @Override
    public KnowledgeBaseDTO getById(String id) {
        if (StringUtils.isBlank(id)) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        KnowledgeBaseEntity entity = knowledgeBaseDao.selectById(id);
        if (entity == null) {
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }

        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    public KnowledgeBaseDTO save(KnowledgeBaseDTO knowledgeBaseDTO) {
        if (knowledgeBaseDTO == null) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }

        // 检查是否存在同名知识库
        checkDuplicateKnowledgeBaseName(knowledgeBaseDTO, null);

        String datasetId = null;
        // 调用RAGFlow API创建数据集
        try {
            Map<String, Object> ragConfig = getValidatedRAGConfig(knowledgeBaseDTO.getRagModelId());
            datasetId = createDatasetInRAGFlow(
                    knowledgeBaseDTO.getName(),
                    knowledgeBaseDTO.getDescription(),
                    ragConfig);
        } catch (Exception e) {
            // 如果RAG API调用失败，直接抛出异常，无需回滚（因为还没有插入本地数据库）
            throw e;
        }

        // 验证数据集ID是否已存在
        KnowledgeBaseEntity existingEntity = knowledgeBaseDao.selectOne(
                new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", datasetId));
        if (existingEntity != null) {
            // 如果datasetId已存在，删除RAGFlow中的数据集并抛出异常
            try {
                Map<String, Object> ragConfig = getValidatedRAGConfig(knowledgeBaseDTO.getRagModelId());
                deleteDatasetInRAGFlow(datasetId, ragConfig);
            } catch (Exception deleteException) {
                log.warn("删除重复datasetId的RAGFlow数据集失败: {}", deleteException.getMessage());
            }
            throw new RenException(ErrorCode.DB_RECORD_EXISTS);
        }

        // 创建本地实体并保存
        KnowledgeBaseEntity entity = ConvertUtils.sourceToTarget(knowledgeBaseDTO, KnowledgeBaseEntity.class);
        entity.setDatasetId(datasetId);
        knowledgeBaseDao.insert(entity);

        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    public KnowledgeBaseDTO update(KnowledgeBaseDTO knowledgeBaseDTO) {
        if (knowledgeBaseDTO == null || StringUtils.isBlank(knowledgeBaseDTO.getId())) {
            throw new RenException(ErrorCode.IDENTIFIER_NOT_NULL);
        }

        // 检查记录是否存在
        KnowledgeBaseEntity existingEntity = knowledgeBaseDao.selectById(knowledgeBaseDTO.getId());
        if (existingEntity == null) {
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }

        // 检查是否存在同名知识库（排除当前记录）
        checkDuplicateKnowledgeBaseName(knowledgeBaseDTO, knowledgeBaseDTO.getId());

        // 验证数据集ID是否与其他记录冲突
        if (StringUtils.isNotBlank(knowledgeBaseDTO.getDatasetId())) {
            KnowledgeBaseEntity conflictEntity = knowledgeBaseDao.selectOne(
                    new QueryWrapper<KnowledgeBaseEntity>()
                            .eq("dataset_id", knowledgeBaseDTO.getDatasetId())
                            .ne("id", knowledgeBaseDTO.getId()));
            if (conflictEntity != null) {
                throw new RenException(ErrorCode.DB_RECORD_EXISTS);
            }
        }

        KnowledgeBaseEntity entity = ConvertUtils.sourceToTarget(knowledgeBaseDTO, KnowledgeBaseEntity.class);
        knowledgeBaseDao.updateById(entity);

        // 删除缓存
        if (entity.getDatasetId() != null) {
            redisUtils.delete(RedisKeys.getKnowledgeBaseCacheKey(entity.getId()));
        }

        // 调用RAGFlow API更新数据集
        if (StringUtils.isNotBlank(knowledgeBaseDTO.getDatasetId())) {
            try {
                Map<String, Object> ragConfig = getValidatedRAGConfig(knowledgeBaseDTO.getRagModelId());
                updateDatasetInRAGFlow(
                        knowledgeBaseDTO.getDatasetId(),
                        knowledgeBaseDTO.getName(),
                        knowledgeBaseDTO.getDescription(),
                        ragConfig);
            } catch (Exception e) {
                // 如果RAG API调用失败，回滚本地数据库操作
                knowledgeBaseDao.updateById(existingEntity);
                throw e;
            }
        }

        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    public KnowledgeBaseDTO getByDatasetId(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }

        KnowledgeBaseEntity entity = knowledgeBaseDao.selectOne(
                new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", datasetId));

        if (entity == null) {
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }

        return ConvertUtils.sourceToTarget(entity, KnowledgeBaseDTO.class);
    }

    @Override
    public void deleteByDatasetId(String datasetId) {
        if (StringUtils.isBlank(datasetId)) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }

        log.info("=== 开始通过datasetId删除操作 ===");
        log.info("删除datasetId: {}", datasetId);

        KnowledgeBaseEntity entity = knowledgeBaseDao.selectOne(
                new QueryWrapper<KnowledgeBaseEntity>().eq("dataset_id", datasetId));

        if (entity == null) {
            log.warn("记录不存在，datasetId: {}", datasetId);
            throw new RenException(ErrorCode.Knowledge_Base_RECORD_NOT_EXISTS);
        }
        redisUtils.delete(RedisKeys.getKnowledgeBaseCacheKey(entity.getId()));

        log.info("找到记录: ID={}, datasetId={}, ragModelId={}",
                entity.getId(), entity.getDatasetId(), entity.getRagModelId());

        // 先删除本地数据库记录
        int deleteCount = knowledgeBaseDao.deleteById(entity.getId());
        log.info("本地数据库删除结果: {}", deleteCount > 0 ? "成功" : "失败");

        // 调用RAGFlow API删除数据集
        if (StringUtils.isNotBlank(entity.getDatasetId()) && StringUtils.isNotBlank(entity.getRagModelId())) {
            try {
                log.info("开始调用RAGFlow API删除数据集");
                Map<String, Object> ragConfig = getValidatedRAGConfig(entity.getRagModelId());
                deleteDatasetInRAGFlow(entity.getDatasetId(), ragConfig);
                log.info("RAGFlow API删除调用完成");
            } catch (Exception e) {
                log.warn("删除RAGFlow数据集失败: {}", e.getMessage());
            }
        } else {
            log.warn("datasetId或ragModelId为空，跳过RAGFlow删除");
        }

        log.info("=== 通过datasetId删除操作结束 ===");
    }

    @Override
    public Map<String, Object> getRAGConfig(String ragModelId) {
        if (StringUtils.isBlank(ragModelId)) {
            throw new RenException(ErrorCode.PARAMS_GET_ERROR);
        }

        // 从缓存获取模型配置
        ModelConfigEntity modelConfig = modelConfigService.getModelByIdFromCache(ragModelId);
        if (modelConfig == null || modelConfig.getConfigJson() == null) {
            throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND);
        }

        // 验证是否为RAG类型配置
        if (!Constant.RAG_CONFIG_TYPE.equals(modelConfig.getModelType().toUpperCase())) {
            throw new RenException(ErrorCode.RAG_CONFIG_TYPE_ERROR);
        }

        Map<String, Object> config = modelConfig.getConfigJson();

        // 验证必要的配置参数
        validateRagConfig(config);

        // 返回配置信息
        return config;
    }

    @Override
    public Map<String, Object> getDefaultRAGConfig() {
        // 获取默认RAG模型配置
        QueryWrapper<ModelConfigEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("model_type", Constant.RAG_CONFIG_TYPE)
                .eq("is_default", 1)
                .eq("is_enabled", 1);

        List<ModelConfigEntity> modelConfigs = modelConfigDao.selectList(queryWrapper);
        if (modelConfigs == null || modelConfigs.isEmpty()) {
            throw new RenException(ErrorCode.RAG_DEFAULT_CONFIG_NOT_FOUND);
        }

        ModelConfigEntity defaultConfig = modelConfigs.get(0);
        if (defaultConfig.getConfigJson() == null) {
            throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND);
        }

        Map<String, Object> config = defaultConfig.getConfigJson();

        // 验证必要的配置参数
        validateRagConfig(config);

        return config;
    }

    @Override
    public List<Map<String, Object>> getRAGModels() {
        // 查询RAG类型的模型配置
        QueryWrapper<ModelConfigEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("model_type", Constant.RAG_CONFIG_TYPE)
                .eq("is_enabled", 1)
                .orderByDesc("is_default")
                .orderByDesc("create_date");

        List<ModelConfigEntity> modelConfigs = modelConfigDao.selectList(queryWrapper);

        List<Map<String, Object>> modelList = new ArrayList<>();
        for (ModelConfigEntity modelConfig : modelConfigs) {
            Map<String, Object> modelInfo = new HashMap<>();
            modelInfo.put("id", modelConfig.getId());
            modelInfo.put("modelName", modelConfig.getModelName());
            modelList.add(modelInfo);
        }
        return modelList;
    }

    /**
     * 验证RAG配置中是否包含必要的参数
     */
    private void validateRagConfig(Map<String, Object> config) {
        if (config == null) {
            throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND);
        }

        // 从配置中提取必要的参数
        String baseUrl = (String) config.get("base_url");
        String apiKey = (String) config.get("api_key");

        // 验证base_url是否存在且非空
        if (StringUtils.isBlank(baseUrl)) {
            throw new RenException(ErrorCode.RAG_API_ERROR);
        }
    }

    /**
     * 调用RAGFlow API创建数据集
     */
    private String createDatasetInRAGFlow(String name, String description, Map<String, Object> ragConfig) {
        String datasetId = null;
        String baseUrl = (String) ragConfig.get("base_url");
        String apiKey = (String) ragConfig.get("api_key");

        log.info("开始调用RAGFlow API创建数据集, name: {}", name);
        log.debug("RAGFlow配置 - baseUrl: {}, apiKey: {}", baseUrl, StringUtils.isBlank(apiKey) ? "未配置" : "已配置");

        // 构建请求URL
        String url = baseUrl + "/api/v1/datasets";
        log.debug("请求URL: {}", url);

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        String username = SecurityUser.getUser().getUsername();
        requestBody.put("name", username + "_" + name);
        if (StringUtils.isNotBlank(description)) {
            requestBody.put("description", description);
        }
        log.debug("请求体: {}", requestBody);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        // 发送POST请求
        log.info("发送POST请求到RAGFlow API...");
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

        log.info("RAGFlow API响应状态码: {}", response.getStatusCode());
        log.debug("RAGFlow API响应内容: {}", response.getBody());

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("RAGFlow API调用失败，状态码: {}, 响应内容: {}", response.getStatusCode(), response.getBody());
            throw new RenException(ErrorCode.RAG_API_ERROR, response.getStatusCode().toString());
        }

        // 解析响应体，提取datasetId
        String responseBody = response.getBody();
        if (StringUtils.isNotBlank(responseBody)) {
            try {
                // 解析RAGFlow API响应，支持多种可能的字段名
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);

                log.debug("RAGFlow API响应解析结果: {}", responseMap);

                // 首先检查响应码
                Integer code = (Integer) responseMap.get("code");
                String message = (String) responseMap.get("message");
                if (code != null && code == 0) {
                    // 响应码为0表示成功，从data字段中获取datasetId
                    Object dataObj = responseMap.get("data");
                    if (dataObj instanceof Map) {
                        Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                        datasetId = (String) dataMap.get("id");

                        if (StringUtils.isBlank(datasetId)) {
                            // 如果id字段为空，尝试其他可能的字段名
                            datasetId = (String) dataMap.get("dataset_id");
                            datasetId = (String) dataMap.get("datasetId");
                        }
                    }
                } else {
                    // 如果响应码不为0，说明API调用失败
                    log.error("RAGFlow API调用失败，响应码: {}, 响应内容: {}", code, message);
                    throw new RenException(ErrorCode.RAG_API_ERROR,
                            "RAGFlow API调用失败，响应码: " + code + ", 消息: " + message);
                }

                log.info("从RAGFlow API响应中解析出datasetId: {}", datasetId);
                log.debug("完整响应内容: {}", responseBody);
            } catch (Exception e) {
                log.error("解析RAGFlow API响应失败: {}", e.getMessage(), e);
                throw new RenException(ErrorCode.RAG_API_ERROR, "解析响应失败: " + e.getMessage());
            }
        }

        if (StringUtils.isBlank(datasetId)) {
            log.error("无法从RAGFlow API响应中获取datasetId，响应内容: {}", responseBody);
            throw new RenException(ErrorCode.RAG_API_ERROR, "RAGFlow API响应中未包含datasetId");
        }
        log.info("RAGFlow数据集创建成功，datasetId: {}", datasetId);

        return datasetId;
    }

    /**
     * 调用RAGFlow API更新数据集
     */
    private void updateDatasetInRAGFlow(String datasetId, String name, String description,
            Map<String, Object> ragConfig) {
        String baseUrl = (String) ragConfig.get("base_url");
        String apiKey = (String) ragConfig.get("api_key");

        log.info("开始调用RAGFlow API更新数据集，datasetId: {}, name: {}", datasetId, name);
        log.debug("RAGFlow配置 - baseUrl: {}, apiKey: {}", baseUrl, StringUtils.isBlank(apiKey) ? "未配置" : "已配置");

        // 构建请求URL
        String url = baseUrl + "/api/v1/datasets/" + datasetId;
        log.debug("请求URL: {}", url);
        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("dataset_id", datasetId);
        String username = SecurityUser.getUser().getUsername();
        requestBody.put("name", username + "_" + name);
        if (StringUtils.isNotBlank(description)) {
            requestBody.put("description", description);
        }
        log.debug("请求体: {}", requestBody);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        // 发送PUT请求
        log.info("发送PUT请求到RAGFlow API...");
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PUT, requestEntity, String.class);

        log.info("RAGFlow API响应状态码: {}", response.getStatusCode());
        log.debug("RAGFlow API响应内容: {}", response.getBody());

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("RAGFlow API调用失败，状态码: {}, 响应内容: {}", response.getStatusCode(), response.getBody());
            throw new RenException(ErrorCode.RAG_API_ERROR);
        }

        log.info("RAGFlow数据集更新成功，datasetId: {}", datasetId);

    }

    /**
     * 调用RAGFlow API删除数据集
     */
    private void deleteDatasetInRAGFlow(String datasetId, Map<String, Object> ragConfig) {
        String baseUrl = (String) ragConfig.get("base_url");
        String apiKey = (String) ragConfig.get("api_key");

        log.info("开始调用RAGFlow API删除数据集，datasetId: {}", datasetId);
        log.debug("RAGFlow配置 - baseUrl: {}, apiKey: {}", baseUrl, StringUtils.isBlank(apiKey) ? "未配置" : "已配置");

        // 构建请求URL
        String url = baseUrl + "/api/v1/datasets";
        log.debug("请求URL: {}", url);

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("ids", List.of(datasetId));
        log.debug("请求体: {}", requestBody);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        // 发送DELETE请求
        log.info("发送DELETE请求到RAGFlow API...");
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, requestEntity,
                String.class);

        log.info("RAGFlow API响应状态码: {}", response.getStatusCode());
        log.debug("RAGFlow API响应内容: {}", response.getBody());

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("RAGFlow API调用失败，状态码: {}, 响应内容: {}", response.getStatusCode(), response.getBody());
            throw new RenException(ErrorCode.RAG_API_ERROR);
        }
        log.info("RAGFlow数据集删除成功，datasetId: {}", datasetId);

    }

    /**
     * 获取RAG配置并验证
     */
    private Map<String, Object> getValidatedRAGConfig(String ragModelId) {
        Map<String, Object> ragConfig;
        if (StringUtils.isNotBlank(ragModelId)) {
            ragConfig = getRAGConfig(ragModelId);
        } else {
            ragConfig = getDefaultRAGConfig();
        }

        // 验证baseUrl和apiKey
        validateRAGConfigParameters(ragConfig);

        return ragConfig;
    }

    /**
     * 验证RAG配置参数
     */
    private void validateRAGConfigParameters(Map<String, Object> ragConfig) {
        if (ragConfig == null) {
            throw new RenException(ErrorCode.RAG_CONFIG_NOT_FOUND, "RAG配置为空，请检查配置");
        }

        // 从配置中提取必要的参数
        String baseUrl = (String) ragConfig.get("base_url");
        String apiKey = (String) ragConfig.get("api_key");

        if (StringUtils.isBlank(baseUrl)) {
            throw new RenException(ErrorCode.RAG_API_ERROR, "RAG配置中base_url为空，请完善配置");
        }

        if (StringUtils.isBlank(apiKey)) {
            throw new RenException(ErrorCode.RAG_API_ERROR, "RAG配置中api_key为空，请完善配置");
        }

        if (apiKey.contains("你")) {
            throw new RenException(ErrorCode.RAG_API_ERROR, "RAG配置中api_key包含占位符'你'，请替换为实际的API密钥");
        }

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new RenException(ErrorCode.RAG_API_ERROR, "RAG配置中base_url格式不正确，必须以http://或https://开头");
        }
    }

    /**
     * 检查是否存在同名知识库
     * 
     * @param knowledgeBaseDTO 知识库DTO
     * @param excludeId        排除的ID（更新时使用）
     */
    private void checkDuplicateKnowledgeBaseName(KnowledgeBaseDTO knowledgeBaseDTO, String excludeId) {
        if (StringUtils.isNotBlank(knowledgeBaseDTO.getName())) {
            Long currentUserId = SecurityUser.getUserId();
            QueryWrapper<KnowledgeBaseEntity> queryWrapper = new QueryWrapper<KnowledgeBaseEntity>()
                    .eq("name", knowledgeBaseDTO.getName())
                    .eq("creator", currentUserId);

            // 如果提供了排除ID，则排除该记录
            if (StringUtils.isNotBlank(excludeId)) {
                queryWrapper.ne("id", excludeId);
            }

            long count = knowledgeBaseDao.selectCount(queryWrapper);
            if (count > 0) {
                throw new RenException(ErrorCode.KNOWLEDGE_BASE_NAME_EXISTS,
                        MessageUtils.getMessage(ErrorCode.KNOWLEDGE_BASE_NAME_EXISTS));
            }
        }
    }

    /**
     * 从RAGFlow API获取知识库的文档数量
     */
    private Integer getDocumentCountFromRAGFlow(String datasetId, String ragModelId) {
        if (StringUtils.isBlank(datasetId) || StringUtils.isBlank(ragModelId)) {
            log.warn("datasetId或ragModelId为空，无法获取文档数量");
            return 0;
        }

        log.info("开始获取知识库 {} 的文档数量", datasetId);

        // 获取RAG配置
        Map<String, Object> ragConfig = getValidatedRAGConfig(ragModelId);
        String baseUrl = (String) ragConfig.get("base_url");
        String apiKey = (String) ragConfig.get("api_key");

        // 构建请求URL - 调用RAGFlow API获取文档列表，但不返回文档详情，只获取总数
        String url = baseUrl + "/api/v1/datasets/" + datasetId + "/documents?page=1&size=1";
        log.debug("请求URL: {}", url);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<String> requestEntity = new HttpEntity<>(headers);

        // 发送GET请求
        log.info("发送GET请求到RAGFlow API获取文档数量...");
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);

        log.info("RAGFlow API响应状态码: {}", response.getStatusCode());

        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error("RAGFlow API调用失败，状态码: {}, 响应内容: {}", response.getStatusCode(), response.getBody());
            return 0;
        }

        String responseBody = response.getBody();
        log.debug("RAGFlow API响应内容: {}", responseBody);

        // 解析响应
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            Integer code = (Integer) responseMap.get("code");

            if (code != null && code == 0) {
                Object dataObj = responseMap.get("data");
                if (dataObj instanceof Map) {
                    Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                    Object totalObj = dataMap.get("total");
                    if (totalObj instanceof Integer) {
                        Integer documentCount = (Integer) totalObj;
                        log.info("获取知识库 {} 的文档数量成功: {}", datasetId, documentCount);
                        return documentCount;
                    } else if (totalObj instanceof Long) {
                        Long documentCount = (Long) totalObj;
                        log.info("获取知识库 {} 的文档数量成功: {}", datasetId, documentCount);
                        return documentCount.intValue();
                    }
                }
            } else {
                log.error("RAGFlow API调用失败，响应码: {}, 响应内容: {}", code, responseBody);
            }
        } catch (Exception e) {
            log.error("解析RAGFlow API响应失败: {}", e.getMessage(), e);
        }
        return 0;
    }

}