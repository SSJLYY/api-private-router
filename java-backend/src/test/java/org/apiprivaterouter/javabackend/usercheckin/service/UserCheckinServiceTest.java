package org.apiprivaterouter.javabackend.usercheckin.service;

import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinRequest;
import org.apiprivaterouter.javabackend.usercheckin.repository.UserCheckinRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserCheckinServiceTest {

    @Test
    void rejectsStakeBelowMinimum() {
        UserCheckinService service = new UserCheckinService(new FakeRepository(new BigDecimal("10.0")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.checkin(new CurrentUser(1L, "user", "u@test.com", 0L), new UserCheckinRequest(0.001, "UTC")));

        assertEquals("stake_amount must be >= 0.01", ex.getMessage());
    }

    @Test
    void rejectsInsufficientBalance() {
        UserCheckinService service = new UserCheckinService(new FakeRepository(new BigDecimal("5.0")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.checkin(new CurrentUser(1L, "user", "u@test.com", 0L), new UserCheckinRequest(10.0, "UTC")));

        assertEquals("insufficient balance", ex.getMessage());
    }

    private static final class FakeRepository extends UserCheckinRepository {
        private final BigDecimal balance;

        private FakeRepository(BigDecimal balance) {
            super(null);
            this.balance = balance;
        }

        @Override
        public Optional<UserBalanceSnapshot> findUserByIdForUpdate(long userId) {
            return Optional.of(new UserBalanceSnapshot(userId, balance));
        }

        @Override
        public Optional<UserBalanceSnapshot> findUserById(long userId) {
            return Optional.of(new UserBalanceSnapshot(userId, balance));
        }

        @Override
        public Optional<CheckinRecord> findByUserAndDate(long userId, LocalDate checkinDate) {
            return Optional.empty();
        }

        @Override
        public void updateUserBalance(long userId, BigDecimal balanceAfter) {
        }

        @Override
        public CheckinRecord createCheckin(long userId, LocalDate checkinDate, String timezone, BigDecimal stakeAmount, BigDecimal rewardAmount, BigDecimal multiplier, BigDecimal netChange, BigDecimal balanceBefore, BigDecimal balanceAfter, OffsetDateTime checkedInAt) {
            return new CheckinRecord(1L, userId, checkinDate, timezone, stakeAmount, rewardAmount, multiplier, netChange, balanceBefore, balanceAfter, checkedInAt);
        }

        @Override
        public List<org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinCalendarDayResponse> listMonthCalendar(long userId, LocalDate startDate, LocalDate endDate) {
            return List.of();
        }

        @Override
        public List<org.apiprivaterouter.javabackend.usercheckin.model.UserCheckinHistoryItemResponse> listHistory(long userId, int limit, int offset) {
            return List.of();
        }

        @Override
        public long countHistory(long userId) {
            return 0;
        }

        @Override
        public ZoneId normalizeTimezone(String timezone) {
            return ZoneId.of("UTC");
        }
    }
}
