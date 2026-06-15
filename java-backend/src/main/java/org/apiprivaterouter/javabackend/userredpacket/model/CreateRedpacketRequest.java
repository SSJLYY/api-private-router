package org.apiprivaterouter.javabackend.userredpacket.model;

public record CreateRedpacketRequest(
    String redpacket_type,
    Double total_amount,
    Integer count,
    String memo,
    String expire_at
) {}
