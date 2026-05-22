package org.apiprivaterouter.javabackend.admin.account.service;

public class MixedChannelConflictException extends RuntimeException {

    private final long groupId;
    private final String groupName;
    private final String currentPlatform;
    private final String otherPlatform;

    public MixedChannelConflictException(long groupId, String groupName, String currentPlatform, String otherPlatform) {
        super("mixed_channel_warning: Group '%s' contains both %s and %s accounts. Using mixed channels in the same context may cause thinking block signature validation issues, which will fallback to non-thinking mode for historical messages."
                .formatted(groupName, currentPlatform, otherPlatform));
        this.groupId = groupId;
        this.groupName = groupName;
        this.currentPlatform = currentPlatform;
        this.otherPlatform = otherPlatform;
    }

    public long getGroupId() {
        return groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getCurrentPlatform() {
        return currentPlatform;
    }

    public String getOtherPlatform() {
        return otherPlatform;
    }
}
