package org.apiprivaterouter.javabackend.userfund.model;

import java.math.BigDecimal;

public record HouseReportResponse(
        BigDecimal total_income,
        BigDecimal total_expense,
        BigDecimal net_profit,
        long total_income_count,
        long total_expense_count,
        BigDecimal game_loss_income,
        long game_loss_count,
        BigDecimal bet_loss_income,
        long bet_loss_count,
        BigDecimal other_income_amount,
        long other_income_count,
        BigDecimal game_win_payout,
        long game_win_payout_count,
        BigDecimal bet_win_payout,
        long bet_win_payout_count,
        BigDecimal other_expense_amount,
        long other_expense_count
) {}
