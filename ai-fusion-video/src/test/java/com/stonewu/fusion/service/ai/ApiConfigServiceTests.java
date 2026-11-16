package com.stonewu.fusion.service.ai;

import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.ai.ApiConfig;
import com.stonewu.fusion.mapper.ai.AiModelMapper;
import com.stonewu.fusion.mapper.ai.ApiConfigMapper;
import com.stonewu.fusion.service.ai.catalog.ProviderCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiConfigServiceTests {

    private static final long CONFIG_ID = 5L;

    @Mock
    private ApiConfigMapper configs;
    @Mock
    private AiModelMapper models;
    @Mock
    private ObjectProvider<ChatModelFactory> chatModelFactory;
    @Mock
    private ProviderCatalogService providerCatalog;
    @InjectMocks
    private ApiConfigService service;

    /** 保留仍供默认模型使用的接入，并给出移除默认标记的操作提示。 */
    @Test
    void refusesToDisableOrDeleteAnApiUsedByAnEnabledDefault() {
        var config = ApiConfig.builder().id(CONFIG_ID).platform("openai_compatible").build();
        when(configs.selectById(CONFIG_ID)).thenReturn(config);
        when(models.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> disableConfig()).isInstanceOfSatisfying(BusinessException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo(409);
            assertThat(failure.getMessage()).contains("先切换或取消默认");
        });
        assertThatThrownBy(() -> service.deleteApiConfig(CONFIG_ID)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(409));
        assertThat(config.getStatus()).isEqualTo(1);
        verify(configs, never()).updateById(any(ApiConfig.class));
        verify(configs, never()).deleteById(CONFIG_ID);
    }

    /** 没有启用的默认模型使用此接入时，可以正常停用和删除。 */
    @Test
    void allowsDisablingAndDeletingAnApiWithoutAnEnabledDefault() {
        var config = ApiConfig.builder().id(CONFIG_ID).platform("openai_compatible").build();
        when(configs.selectById(CONFIG_ID)).thenReturn(config);

        disableConfig();
        assertThat(config.getStatus()).isZero();
        verify(configs).updateById(config);
        service.deleteApiConfig(CONFIG_ID);
        verify(configs).deleteById(CONFIG_ID);
    }

    private void disableConfig() {
        service.updateApiConfig(CONFIG_ID, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, 0, null);
    }
}
