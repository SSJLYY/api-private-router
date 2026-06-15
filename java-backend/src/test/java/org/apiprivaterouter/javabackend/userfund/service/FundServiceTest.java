package org.apiprivaterouter.javabackend.userfund.service;

import org.apiprivaterouter.javabackend.admin.fund.model.AdminFundAdjustRequest;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.userfund.model.*;
import org.apiprivaterouter.javabackend.userfund.repository.FundRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundServiceTest {

    private final CurrentUser user = new CurrentUser(1L, "test@test.com", "user", 0L);
    private final CurrentUser admin = new CurrentUser(999L, "admin@test.com", "admin", 0L);
    private final CurrentUser user2 = new CurrentUser(2L, "user2@test.com", "user", 0L);

    private FundResponseTemplates t = new FundResponseTemplates();

    @Test
    void getAccountCreatesIfMissing() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserId(1L)).thenReturn(Optional.empty());
        when(repo.createAccount(1L)).thenReturn(t.account(1L));

        FundService service = new FundService(repo);
        FundAccountResponse result = service.getAccount(user);

        assertEquals(1L, result.id());
        verify(repo).createAccount(1L);
    }

    @Test
    void getAccountReturnsExisting() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserId(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        FundAccountResponse result = service.getAccount(user);

        assertEquals(1L, result.id());
        verify(repo, never()).createAccount(anyLong());
    }

    @Test
    void freezeDeductsAvailableBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.createFreeze(eq(1L), argThat(a -> a.compareTo(money("50")) == 0), eq("test"), isNull(), isNull(), eq(1L)))
                .thenReturn(t.freezeResp(1L, "50"));
        when(repo.findFreezeById(1L, 1L)).thenReturn(Optional.of(t.freezeResp(1L, "50")));

        FundService service = new FundService(repo);
        FreezeResponse result = service.freeze(user, new FreezeRequest(50.0, "test", null, null));

        assertEquals("frozen", result.status());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("100")) == 0), argThat(f -> f.compareTo(money("50")) == 0));
        verify(repo).insertTransaction(anyLong(), anyString(), anyString(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void freezeRejectsInsufficientBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.freeze(user, new FreezeRequest(1000.0, "too much", null, null)));
    }

    @Test
    void unfreezeRestoresAvailableBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findFreezeById(1L, 1L)).thenReturn(Optional.of(t.freezeResp(1L, "30")))
                .thenReturn(Optional.of(t.freezeRespUnfrozen(1L, "30")));

        FundService service = new FundService(repo);
        FreezeResponse result = service.unfreeze(1L, user);

        assertEquals("unfrozen", result.status());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("100")) == 0), argThat(f -> f.compareTo(money("-30")) == 0));
    }

    @Test
    void unfreezeRejectsNonFrozen() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findFreezeById(1L, 1L)).thenReturn(Optional.of(t.freezeRespUnfrozen(1L, "30")));

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class, () -> service.unfreeze(1L, user));
    }

    @Test
    void loanDisbursesFromCredit() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        CreditResponse credit = new CreditResponse(1L, money("500"), BigDecimal.ZERO,
                money("500"), money("0.05"), "active", null, null, null, null);
        when(repo.findCreditByUserId(1L)).thenReturn(Optional.of(credit));
        when(repo.createLoan(eq(1L), argThat(a -> a.compareTo(money("100")) == 0), argThat(r -> r.compareTo(money("0.05")) == 0),
                any(), eq(30))).thenReturn(t.loanResp(1L, "100"));
        when(repo.findLoanById(1L, 1L)).thenReturn(Optional.of(t.loanResp(1L, "100")));

        FundService service = new FundService(repo);
        LoanResponse result = service.loan(user, new LoanRequest(100.0, 30));

        assertEquals("active", result.status());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("200")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateCreditUsed(eq(1L), argThat(u -> u.compareTo(money("100")) == 0));
    }

    @Test
    void loanRejectsExceedingCredit() {
        FundRepository repo = mock(FundRepository.class);
        CreditResponse credit = new CreditResponse(1L, money("50"), money("50"),
                BigDecimal.ZERO, money("0.05"), "active", null, null, null, null);
        when(repo.findCreditByUserId(1L)).thenReturn(Optional.of(credit));

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class, () -> service.loan(user, new LoanRequest(100.0, 30)));
    }

    @Test
    void repayReducesLoanAndCredit() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findLoanById(1L, 1L)).thenReturn(Optional.of(t.loanRespActive(1L, "100", "105")))
                .thenReturn(Optional.of(t.loanRespRepaid(1L, "100")));
        when(repo.findCreditByUserId(1L)).thenReturn(Optional.of(new CreditResponse(1L, money("500"),
                money("100"), money("400"), money("0.05"), "active", null, null, null, null)));
        FundService service = new FundService(repo);
        LoanResponse result = service.repay(user, new RepayRequest(1L, 105.0));

        assertEquals("repaid", result.status());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("-5")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateCreditUsed(eq(1L), argThat(u -> u.compareTo(money("-5")) == 0));
    }

    @Test
    void transferMovesFundsBetweenUsers() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findAccountByUserIdForUpdate(2L)).thenReturn(Optional.of(t.account(2L)));
        when(repo.userExists(2L)).thenReturn(true);

        FundService service = new FundService(repo);
        TransferResponse result = service.transfer(user, new TransferRequest(2L, null, 30.0, null, "test", null));

        assertNotNull(result.group_id());
        assertEquals(1L, result.from_user_id());
        assertEquals(2L, result.to_user_id());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("70")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateAccountBalance(eq(2L), argThat(b -> b.compareTo(money("130")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo, times(2)).insertTransaction(anyLong(), anyString(), anyString(), any(),
                any(), any(), anyString(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void transferRejectsSameUser() {
        FundRepository repo = mock(FundRepository.class);
        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer(user, new TransferRequest(1L, null, 10.0, null, "self", null)));
    }

    @Test
    void transferRejectsInsufficientBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.userExists(2L)).thenReturn(true);

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer(user, new TransferRequest(2L, null, 1000.0, null, "too much", null)));
    }

    @Test
    void transferAddsFeeCorrectly() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findAccountByUserIdForUpdate(2L)).thenReturn(Optional.of(t.account(2L)));
        when(repo.userExists(2L)).thenReturn(true);

        FundService service = new FundService(repo);
        TransferResponse result = service.transfer(user, new TransferRequest(2L, null, 30.0, 5.0, "with fee", null));

        assertTrue(result.amount().compareTo(money("30")) == 0);
        assertTrue(result.fee().compareTo(money("5")) == 0);
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("65")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void apiDeductConsumesBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        // amountInCents uses BigDecimal.valueOf(val, 8), so 5_000_000_000L = 50.00000000
        ApiDeductResponse result = service.deductApiUsage(1L, 5_000_000_000L, "usage_log", 100L, "test deduction");

        assertEquals("success", result.status());
        assertEquals(1L, result.user_id());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("50")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void apiDeductRejectsInsufficientFunds() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.deductApiUsage(1L, 10_000_000_000_000L, "usage_log", 100L, "over balance"));
    }

    @Test
    void apiDeductTriggersAutoLoanWhenCreditAvailable() {
        FundRepository repo = mock(FundRepository.class);
        // First call: balance=100, after auto-loan: balance=150
        when(repo.findAccountByUserIdForUpdate(1L))
                .thenReturn(Optional.of(t.account(1L)))
                .thenReturn(Optional.of(t.accountWithBalance(1L, "150")));
        when(repo.findCreditByUserId(1L)).thenReturn(Optional.of(new CreditResponse(1L, money("500"),
                BigDecimal.ZERO, money("500"), money("0.05"), "active", null, null, null, null)));

        FundService service = new FundService(repo);
        // 15_000_000_000L / 1e8 = 150 > 100 balance, triggers auto-loan of 50
        ApiDeductResponse result = service.deductApiUsage(1L, 15_000_000_000L, "usage_log", 100L, "auto loan test");

        assertEquals("success", result.status());
        verify(repo).updateCreditUsed(eq(1L), argThat(u -> u.compareTo(money("50")) == 0));
    }

    @Test
    void rechargeCreatesPendingOrder() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.createRechargeOrder(eq(1L), argThat(a -> a.compareTo(money("100")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0),
                eq("alipay"), isNull(), isNull(), eq("test")))
                .thenReturn(t.rechargeResp(1L, "100"));

        FundService service = new FundService(repo);
        RechargeResponse result = service.createRecharge(user, new RechargeRequest(100.0, "alipay", null, "test"));

        assertEquals("pending", result.status());
    }

    @Test
    void completeRechargeCreditsBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findRechargeById(1L, 1L)).thenReturn(Optional.of(t.rechargeResp(1L, "100")));

        FundService service = new FundService(repo);
        service.completeRecharge(1L, 1L);

        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("200")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void fundLendingOfferExecutesP2PTransfer() {
        FundRepository repo = mock(FundRepository.class);
        var offer = t.lendingDetailOpen(10L, 2L, "200", "0.08", 30);
        when(repo.findLendingDetailById(10L)).thenReturn(Optional.of(offer));
        // Lender needs balance >= offer amount (200)
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.accountWithBalance(1L, "500")));
        when(repo.findAccountByUserIdForUpdate(2L)).thenReturn(Optional.of(t.account(2L)));

        FundService service = new FundService(repo);
        LendingDetailResponse result = service.fundLendingOffer(user, new LendingFundRequest(10L, ""));

        assertNotNull(result);
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("300")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateAccountBalance(eq(2L), argThat(b -> b.compareTo(money("300")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateLendingFunded(eq(10L), eq(1L), eq(2L), any(), any());
    }

    @Test
    void fundLendingRejectsOwnOffer() {
        FundRepository repo = mock(FundRepository.class);
        var ownUser = new CurrentUser(1L, "own@test.com", "user", 0L);
        var offer = t.lendingDetailOpen(10L, 1L, "100", "0.05", 30);
        when(repo.findLendingDetailById(10L)).thenReturn(Optional.of(offer));

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.fundLendingOffer(ownUser, new LendingFundRequest(10L, "")));
    }

    @Test
    void repayLendingReducesRemaining() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findLendingDetailById(10L)).thenReturn(Optional.of(t.lendingDetailActive(10L, 1L, 2L)));
        when(repo.findAccountByUserIdForUpdate(2L)).thenReturn(Optional.of(t.account(2L)));
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);

        var borrower = new CurrentUser(2L, "borrower@test.com", "user", 0L);
        LendingDetailResponse result = service.repayLending(borrower, new LendingRepayRequest(10L, 50.0, "partial"));

        assertNotNull(result);
        verify(repo).updateAccountBalance(eq(2L), argThat(b -> b.compareTo(money("50")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("150")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void getStatsReturnsAggregated() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.getStats(1L)).thenReturn(new FundStatsResponse(
                money("100"), money("20"),
                money("500"), money("100"),
                money("400"), money("200"),
                money("50")));

        FundService service = new FundService(repo);
        FundStatsResponse result = service.getStats(user);

        assertTrue(result.total_balance().compareTo(money("100")) == 0);
        assertTrue(result.available_credit().compareTo(money("400")) == 0);
    }

    @Test
    void adminAdjustBalance() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findAccountByUserId(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        service.adjustBalance(1L, new AdminFundAdjustRequest(50.0, "bonus"), 999L);

        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("150")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void adminAdjustBalanceNegative() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findAccountByUserId(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        service.adjustBalance(1L, new AdminFundAdjustRequest(-30.0, "deduct"), 999L);

        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("70")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void adminAdjustBalanceRejectsNegativeResult() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));

        FundService service = new FundService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> service.adjustBalance(1L, new AdminFundAdjustRequest(-200.0, "over deduct"), 999L));
    }

    @Test
    void adminTransferBetweenUsers() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.findAccountByUserIdForUpdate(2L)).thenReturn(Optional.of(t.account(2L)));

        FundService service = new FundService(repo);
        TransferResponse result = service.adminTransfer(
                new AdminTransferRequest(1L, 2L, 40.0, "admin move"), 999L);

        assertEquals(1L, result.from_user_id());
        assertEquals(2L, result.to_user_id());
        verify(repo).updateAccountBalance(eq(1L), argThat(b -> b.compareTo(money("60")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
        verify(repo).updateAccountBalance(eq(2L), argThat(b -> b.compareTo(money("140")) == 0), argThat(f -> f.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void adminFreezeAndUnfreeze() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAccountByUserIdForUpdate(1L)).thenReturn(Optional.of(t.account(1L)));
        when(repo.createFreeze(eq(1L), argThat(a -> a.compareTo(money("30")) == 0), eq("admin freeze"), isNull(), isNull(), eq(999L)))
                .thenReturn(t.freezeResp(1L, "30"));
        when(repo.findFreezeByIdAdmin(1L)).thenReturn(Optional.of(t.freezeResp(1L, "30")))
                .thenReturn(Optional.of(t.freezeRespUnfrozen(1L, "30")));

        FundService service = new FundService(repo);
        FreezeResponse freezeResult = service.adminFreeze(1L, new AdminFreezeRequest(1L, 30.0, "admin freeze", null, null), 999L);
        assertEquals("frozen", freezeResult.status());

        FreezeResponse unfreezeResult = service.adminUnfreeze(1L, "admin released", 999L);
        assertEquals("unfrozen", unfreezeResult.status());
        verify(repo, times(2)).updateAccountBalance(anyLong(), any(), any());
    }

    @Test
    void getLendingOffersWithStatusFilter() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findLendingOffers("open", 10, 0)).thenReturn(List.of());
        when(repo.countLendingOffers("open")).thenReturn(0L);

        FundService service = new FundService(repo);
        PageResponse<LendingOfferResponse> result = service.getLendingOffers("open", 1, 10);

        assertEquals(0, result.items().size());
    }

    @Test
    void cancelOpenLendingOffer() {
        FundRepository repo = mock(FundRepository.class);
        var offer = t.lendingDetailOpen(10L, 1L, "100", "0.05", 30);
        when(repo.findLendingDetailById(10L)).thenReturn(Optional.of(offer));

        FundService service = new FundService(repo);
        service.cancelLendingOffer(user, 10L);
        verify(repo).updateLendingCancel(10L);
    }

    @Test
    void auditLogsForUser() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAuditLogs(eq(1L), eq(20), eq(0), isNull(), isNull(), isNull()))
                .thenReturn(List.of(t.auditLogResp(1L)));
        when(repo.countAuditLogs(eq(1L), isNull(), isNull(), isNull())).thenReturn(1L);

        FundService service = new FundService(repo);
        PageResponse<AuditLogResponse> result = service.getAuditLogs(user, 1, 20, null);

        assertEquals(1, result.items().size());
        assertEquals("fund_freeze", result.items().get(0).action());
    }

    @Test
    void overviewReturnsStats() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.getOverview()).thenReturn(new FundOverviewResponse(
                10L, money("10000"), money("500"),
                money("5000"), money("2000"),
                money("3000"), money("1000"),
                5L, 3L, 4L, 2L, money("200"),
                1L, money("50"), 10L, money("800")));

        FundService service = new FundService(repo);
        FundOverviewResponse result = service.getOverview();

        assertEquals(10L, result.total_users());
        assertTrue(result.total_balance().compareTo(money("10000")) == 0);
    }

    @Test
    void adminUpdateCredit() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findCreditByUserId(1L)).thenReturn(Optional.of(
                new CreditResponse(1L, money("200"), BigDecimal.ZERO,
                        money("200"), money("0.03"), "active", null, null, null, null)));

        FundService service = new FundService(repo);
        service.updateCredit(1L, new AdminCreditUpdateRequest(1L, 1000.0, 0.06));

        verify(repo).updateCredit(eq(1L), argThat(l -> l.compareTo(money("1000")) == 0), argThat(r -> r.compareTo(money("0.06")) == 0));
    }

    @Test
    void adminUserLoans() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findAllLoans(2L, "active", 10, 0)).thenReturn(List.of(t.loanResp(1L, "200")));
        when(repo.countAllLoans(2L, "active")).thenReturn(1L);

        FundService service = new FundService(repo);
        PageResponse<LoanResponse> result = service.getAllLoans(2L, "active", 1, 10);

        assertEquals(1, result.items().size());
    }

    @Test
    void adminLendingDetails() {
        FundRepository repo = mock(FundRepository.class);
        when(repo.findLendingDetails(1L, "open", 10, 0)).thenReturn(List.of(t.lendingDetailOpen(1L, 1L, "100", "0.05", 30)));
        when(repo.countLendingDetails(1L, "open")).thenReturn(1L);

        FundService service = new FundService(repo);
        PageResponse<LendingDetailResponse> result = service.getAllLendingDetails(1L, "open", 1, 10);

        assertEquals(1, result.items().size());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(8);
    }

    static class FundResponseTemplates {

        FundAccountResponse account(long userId) {
            return new FundAccountResponse(userId, "main",
                    money("100"), BigDecimal.ZERO,
                    money("500"), BigDecimal.ZERO,
                    money("100"),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    "active", null);
        }

        FundAccountResponse accountWithBalance(long userId, String balance) {
            return new FundAccountResponse(userId, "main",
                    money(balance), BigDecimal.ZERO,
                    money("500"), BigDecimal.ZERO,
                    money(balance),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    "active", null);
        }

        FreezeResponse freezeResp(long id, String amount) {
            return new FreezeResponse(id, 1L, money(amount), "test freeze",
                    "frozen", null, null, null, null, "", null);
        }

        FreezeResponse freezeRespUnfrozen(long id, String amount) {
            return new FreezeResponse(id, 1L, money(amount), "test freeze",
                    "unfrozen", null, null, null, null, "", null);
        }

        LoanResponse loanResp(long id, String amount) {
            return new LoanResponse(id, money(amount),
                    money("0.05"), money("5"),
                    BigDecimal.ZERO, money("105"),
                    "active", null, null, null);
        }

        LoanResponse loanRespActive(long id, String amount, String remaining) {
            return new LoanResponse(id, money(amount),
                    money("0.05"), money("5"),
                    BigDecimal.ZERO, money(remaining),
                    "active", null, null, null);
        }

        LoanResponse loanRespRepaid(long id, String amount) {
            return new LoanResponse(id, money(amount),
                    money("0.05"), money("5"),
                    money(amount), BigDecimal.ZERO,
                    "repaid", null, null, null);
        }

        LendingDetailResponse lendingDetailOpen(long id, long userId, String amount, String rate, int days) {
            return new LendingDetailResponse(id, userId, null, null,
                    money(amount), money(rate), days,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "open", null, null, null, null, "", null, null);
        }

        LendingDetailResponse lendingDetailActive(long id, long lenderId, long borrowerId) {
            return new LendingDetailResponse(id, borrowerId, lenderId, borrowerId,
                    money("100"), money("0.05"), 30,
                    money("0.50"), money("105"), money("105"),
                    "active", null, null, null, null, "", null, null);
        }

        RechargeResponse rechargeResp(long id, String amount) {
            return new RechargeResponse(id, 1L, money(amount), BigDecimal.ZERO,
                    "alipay", null, "pending", BigDecimal.ZERO, null, null);
        }

        AuditLogResponse auditLogResp(long id) {
            return new AuditLogResponse(id, 1L, "fund_freeze", "freeze", 1L,
                    money("50"), money("100"), money("50"), "test freeze",
                    "1", "user", null, "success", null);
        }
    }
}
