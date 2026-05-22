package org.apiprivaterouter.javabackend.admin.channelmonitor.repository;

import org.apiprivaterouter.javabackend.channelmonitor.repository.ChannelMonitorCoreRepository;
import org.springframework.stereotype.Repository;

@Repository
public class ChannelMonitorRepository {

    private final ChannelMonitorCoreRepository coreRepository;

    public ChannelMonitorRepository(ChannelMonitorCoreRepository coreRepository) {
        this.coreRepository = coreRepository;
    }

    public ChannelMonitorCoreRepository core() {
        return coreRepository;
    }
}
