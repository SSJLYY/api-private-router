package org.apiprivaterouter.javabackend.admin.datamanagement.controller;

import org.junit.jupiter.api.Test;
import org.apiprivaterouter.javabackend.admin.datamanagement.service.AdminDataManagementService;
import org.apiprivaterouter.javabackend.common.api.ApiExceptionHandler;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDataManagementControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminDataManagementController(new AdminDataManagementService(), new CurrentUserContext()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void agentHealthKeepsDeprecatedSuccessPayload() throws Exception {
        mockMvc.perform(get("/api/v1/admin/data-management/agent/health")
                        .requestAttr("api-private-router.currentUser", admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(0)))
                .andExpect(jsonPath("$.data.enabled", equalTo(false)))
                .andExpect(jsonPath("$.data.reason", equalTo(AdminDataManagementService.DEPRECATED_REASON)))
                .andExpect(jsonPath("$.data.socket_path", equalTo(AdminDataManagementService.DEFAULT_SOCKET_PATH)));
    }

    @Test
    void nonHealthRouteReturnsStructuredDeprecatedError() throws Exception {
        mockMvc.perform(get("/api/v1/admin/data-management/config")
                        .requestAttr("api-private-router.currentUser", admin())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", equalTo(503)))
                .andExpect(jsonPath("$.reason", equalTo(AdminDataManagementService.DEPRECATED_REASON)))
                .andExpect(jsonPath("$.metadata.socket_path", equalTo(AdminDataManagementService.DEFAULT_SOCKET_PATH)));
    }

    private CurrentUser admin() {
        return new CurrentUser(1L, "admin@example.test", "admin", 0L);
    }
}
