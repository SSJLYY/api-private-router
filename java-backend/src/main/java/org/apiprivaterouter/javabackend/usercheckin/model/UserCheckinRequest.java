package org.apiprivaterouter.javabackend.usercheckin.model;

public record UserCheckinRequest(
        Double stake_amount,
        String timezone
) {
}
