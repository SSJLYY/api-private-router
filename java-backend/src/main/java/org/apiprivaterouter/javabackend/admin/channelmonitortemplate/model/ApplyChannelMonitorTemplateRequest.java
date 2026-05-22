package org.apiprivaterouter.javabackend.admin.channelmonitortemplate.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ApplyChannelMonitorTemplateRequest(
        @NotEmpty List<Long> monitor_ids
) {
}
