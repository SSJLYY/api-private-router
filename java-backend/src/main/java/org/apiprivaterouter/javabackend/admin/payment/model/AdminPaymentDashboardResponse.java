package org.apiprivaterouter.javabackend.admin.payment.model;

import java.util.List;

public record AdminPaymentDashboardResponse(
        double today_amount,
        double total_amount,
        long today_count,
        long total_count,
        double avg_amount,
        List<DailySeriesPoint> daily_series,
        List<PaymentMethodStat> payment_methods,
        List<TopUserStat> top_users
) {
    public record DailySeriesPoint(String date, double amount, long count) {
    }

    public record PaymentMethodStat(String type, double amount, long count) {
    }

    public record TopUserStat(long user_id, String email, double amount) {
    }
}
