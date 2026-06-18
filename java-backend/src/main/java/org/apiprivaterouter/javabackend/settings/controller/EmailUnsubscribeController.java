package org.apiprivaterouter.javabackend.settings.controller;

import org.apiprivaterouter.javabackend.settings.service.EmailUnsubscribeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class EmailUnsubscribeController {

    private final EmailUnsubscribeService emailUnsubscribeService;

    public EmailUnsubscribeController(EmailUnsubscribeService emailUnsubscribeService) {
        this.emailUnsubscribeService = emailUnsubscribeService;
    }

    @GetMapping(value = "/email-unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    public String unsubscribe(@RequestParam("token") String token) {
        try {
            EmailUnsubscribeService.UnsubscribeResult result = emailUnsubscribeService.unsubscribe(token);
            if (result.done()) {
                return buildSuccessHtml(result.email(), result.event());
            }
            return buildAlreadyHtml(result.email(), result.event());
        } catch (Exception ex) {
            return buildErrorHtml(ex.getMessage());
        }
    }

    private String buildSuccessHtml(String email, String event) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Unsubscribed</title>"
                + "<style>body{font-family:system-ui,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f5f5f5}"
                + ".card{background:#fff;border-radius:12px;padding:40px;max-width:480px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1)}"
                + "h1{color:#16a34a;font-size:24px;margin:0 0 12px}p{color:#666;margin:0 0 8px;line-height:1.6}"
                + ".email{color:#333;font-weight:500}</style></head>"
                + "<body><div class=\"card\"><h1>Unsubscribed</h1>"
                + "<p>You have successfully unsubscribed <span class=\"email\">" + escapeHtml(email) + "</span></p>"
                + "<p>from <strong>" + escapeHtml(event) + "</strong> notifications.</p></div></body></html>";
    }

    private String buildAlreadyHtml(String email, String event) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Already Unsubscribed</title>"
                + "<style>body{font-family:system-ui,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f5f5f5}"
                + ".card{background:#fff;border-radius:12px;padding:40px;max-width:480px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1)}"
                + "h1{color:#d97706;font-size:24px;margin:0 0 12px}p{color:#666;margin:0 0 8px;line-height:1.6}"
                + ".email{color:#333;font-weight:500}</style></head>"
                + "<body><div class=\"card\"><h1>Already Unsubscribed</h1>"
                + "<p><span class=\"email\">" + escapeHtml(email) + "</span> was already unsubscribed</p>"
                + "<p>from <strong>" + escapeHtml(event) + "</strong> notifications.</p></div></body></html>";
    }

    private String buildErrorHtml(String message) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Error</title>"
                + "<style>body{font-family:system-ui,sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f5f5f5}"
                + ".card{background:#fff;border-radius:12px;padding:40px;max-width:480px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.1)}"
                + "h1{color:#dc2626;font-size:24px;margin:0 0 12px}p{color:#666;margin:0;line-height:1.6}</style></head>"
                + "<body><div class=\"card\"><h1>Unsubscribe Failed</h1>"
                + "<p>" + escapeHtml(message == null ? "Invalid or expired unsubscribe link." : message) + "</p></div></body></html>";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
