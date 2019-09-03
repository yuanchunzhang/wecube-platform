package com.webank.wecube.core.controller;

import static com.webank.wecube.core.domain.JsonResponse.okay;
import static com.webank.wecube.core.domain.JsonResponse.okayWithData;
import static com.webank.wecube.core.domain.MenuItem.MENU_ADMIN_BASE_DATA_MANAGEMENT;

import java.util.List;

import javax.annotation.security.RolesAllowed;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.webank.wecube.core.domain.JsonResponse;
import com.webank.wecube.core.domain.SystemVariable;
import com.webank.wecube.core.service.SystemVariableService;

@RestController
@RolesAllowed({MENU_ADMIN_BASE_DATA_MANAGEMENT})
public class SystemVariableController {

    @Autowired
    private SystemVariableService systemVariableService;

    @GetMapping("/system-variables/supported-scope-types")
    @ResponseBody
    public JsonResponse getSupportedScopeTypes() {
        List<String> properties = systemVariableService.getSupportedScopeTypes();
        return okayWithData(properties);
    }
    
    @GetMapping("/system-variables/global")
    @ResponseBody
    public JsonResponse getGlobalSystemVariables(@RequestParam(value = "status", required = false) String status) {
        List<SystemVariable> properties = systemVariableService.getGlobalSystemVariables(status);
        return okayWithData(properties);
    }
    
    @GetMapping("/system-variables")
    @ResponseBody
    public JsonResponse getSystemVariables(@RequestParam(value = "scope-type") String scopeType
            , @RequestParam(value = "scope-value") String scopeValue
            , @RequestParam(value = "status", required = false) String status) {
        List<SystemVariable> properties = systemVariableService.getSystemVariables(scopeType, scopeValue, status);
        return okayWithData(properties);
    }
    
    @GetMapping("/system-variables/all")
    @ResponseBody
    public JsonResponse getAllSystemVariables(@RequestParam(value = "status", required = false) String status) {
        List<SystemVariable> properties = systemVariableService.getAllSystemVariables(status);
        return okayWithData(properties);
    }

    @GetMapping("/system-variables/{var-id}")
    @ResponseBody
    public JsonResponse getSystemVariableById(@PathVariable(value = "var-id") int varId) {
        return okayWithData(systemVariableService.getSystemVariableById(varId));
    }
    
    @PostMapping("/system-variables/save")
    @ResponseBody
    public JsonResponse saveSystemVariables(@RequestBody List<SystemVariable> variables) {
        return okayWithData(systemVariableService.saveSystemVariables(variables));
    }
    

    @PostMapping("/system-variables/delete")
    @ResponseBody
    public JsonResponse deleteSystemVariables(@RequestBody List<Integer> variableIds) {
        systemVariableService.deleteSystemVariables(variableIds);
        return okay();
    }
}



