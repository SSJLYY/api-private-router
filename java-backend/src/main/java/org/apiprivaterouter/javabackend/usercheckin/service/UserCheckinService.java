package org.apiprivaterouter.javabackend.usercheckin.service;

import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinCalendarDayResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinCalendarResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinHistoryItemResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinRequest;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinResultResponse;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinStatusResponse;
import org.apiprivaterouter.javabackend.usercheckin.repository.UserCheckinRepository;
import org.apiprivaterouter.javabackend.userfund.service.FundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class UserCheckinService {

    private static final Logger log = LoggerFactory.getLogger(UserCheckinService.class);
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("0.20");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("2.20");
    private static final BigDecimal MIN_STAKE = new BigDecimal("0.01");
    private static final int DEFAULT_HISTORY_PAGE = 1;
    private static final int DEFAULT_HISTORY_PAGE_SIZE = 20;
    private static final int MAX_HISTORY_PAGE_SIZE = 100;

    private final UserCheckinRepository repository;
    private final FundService fundService;

    public UserCheckinService(UserCheckinRepository repository, FundService fundService) {
        this.repository = repository;
        this.fundService = fundService;
    }

    public UserCheckinStatusResponse getStatus(CurrentUser currentUser, String timezone) {
        ZoneId zoneId = repository.normalizeTimezone(timezone);
        LocalDate checkinDate = LocalDate.now(zoneId);
        UserCheckinRepository.CheckinRecord record = repository.findByUserAndDate(currentUser.userId(), checkinDate).orElse(null);
        BigDecimal balance = repository.findUserById(currentUser.userId()).map(UserCheckinRepository.UserBalanceSnapshot::balance).orElse(null);
        return new UserCheckinStatusResponse(
                checkinDate.toString(),
                zoneId.getId(),
                record != null,
                record == null ? null : record.multiplier(),
                record == null ? null : record.rewardAmount(),
                record == null ? null : record.netChange(),
                balance,
                record == null || record.checkedInAt() == null ? null : record.checkedInAt().toInstant().toString()
        );
    }

    @Transactional
    public UserCheckinResultResponse checkin(CurrentUser currentUser, UserCheckinRequest request) {
        BigDecimal stakeAmount = normalizeStakeAmount(request == null ? null : request.stake_amount());
        ZoneId zoneId = repository.normalizeTimezone(request == null ? null : request.timezone());
        LocalDate checkinDate = LocalDate.now(zoneId);

        if (repository.findByUserAndDate(currentUser.userId(), checkinDate).isPresent()) {
            throw new IllegalArgumentException("already checked in today");
        }

        UserCheckinRepository.UserBalanceSnapshot user = repository.findUserByIdForUpdate(currentUser.userId())
                .orElseThrow(() -> new IllegalArgumentException("user not found"));

        if (user.balance().compareTo(stakeAmount) < 0) {
            throw new IllegalArgumentException("insufficient balance");
        }

        BigDecimal multiplier = randomMultiplier();
        BigDecimal rewardAmount = roundMoney(stakeAmount.multiply(multiplier));
        BigDecimal netChange = roundMoney(rewardAmount.subtract(stakeAmount));
        BigDecimal balanceBefore = roundMoney(user.balance());
        BigDecimal balanceAfter = roundMoney(balanceBefore.add(netChange));
        OffsetDateTime checkedInAt = OffsetDateTime.now();

        UserCheckinRepository.CheckinRecord record = repository.createCheckin(
                user.id(),
                checkinDate,
                zoneId.getId(),
                stakeAmount,
                rewardAmount,
                multiplier,
                netChange,
                balanceBefore,
                balanceAfter,
                checkedInAt
        );
        repository.updateUserBalance(user.id(), balanceAfter);
        syncFundCheckin(user.id(), stakeAmount, rewardAmount, netChange, checkinDate.toString());

        return toResult(record);
    }

    public UserCheckinCalendarResponse getMonthCalendar(CurrentUser currentUser, Integer year, Integer month, String timezone) {
        ZoneId zoneId = repository.normalizeTimezone(timezone);
        LocalDate currentDate = LocalDate.now(zoneId);
        int resolvedYear = year == null ? currentDate.getYear() : year;
        int resolvedMonth = month == null ? currentDate.getMonthValue() : month;
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.of(resolvedYear, resolvedMonth);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid year or month");
        }

        List<UserCheckinCalendarDayResponse> checkins = repository.listMonthCalendar(
                currentUser.userId(),
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth()
        );
        Map<String, UserCheckinCalendarDayResponse> byDate = new HashMap<>();
        for (UserCheckinCalendarDayResponse item : checkins) {
            byDate.put(item.checkin_date(), item);
        }

        List<UserCheckinCalendarDayResponse> days = new ArrayList<>();
        for (int day = 1; day <= yearMonth.lengthOfMonth(); day++) {
            LocalDate date = yearMonth.atDay(day);
            UserCheckinCalendarDayResponse existing = byDate.get(date.toString());
            if (existing != null) {
                days.add(existing);
                continue;
            }
            days.add(new UserCheckinCalendarDayResponse(date.toString(), false, null, null, null, null, null));
        }

        return new UserCheckinCalendarResponse(resolvedYear, resolvedMonth, zoneId.getId(), days);
    }

    public PageResponse<UserCheckinHistoryItemResponse> getHistory(CurrentUser currentUser, Integer page, Integer pageSize) {
        int resolvedPage = page == null || page < 1 ? DEFAULT_HISTORY_PAGE : page;
        int resolvedPageSize = pageSize == null || pageSize < 1 ? DEFAULT_HISTORY_PAGE_SIZE : Math.min(pageSize, MAX_HISTORY_PAGE_SIZE);
        int offset = (resolvedPage - 1) * resolvedPageSize;
        List<UserCheckinHistoryItemResponse> items = repository.listHistory(currentUser.userId(), resolvedPageSize, offset);
        long total = repository.countHistory(currentUser.userId());
        return new PageResponse<>(items, total, resolvedPage, resolvedPageSize);
    }

    private UserCheckinResultResponse toResult(UserCheckinRepository.CheckinRecord record) {
        return new UserCheckinResultResponse(
                record.checkinDate().toString(),
                record.timezone(),
                record.stakeAmount(),
                record.rewardAmount(),
                record.multiplier(),
                record.netChange(),
                record.balanceBefore(),
                record.balanceAfter(),
                record.checkedInAt() == null ? null : record.checkedInAt().toInstant().toString()
        );
    }

    private BigDecimal normalizeStakeAmount(Double stakeAmount) {
        if (stakeAmount == null) {
            throw new IllegalArgumentException("stake_amount is required");
        }
        if (!Double.isFinite(stakeAmount) || stakeAmount <= 0) {
            throw new IllegalArgumentException("stake_amount must be > 0");
        }
        BigDecimal normalized = roundMoney(BigDecimal.valueOf(stakeAmount));
        if (normalized.compareTo(MIN_STAKE) < 0) {
            throw new IllegalArgumentException("stake_amount must be >= 0.01");
        }
        return normalized;
    }

    private BigDecimal randomMultiplier() {
        double raw = ThreadLocalRandom.current().nextDouble(MIN_MULTIPLIER.doubleValue(), MAX_MULTIPLIER.doubleValue() + 0.001d);
        BigDecimal rounded = BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(MIN_MULTIPLIER) < 0) {
            return MIN_MULTIPLIER;
        }
        if (rounded.compareTo(MAX_MULTIPLIER) > 0) {
            return MAX_MULTIPLIER;
        }
        return rounded;
    }

    private BigDecimal roundMoney(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    private void syncFundCheckin(long userId, BigDecimal stakeAmount, BigDecimal rewardAmount,
                                  BigDecimal netChange, String checkinDate) {
        long stakeCents = stakeAmount.movePointRight(8).longValue();
        if (stakeCents > 0) {
            fundService.recordCheckinStake(userId, stakeCents, checkinDate);
        }
        long rewardCents = rewardAmount.movePointRight(8).longValue();
        if (rewardCents > 0) {
            fundService.recordCheckinReward(userId, rewardCents, checkinDate);
        }
    }
}
