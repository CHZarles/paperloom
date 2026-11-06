package io.github.chzarles.paperloom.controller;

import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.service.ModelProviderConfigService;
import io.github.chzarles.paperloom.utils.JwtUtils;
import io.github.chzarles.paperloom.utils.LogUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员模型 Provider 配置接口。API Key 只写入（加密落库），永不回传。
 */
@RestController
@RequestMapping("/api/v1/admin/model-providers")
public class ModelProviderAdminController {

    private final ModelProviderConfigService modelProviderConfigService;
    private final JwtUtils jwtUtils;

    public ModelProviderAdminController(ModelProviderConfigService modelProviderConfigService,
                                        JwtUtils jwtUtils) {
        this.modelProviderConfigService = modelProviderConfigService;
        this.jwtUtils = jwtUtils;
    }

    @GetMapping
    public ResponseEntity<?> listProviders(
            @RequestParam(defaultValue = "llm") String scope) {
        try {
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "获取模型 Provider 配置成功",
                    "data", modelProviderConfigService.getAdminOverview(scope)
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_LIST_MODEL_PROVIDERS", null, "获取模型 Provider 配置失败: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "获取模型 Provider 配置失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{scope}/{providerCode}")
    public ResponseEntity<?> saveProvider(
            @RequestHeader("Authorization") String token,
            @PathVariable String scope,
            @PathVariable String providerCode,
            @RequestBody ModelProviderConfigService.ProviderSaveCommand request) {
        String adminUsername = null;
        try {
            adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
            ModelProviderConfigService.AdminProviderOverview data =
                    modelProviderConfigService.saveProvider(scope, providerCode, request, adminUsername);
            LogUtils.logBusiness("ADMIN_SAVE_MODEL_PROVIDER", adminUsername,
                    "管理员保存模型 Provider：scope=%s, provider=%s, active=%s",
                    scope, providerCode, request.active());
            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "message", "模型 Provider 配置已保存",
                    "data", data
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("ADMIN_SAVE_MODEL_PROVIDER", adminUsername,
                    "保存模型 Provider 失败: %s", e, e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            LogUtils.logBusinessError("ADMIN_SAVE_MODEL_PROVIDER", adminUsername,
                    "保存模型 Provider 异常: %s", e, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "保存模型 Provider 配置失败: " + e.getMessage()));
        }
    }
}
