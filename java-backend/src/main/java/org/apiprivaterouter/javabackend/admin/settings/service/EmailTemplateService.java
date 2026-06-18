package org.apiprivaterouter.javabackend.admin.settings.service;

import org.apiprivaterouter.javabackend.admin.settings.model.EmailTemplateResponse;
import org.apiprivaterouter.javabackend.admin.settings.model.PreviewEmailTemplateRequest;
import org.apiprivaterouter.javabackend.admin.settings.model.UpdateEmailTemplateRequest;
import org.apiprivaterouter.javabackend.common.api.HttpStatusException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmailTemplateService {

    public List<EmailTemplateResponse> listTemplates() {
        return List.of();
    }

    public EmailTemplateResponse getTemplate(String event, String locale) {
        throw new HttpStatusException(404, "email template not found");
    }

    public EmailTemplateResponse updateTemplate(String event, String locale, UpdateEmailTemplateRequest request) {
        throw new HttpStatusException(501, "not implemented");
    }

    public EmailTemplateResponse previewTemplate(PreviewEmailTemplateRequest request) {
        throw new HttpStatusException(501, "not implemented");
    }

    public EmailTemplateResponse restoreOfficialTemplate(String event, String locale) {
        throw new HttpStatusException(501, "not implemented");
    }
}
