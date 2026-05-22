package org.apiprivaterouter.javabackend.admin.account.model;

public record AccountTestEvent(
        String type,
        String text,
        String model,
        Boolean success,
        String error,
        String image_url,
        String mime_type
) {
    public static AccountTestEvent testStart(String model) {
        return new AccountTestEvent("test_start", null, model, null, null, null, null);
    }

    public static AccountTestEvent content(String text) {
        return new AccountTestEvent("content", text, null, null, null, null, null);
    }

    public static AccountTestEvent image(String imageUrl, String mimeType) {
        return new AccountTestEvent("image", null, null, null, null, imageUrl, mimeType);
    }

    public static AccountTestEvent complete(boolean success, String error) {
        return new AccountTestEvent("test_complete", null, null, success, error, null, null);
    }

    public static AccountTestEvent error(String error) {
        return new AccountTestEvent("error", null, null, null, error, null, null);
    }
}
