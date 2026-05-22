package org.apiprivaterouter.javabackend.channelmonitor.service;

import org.apiprivaterouter.javabackend.channelmonitor.model.ExtraModelStatus;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorDetailResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorExtraModelResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorListResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorModelDetailResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserChannelMonitorTimelinePointResponse;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorDetailRecord;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorModelDetail;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorTimelinePoint;
import org.apiprivaterouter.javabackend.channelmonitor.model.UserMonitorViewRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service("userChannelMonitorService")
public class ChannelMonitorService {

    private final ChannelMonitorCoreService coreService;

    public ChannelMonitorService(ChannelMonitorCoreService coreService) {
        this.coreService = coreService;
    }

    public UserChannelMonitorListResponse list() {
        List<UserChannelMonitorResponse> items = coreService.buildUserViews().stream()
                .map(this::toResponse)
                .toList();
        return new UserChannelMonitorListResponse(items);
    }

    public UserChannelMonitorDetailResponse status(long id) {
        return toDetailResponse(coreService.buildUserDetail(id));
    }

    private UserChannelMonitorResponse toResponse(UserMonitorViewRecord item) {
        return new UserChannelMonitorResponse(
                item.id(),
                item.name(),
                item.provider(),
                item.groupName(),
                item.primaryModel(),
                item.primaryStatus(),
                item.primaryLatencyMs(),
                item.primaryPingLatencyMs(),
                item.availability7d(),
                item.extraModels().stream().map(this::toExtraModel).toList(),
                item.timeline().stream().map(this::toTimelinePoint).toList()
        );
    }

    private UserChannelMonitorExtraModelResponse toExtraModel(ExtraModelStatus item) {
        return new UserChannelMonitorExtraModelResponse(item.model(), item.status(), item.latencyMs());
    }

    private UserChannelMonitorTimelinePointResponse toTimelinePoint(UserMonitorTimelinePoint point) {
        return new UserChannelMonitorTimelinePointResponse(
                point.status(),
                point.latencyMs(),
                point.pingLatencyMs(),
                iso(point.checkedAt())
        );
    }

    private UserChannelMonitorDetailResponse toDetailResponse(UserMonitorDetailRecord detail) {
        return new UserChannelMonitorDetailResponse(
                detail.id(),
                detail.name(),
                detail.provider(),
                detail.groupName(),
                detail.models().stream().map(this::toModelDetail).toList()
        );
    }

    private UserChannelMonitorModelDetailResponse toModelDetail(UserMonitorModelDetail detail) {
        return new UserChannelMonitorModelDetailResponse(
                detail.model(),
                detail.latestStatus(),
                detail.latestLatencyMs(),
                detail.availability7d(),
                detail.availability15d(),
                detail.availability30d(),
                detail.avgLatency7dMs()
        );
    }

    private String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
