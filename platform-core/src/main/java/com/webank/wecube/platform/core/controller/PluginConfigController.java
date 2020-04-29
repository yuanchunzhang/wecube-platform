package com.webank.wecube.platform.core.controller;

import com.webank.wecube.platform.core.commons.WecubeCoreException;
import com.webank.wecube.platform.core.domain.JsonResponse;
import com.webank.wecube.platform.core.dto.CommonResponseDto;
import com.webank.wecube.platform.core.dto.PluginConfigDto;
import com.webank.wecube.platform.core.dto.PluginInterfaceRoleRequestDto;
import com.webank.wecube.platform.core.dto.queryAvailableInterfacesForProcessDefinitionDto;
import com.webank.wecube.platform.core.service.plugin.PluginConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import static com.webank.wecube.platform.core.domain.JsonResponse.okayWithData;
import static com.webank.wecube.platform.core.domain.JsonResponse.okay;

@RestController
@RequestMapping("/v1")
public class PluginConfigController {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private PluginConfigService pluginConfigService;

    @PostMapping("/plugins")
    @ResponseBody
    public JsonResponse savePluginConfig(@RequestBody PluginConfigDto pluginConfigDto) {
        return okayWithData(pluginConfigService.savePluginConfig(pluginConfigDto));
    }

    @GetMapping("/plugins/interfaces/enabled")
    @ResponseBody
    public JsonResponse queryAllEnabledPluginConfigInterface() {
        return okayWithData(pluginConfigService.queryAllLatestEnabledPluginConfigInterface());
    }

    @GetMapping("/plugins/interfaces/package/{package-name}/entity/{entity-name}/enabled")
    @ResponseBody
    public JsonResponse queryAllEnabledPluginConfigInterfaceForEntityName(
            @PathVariable(value = "package-name") String packageName,
            @PathVariable(value = "entity-name") String entityName) {
        return okayWithData(pluginConfigService.queryAllEnabledPluginConfigInterfaceForEntity(packageName, entityName,
                null));
    }

    @PostMapping("/plugins/interfaces/package/{package-name}/entity/{entity-name}/enabled/query-by-target-entity-filter-rule")
    @ResponseBody
    public JsonResponse queryAllEnabledPluginConfigInterfaceByEntityNameAndFilterRule(
            @PathVariable(value = "package-name") String packageName,
            @PathVariable(value = "entity-name") String entityName,
            @RequestBody queryAvailableInterfacesForProcessDefinitionDto filterRule) {
        return okayWithData(pluginConfigService.queryAllEnabledPluginConfigInterfaceForEntity(packageName, entityName,
                filterRule));
    }

    @PostMapping("/plugins/enable/{plugin-config-id:.+}")
    @ResponseBody
    public JsonResponse enablePlugin(@PathVariable(value = "plugin-config-id") String pluginConfigId) {
        return okayWithData(pluginConfigService.enablePlugin(pluginConfigId));
    }

    @PostMapping("/plugins/disable/{plugin-config-id:.+}")
    @ResponseBody
    public JsonResponse disablePlugin(@PathVariable(value = "plugin-config-id") String pluginConfigId) {
        return okayWithData(pluginConfigService.disablePlugin(pluginConfigId));
    }

    @GetMapping("/plugins/interfaces/{plugin-config-id:.+}")
    @ResponseBody
    public JsonResponse queryPluginConfigInterfaceByConfigId(
            @PathVariable(value = "plugin-config-id") String pluginConfigId) {
        return okayWithData(pluginConfigService.queryPluginConfigInterfaceByConfigId(pluginConfigId));
    }

    @DeleteMapping("/plugins/configs/{plugin-config-id:.+}")
    @ResponseBody
    public JsonResponse deletePluginConfigByConfigId(@PathVariable(value = "plugin-config-id") String pluginConfigId) {
        try {
            pluginConfigService.deletePluginConfigById(pluginConfigId);
        } catch (Exception e) {
            log.error(e.getMessage());
            return JsonResponse.error(e.getMessage());
        }
        return okay();
    }

    @GetMapping("/plugins/interfaces/{plugin-interface-id}/roles")
    public CommonResponseDto getPluginInterfacePermission(
            @PathVariable("plugin-interface-id") String pluginInterfaceId) {
        return CommonResponseDto.okayWithData(pluginConfigService.getPluginInterfacePermissionById(pluginInterfaceId));
    }

    @PostMapping("/plugins/interfaces/{plugin-service-name}/roles")
    public CommonResponseDto grantPluginInterfacePermissionToRoles(@PathVariable("plugin-service-name") String pluginServiceName,
            @RequestBody PluginInterfaceRoleRequestDto pluginInterfaceRoleRequestDto) {
        try {
            pluginConfigService.grantPluginInterfacePermissionToRoles(pluginServiceName, pluginInterfaceRoleRequestDto);
        } catch (WecubeCoreException ex) {
            return CommonResponseDto.error(ex.getMessage());
        }
        return CommonResponseDto.okay();
    }

    @DeleteMapping("/plugins/interfaces/{plugin-service-name}/roles")
    public CommonResponseDto removePluginInterfacePermissionToRoles(@PathVariable("plugin-service-name") String pluginServiceName,
            @RequestBody PluginInterfaceRoleRequestDto pluginInterfaceRoleRequestDto) {
        try {
            pluginConfigService.removePluginInterfacePermissionToRoles(pluginServiceName,
                    pluginInterfaceRoleRequestDto);
        } catch (WecubeCoreException ex) {
            return CommonResponseDto.error(ex.getMessage());
        }
        return CommonResponseDto.okay();
    }
}
