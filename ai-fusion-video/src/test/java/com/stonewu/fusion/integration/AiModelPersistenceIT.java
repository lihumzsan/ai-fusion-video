package com.stonewu.fusion.integration;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.fusion.entity.ai.AiModel;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.mapper.ai.AiModelMapper;
import com.stonewu.fusion.mapper.ai.ApiConfigMapper;
import com.stonewu.fusion.service.ai.AiModelService;
import com.stonewu.fusion.service.ai.ApiConfigService;
import com.stonewu.fusion.service.ai.ChatModelFactory;
import com.stonewu.fusion.service.ai.ModelPresetService;
import com.stonewu.fusion.service.ai.catalog.ProviderCatalogService;
import com.stonewu.fusion.service.ai.comfyui.ComfyUiWorkflowService;
import com.stonewu.fusion.service.ai.model.AiModelMetadataResolver;
import com.stonewu.fusion.service.ai.provider.AiProviderContextFactory;
import com.stonewu.fusion.service.generation.GenerationModelCapabilityService;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 MySQL 验证模型编辑保留省略字段并持久化显式清空。 */
@Testcontainers
class AiModelPersistenceIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("ai_model_persistence").withUsername("afv").withPassword("afv-test");

    @Configuration
    @EnableTransactionManagement
    static class Transactions {
    }

    @Test
    void rebindingExistingModelToCodexClearsPreviousProviderSettings() throws Exception {
        try (var source = new HikariDataSource(); var context = new AnnotationConfigApplicationContext()) {
            source.setJdbcUrl(MYSQL.getJdbcUrl());
            source.setUsername(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            Flyway.configure().dataSource(source).load().migrate();
            context.getEnvironment().setActiveProfiles("local");
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
            context.registerBean(ChatModelFactory.class, () -> mock(ChatModelFactory.class));
            context.registerBean(ComfyUiWorkflowService.class, () -> mock(ComfyUiWorkflowService.class));
            context.registerBean(GenerationModelCapabilityService.class, () -> mock(GenerationModelCapabilityService.class));
            var presets = mock(ModelPresetService.class);
            when(presets.getPreset("previous-preset")).thenReturn(JSONUtil.createObj().set("modelType", 2));
            context.registerBean(ModelPresetService.class, () -> presets);
            context.register(Transactions.class, AiModelService.class, ApiConfigService.class,
                    ProviderCatalogService.class, AiModelMetadataResolver.class, AiProviderContextFactory.class);
            MybatisTestSupport.register(context, source, ApiConfigMapper.class, AiModelMapper.class);
            context.refresh();

            var configs = context.getBean(ApiConfigService.class);
            var models = context.getBean(AiModelService.class);
            var mapper = context.getBean(AiModelMapper.class);
            long comfyUiId = configs.createApiConfig(ApiConfig.builder().name("ComfyUI").platform("comfyui")
                    .apiUrl("http://127.0.0.1:8188").build());
            long codexId = configs.createApiConfig(ApiConfig.builder().name("Codex").platform("codex")
                    .textProtocol("codex").build());
            long id = models.createAiModel(AiModel.builder().name("Previous model").code("previous-model")
                    .modelType(2).apiConfigId(comfyUiId).modelProtocol("comfyui").capabilityPresetCode("previous-preset")
                    .comfyuiWorkflowId(30L).build());

            models.updateAiModel(id, "Renamed model", null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);

            assertThat(mapper.selectById(id)).satisfies(saved -> {
                assertThat(saved.getName()).isEqualTo("Renamed model");
                assertThat(saved.getModelProtocol()).isEqualTo("comfyui");
                assertThat(saved.getCapabilityPresetCode()).isEqualTo("previous-preset");
                assertThat(saved.getComfyuiWorkflowId()).isEqualTo(30L);
            });

            models.updateAiModel(id, null, "gpt-6.1-sol", "", "", 1, null, null, null, null,
                    null, null, codexId, null, null, null, null, null, null, 0L);

            var rebound = mapper.selectById(id);
            assertThat(rebound.getApiConfigId()).isEqualTo(codexId);
            assertThat(rebound.getModelProtocol()).isNull();
            assertThat(rebound.getCapabilityPresetCode()).isNull();
            assertThat(rebound.getComfyuiWorkflowId()).isNull();
            var provider = context.getBean(AiProviderContextFactory.class).createForModel(rebound);
            assertThat(provider.getPlatform()).isEqualTo("codex");
            assertThat(provider.getModelName()).isEqualTo("gpt-6.1-sol");
        }
    }
}
