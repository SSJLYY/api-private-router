package org.apiprivaterouter.javabackend.admin.settings.controller;

import jakarta.validation.Valid;
import org.apiprivaterouter.javabackend.admin.settings.model.EmailTemplateResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.PreviewEmailTemplateRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.UpdateEmailTemplateRequest;
import org.apiprivaterouter.javabackend.admin.settings.service.EmailTemplateService;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/settings/email-templates")
public class EmailTemplateController {

    private final EmailTemplateService service;
    private final CurrentUserContext currentUserContext;

    public EmailTemplateController(EmailTemplateService service, CurrentUserContext currentUserContext) {
        this.service = service;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping
    public ApiResponse<List<EmailTemplateResponse>> listTemplates() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.listTemplates());
    }

    @GetMapping("/{event}/{locale}")
    public ApiResponse<EmailTemplateResponse> getTemplate(
            @PathVariable String event,
            @PathVariable String locale
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.getTemplate(event, locale));
    }

    @PutMapping("/{event}/{locale}")
    public ApiResponse<EmailTemplateResponse> updateTemplate(
            @PathVariable String event,
            @PathVariable String locale,
            @Valid @RequestBody UpdateEmailTemplateRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.updateTemplate(event, locale, request));
    }

    @PostMapping("/preview")
    public ApiResponse<EmailTemplateResponse> previewTemplate(
            @Valid @RequestBody PreviewEmailTemplateRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.previewTemplate(request));
    }

    @PostMapping("/{event}/{locale}/restore-official")
    public ApiResponse<EmailTemplateResponse> restoreOfficialTemplate(
            @PathVariable String event,
            @PathVariable String locale
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(service.restoreOfficialTemplate(event, locale));
    }
}
