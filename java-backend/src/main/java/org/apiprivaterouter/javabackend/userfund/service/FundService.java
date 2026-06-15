package org.apiprivaterouter.javabackend.userfund.service;

import org.apiprivaterouter.javabackend.admin.fund.model.AdminFundAdjustRequest;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.api.StructuredApiErrorException;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.userfund.model.*;
import org.apiprivaterouter.javabackend.userfund.repository.FundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FundService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final FundRepository repository;

    public FundService(FundRepository repository) {
        this.repository = repository;
    }

    private int resolvePage(Integer page) {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    private int resolvePageSize(Integer pageSize) {
        return (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private FundAccountResponse getOrCreateAccount(long userId) {
        return repository.findAccountByUserId(userId)
                .orElseGet(() -> repository.createAccount(userId));
    }

    /**
     * Lock the account row with SELECT FOR UPDATE to prevent concurrent balance modifications.
     * Creates the account if it doesn't exist yet.
     */
    private FundAccountResponse lockOrCreateAccount(long userId) {
        return repository.findAccountByUserIdForUpdate(userId)
                .orElseGet(() -> repository.createAccount(userId));
    }

    /**
     * Lock two account rows in consistent order (lower userId first) to prevent deadlocks.
     */
    private FundAccountResponse[] lockTwoAccounts(long userId1, long userId2) {
        if (userId1 == userId2) {
            FundAccountResponse a = lockOrCreateAccount(userId1);
            return new FundAccountResponse[]{a, a};
        }
        long first = Math.min(userId1, userId2);
        long second = Math.max(userId1, userId2);
        FundAccountResponse a1 = lockOrCreateAccount(first);
        FundAccountResponse a2 = lockOrCreateAccount(second);
        return userId1 < userId2 ? new FundAccountResponse[]{a1, a2} : new FundAccountResponse[]{a2, a1};
    }

    private HouseAccountResponse lockHouseAccount() {
        return repository.findHouseAccountForUpdate();
    }

    private String newGroupId() {
        return UUID.randomUUID().toString();
    }

    private BigDecimal decimal(Double value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }

    // ===== User Operations =====

    public FundAccountResponse getAccount(CurrentUser user) {
        return getOrCreateAccount(user.userId());
    }

    public FundStatsResponse getStats(CurrentUser user) {
        return repository.getStats(user.userId());
    }

    public PageResponse<FundTransactionResponse> getTransactions(CurrentUser user, Integer page, Integer pageSize, String txType) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<FundTransactionResponse> items = repository.findTransactions(user.userId(), ps, offset, txType, null, null, null, null);
        long total = repository.countTransactions(user.userId(), txType, null, null, null, null);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public FreezeResponse freeze(CurrentUser user, FreezeRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = decimal(req.amount());
        FundAccountResponse account = lockOrCreateAccount(user.userId());
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        if (available.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }

        FreezeResponse freezeResult = repository.createFreeze(
                user.userId(), amount, req.reason(), req.ref_type(), req.ref_id(), user.userId());
        repository.updateAccountBalance(user.userId(), account.balance(), account.frozen_amount().add(amount));
        long txId = repository.insertTransaction(user.userId(), "freeze", "out", amount,
                available, available.subtract(amount), "freeze", freezeResult.id(), null,
                BigDecimal.ZERO, null, "", req.reason());
        repository.insertAuditLog(user.userId(), "fund_freeze", "freeze", freezeResult.id(),
                amount, available, available.subtract(amount), req.reason(),
                user.userId(), "user", null, "success");
        return freezeResult;
    }

    @Transactional
    public FreezeResponse unfreeze(long freezeId, CurrentUser user) {
        FreezeResponse freezeResult = repository.findFreezeByIdForUpdate(freezeId, user.userId())
                .orElseThrow(() -> new IllegalArgumentException("freeze not found"));
        if (!"frozen".equals(freezeResult.status())) {
            throw new IllegalArgumentException("freeze is not in frozen status");
        }

        FundAccountResponse account = lockOrCreateAccount(user.userId());
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        repository.updateFreezeStatus(freezeId, "unfrozen", "unfreeze by user");
        repository.updateAccountBalance(user.userId(), account.balance(), account.frozen_amount().subtract(freezeResult.amount()));
        long txId = repository.insertTransaction(user.userId(), "unfreeze", "in", freezeResult.amount(),
                available, available.add(freezeResult.amount()), "freeze", freezeId, null,
                BigDecimal.ZERO, null, "", "unfreeze");
        repository.insertAuditLog(user.userId(), "fund_unfreeze", "freeze", freezeId,
                freezeResult.amount(), available, available.add(freezeResult.amount()), "unfreeze",
                user.userId(), "user", null, "success");
        return repository.findFreezeById(freezeId, user.userId()).orElseThrow();
    }

    public PageResponse<FreezeResponse> getFreezes(CurrentUser user, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<FreezeResponse> items = repository.findFreezes(user.userId(), status, ps, offset);
        long total = repository.countFreezes(user.userId(), status);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public CreditResponse getCredit(CurrentUser user) {
        return repository.findCreditByUserId(user.userId())
                .orElseGet(() -> {
                    repository.createCredit(user.userId());
                    return repository.findCreditByUserId(user.userId()).orElseThrow();
                });
    }

    @Transactional
    public CreditResponse setCredit(long userId, AdminCreditUpdateRequest req) {
        CreditResponse credit = repository.findCreditByUserId(userId)
                .orElseGet(() -> {
                    repository.createCredit(userId);
                    return repository.findCreditByUserId(userId).orElseThrow();
                });

        BigDecimal newLimit = req.credit_limit() != null ? decimal(req.credit_limit()) : credit.credit_limit();
        BigDecimal newRate = req.interest_rate() != null ? decimal(req.interest_rate()) : credit.interest_rate();
        repository.updateCredit(userId, newLimit, newRate);

        repository.insertAuditLog(userId, "credit_update", "credit", credit.id(),
                newLimit, credit.credit_limit(), newLimit, "credit limit updated",
                userId, "user", null, "success");

        return repository.findCreditByUserId(userId).orElseThrow();
    }

    @Transactional
    public LoanResponse loan(CurrentUser user, LoanRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (req.duration_days() == null || req.duration_days() <= 0) {
            throw new IllegalArgumentException("duration_days must be > 0");
        }
        BigDecimal amount = decimal(req.amount());

        CreditResponse credit = repository.findCreditByUserIdForUpdate(user.userId())
                .orElseThrow(() -> new IllegalArgumentException("credit not found"));
        BigDecimal availableCredit = credit.credit_limit().subtract(credit.credit_used());
        if (availableCredit.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient credit");
        }

        BigDecimal interestRate = credit.interest_rate();
        BigDecimal interestAmount = amount.multiply(interestRate)
                .multiply(BigDecimal.valueOf(req.duration_days()))
                .divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);

        LoanResponse loanResult = repository.createLoan(user.userId(), amount, interestRate, interestAmount, req.duration_days());
        if (repository.adjustCreditUsed(user.userId(), amount) == 0) {
            throw new IllegalStateException("credit adjustment failed (concurrent modification)");
        }

        FundAccountResponse account = lockOrCreateAccount(user.userId());
        BigDecimal balanceBefore = account.balance();
        repository.updateAccountBalance(user.userId(), balanceBefore.add(amount), account.frozen_amount());
        repository.updateAccountCumulative(user.userId(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount);
        long txId = repository.insertTransaction(user.userId(), "loan", "in", amount,
                balanceBefore, balanceBefore.add(amount), "loan", loanResult.id(), null,
                BigDecimal.ZERO, null, "", "loan from credit");
        repository.insertAuditLog(user.userId(), "fund_loan", "loan", loanResult.id(),
                amount, balanceBefore, balanceBefore.add(amount), "loan",
                user.userId(), "user", null, "success");

        return loanResult;
    }

    @Transactional
    public LoanResponse repay(CurrentUser user, RepayRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = decimal(req.amount());

        LoanResponse loanResult = repository.findLoanById(req.loan_id(), user.userId())
                .orElseThrow(() -> new IllegalArgumentException("loan not found"));
        if (!"active".equals(loanResult.status())) {
            throw new IllegalArgumentException("loan is not active");
        }
        if (amount.compareTo(loanResult.remaining_amount()) > 0) {
            throw new IllegalArgumentException("amount exceeds remaining");
        }

        BigDecimal newRepaid = loanResult.repaid_amount().add(amount);
        BigDecimal newRemaining = loanResult.remaining_amount().subtract(amount);
        String newStatus = newRemaining.compareTo(BigDecimal.ZERO) <= 0 ? "repaid" : "active";

        repository.updateLoanRepayment(loanResult.id(), newRepaid, newRemaining, newStatus);

        CreditResponse credit = repository.findCreditByUserIdForUpdate(user.userId())
                .orElseThrow(() -> new IllegalArgumentException("credit not found"));
        if (repository.adjustCreditUsed(user.userId(), amount.negate()) == 0) {
            throw new IllegalStateException("credit adjustment failed (concurrent modification)");
        }

        FundAccountResponse account = lockOrCreateAccount(user.userId());
        BigDecimal balanceBefore = account.balance();
        repository.updateAccountBalance(user.userId(), balanceBefore.subtract(amount), account.frozen_amount());
        repository.updateAccountCumulative(user.userId(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount.negate());
        long txId = repository.insertTransaction(user.userId(), "repay", "out", amount,
                balanceBefore, balanceBefore.subtract(amount), "loan", loanResult.id(), null,
                BigDecimal.ZERO, null, "", "repay loan");
        repository.insertAuditLog(user.userId(), "fund_repay", "loan", loanResult.id(),
                amount, balanceBefore, balanceBefore.subtract(amount), "repay",
                user.userId(), "user", null, "success");

        return repository.findLoanById(loanResult.id(), user.userId()).orElseThrow();
    }

    public PageResponse<LoanResponse> getLoans(CurrentUser user, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<LoanResponse> items = repository.findLoans(user.userId(), status, ps, offset);
        long total = repository.countLoans(user.userId(), status);
        return new PageResponse<>(items, total, p, ps);
    }

    public PageResponse<LoanResponse> getAllLoans(Long userId, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<LoanResponse> items = repository.findAllLoans(userId, status, ps, offset);
        long total = repository.countAllLoans(userId, status);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public LendingDetailResponse createLendingOffer(CurrentUser user, LendingOfferRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (req.interest_rate() == null || req.interest_rate() <= 0) {
            throw new IllegalArgumentException("interest_rate must be > 0");
        }
        if (req.duration_days() == null || req.duration_days() <= 0) {
            throw new IllegalArgumentException("duration_days must be > 0");
        }

        FundAccountResponse account = getOrCreateAccount(user.userId());
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        BigDecimal amount = decimal(req.amount());
        if (available.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }

        LendingDetailResponse offer = repository.createLendingOfferFull(
                user.userId(), amount, decimal(req.interest_rate()), req.duration_days());
        repository.insertAuditLog(user.userId(), "lending_offer_create", "lending_offer", offer.id(),
                amount, null, null, "create lending offer",
                user.userId(), "user", null, "success");
        return offer;
    }

    public PageResponse<LendingOfferResponse> getLendingOffers(String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<LendingOfferResponse> items = repository.findLendingOffers(status, ps, offset);
        long total = repository.countLendingOffers(status);
        return new PageResponse<>(items, total, p, ps);
    }

    public PageResponse<LendingDetailResponse> getMyLendingOffers(CurrentUser user, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<LendingDetailResponse> items = repository.findLendingDetails(user.userId(), status, ps, offset);
        long total = repository.countLendingDetails(user.userId(), status);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public LendingDetailResponse updateLendingOffer(CurrentUser user, long offerId, LendingOfferRequest req) {
        LendingDetailResponse offer = repository.findLendingDetailById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("lending offer not found"));
        if (offer.user_id() != user.userId()) {
            throw new StructuredApiErrorException(403, "FORBIDDEN", "not your offer");
        }
        if (!"open".equals(offer.status())) {
            throw new IllegalArgumentException("only open offers can be updated");
        }
        if (req.amount() != null && req.amount() > 0) {
            repository.updateLendingAmount(offerId, decimal(req.amount()));
        }
        if (req.interest_rate() != null && req.interest_rate() >= 0) {
            repository.updateLendingInterestRate(offerId, decimal(req.interest_rate()));
        }
        if (req.duration_days() != null && req.duration_days() > 0) {
            repository.updateLendingDuration(offerId, req.duration_days());
        }
        repository.insertAuditLog(user.userId(), "lending_update", "lending_offer", offerId,
                offer.amount(), null, null, "updated lending offer",
                user.userId(), "user", null, "success");
        return repository.findLendingDetailById(offerId).orElseThrow();
    }

    // ===== New Feature: Transfer (3) =====

    @Transactional
    public TransferResponse transfer(CurrentUser user, TransferRequest req) {
        if (req.idempotency_key() != null && !req.idempotency_key().isBlank()) {
            boolean claimed = repository.claimIdempotencyKey("fund_transfer", req.idempotency_key());
            if (!claimed) {
                throw new IllegalArgumentException("duplicate transfer request");
            }
        }

        if (req.to_user_id() == null && (req.to_username() == null || req.to_username().isBlank())) {
            throw new IllegalArgumentException("to_user_id or to_username is required");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = decimal(req.amount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        long fromUserId = user.userId();
        long toUserId;
        if (req.to_user_id() != null) {
            toUserId = req.to_user_id();
            if (toUserId == fromUserId) {
                throw new IllegalArgumentException("cannot transfer to self");
            }
            if (!repository.userExists(toUserId)) {
                throw new IllegalArgumentException("target user not found");
            }
        } else {
            toUserId = repository.findUserIdByUsername(req.to_username())
                    .orElseThrow(() -> new IllegalArgumentException("target username not found"));
            if (toUserId == fromUserId) {
                throw new IllegalArgumentException("cannot transfer to self");
            }
        }

        BigDecimal fee = req.fee() != null && req.fee() > 0
                ? BigDecimal.valueOf(req.fee()).setScale(8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal totalDeduct = amount.add(fee);
        String remark = req.remark() != null ? req.remark() : "";
        String groupId = newGroupId();

        FundAccountResponse[] locked = lockTwoAccounts(fromUserId, toUserId);
        FundAccountResponse fromAccount = locked[0];
        FundAccountResponse toAccount = locked[1];
        BigDecimal fromAvailable = fromAccount.balance().subtract(fromAccount.frozen_amount());
        if (fromAvailable.compareTo(totalDeduct) < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        BigDecimal fromNewBalance = fromAccount.balance().subtract(totalDeduct);

        BigDecimal toNewBalance = toAccount.balance().add(amount);

        repository.updateAccountBalance(fromUserId, fromNewBalance, fromAccount.frozen_amount());
        repository.updateAccountBalance(toUserId, toNewBalance, toAccount.frozen_amount());
        repository.updateAccountCumulative(fromUserId, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, amount, BigDecimal.ZERO, BigDecimal.ZERO);
        repository.updateAccountCumulative(toUserId, BigDecimal.ZERO, BigDecimal.ZERO,
                amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        long fromTxId = repository.insertTransaction(fromUserId, "transfer", "out", amount,
                fromAccount.balance(), fromNewBalance, "transfer", toUserId, toUserId,
                fee, groupId, remark, "transfer to user " + toUserId);
        long toTxId = repository.insertTransaction(toUserId, "transfer", "in", amount,
                toAccount.balance(), toNewBalance, "transfer", fromUserId, fromUserId,
                BigDecimal.ZERO, groupId, remark, "transfer from user " + fromUserId);

        repository.insertAuditLog(fromUserId, "fund_transfer_out", "transfer", fromTxId,
                amount, fromAccount.balance(), fromNewBalance, "transfer to " + toUserId + ": " + remark,
                fromUserId, "user", null, "success");
        repository.insertAuditLog(toUserId, "fund_transfer_in", "transfer", toTxId,
                amount, toAccount.balance(), toNewBalance, "transfer from " + fromUserId + ": " + remark,
                fromUserId, "user", null, "success");
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            repository.insertAuditLog(0L, "platform_fee", "transfer_fee", fromTxId,
                    fee, null, null, "transfer fee: " + remark,
                    fromUserId, "user", null, "success");
        }

        return new TransferResponse(groupId, fromUserId, toUserId, amount, fee,
                fromNewBalance, toNewBalance, fromTxId, toTxId, null);
    }

    // ===== New Feature: API Deduct (3) =====

    @Transactional
    public ApiDeductResponse deductApiUsage(long userId, long amountInCents, String refType, Long refId, String description) {
        if (amountInCents <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = BigDecimal.valueOf(amountInCents, 8);
        FundAccountResponse account = lockOrCreateAccount(userId);
        BigDecimal available = account.balance().subtract(account.frozen_amount());

        if (available.compareTo(amount) < 0) {
            checkCreditDeduction(userId, amount, account, refType, refId);
        }

        // Re-read after potential credit deduction (which may have updated balance)
        account = lockOrCreateAccount(userId);
        available = account.balance().subtract(account.frozen_amount());
        if (available.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient balance");
        }

        BigDecimal newBalance = account.balance().subtract(amount);
        repository.updateAccountBalance(userId, newBalance, account.frozen_amount());
        repository.updateAccountCumulative(userId, BigDecimal.ZERO, amount,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long txId = repository.insertTransaction(userId, "api_deduct", "out", amount,
                account.balance(), newBalance, refType, refId, null,
                BigDecimal.ZERO, null, "", description != null ? description : "api usage");
        repository.insertAuditLog(userId, "api_deduct", refType, refId,
                amount, account.balance(), newBalance, description,
                userId, "system", null, "success");

        BigDecimal newAvailable = newBalance.subtract(account.frozen_amount());
        return new ApiDeductResponse(txId, userId, amount, account.balance(), newBalance, newAvailable, "success", null);
    }

    private void checkCreditDeduction(long userId, BigDecimal amountNeeded, FundAccountResponse account,
                                       String refType, Long refId) {
        CreditResponse credit = repository.findCreditByUserIdForUpdate(userId).orElse(null);
        if (credit == null) return;

        BigDecimal availableCredit = credit.credit_limit().subtract(credit.credit_used());
        BigDecimal shortage = amountNeeded.subtract(account.balance().subtract(account.frozen_amount()));
        BigDecimal borrowAmount = shortage.compareTo(BigDecimal.ZERO) > 0 ? shortage : amountNeeded;

        if (availableCredit.compareTo(borrowAmount) < 0) return;

        BigDecimal currentBalance = account.balance();
        repository.updateAccountBalance(userId, currentBalance.add(borrowAmount), account.frozen_amount());
        if (repository.adjustCreditUsed(userId, borrowAmount) == 0) {
            throw new IllegalStateException("credit adjustment failed (concurrent modification)");
        }

        repository.insertTransaction(userId, "auto_loan", "in", borrowAmount,
                currentBalance, currentBalance.add(borrowAmount), refType, refId, null,
                BigDecimal.ZERO, null, "", "auto loan for deduction");
        repository.insertAuditLog(userId, "auto_loan_deduct", refType, refId,
                borrowAmount, currentBalance, currentBalance.add(borrowAmount), "auto loan",
                userId, "system", null, "success");
    }

    // ===== New Feature: Recharge (4) =====

    @Transactional
    public RechargeResponse createRecharge(CurrentUser user, RechargeRequest req) {
        return createRechargeForSystem(user.userId(), req);
    }

    @Transactional
    public RechargeResponse createRechargeForSystem(long userId, RechargeRequest req) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (req.channel() == null || req.channel().isBlank()) {
            throw new IllegalArgumentException("channel is required");
        }

        BigDecimal amount = decimal(req.amount());
        BigDecimal fee = BigDecimal.ZERO;
        String extOrderId = req.external_order_id() != null ? req.external_order_id() : null;

        if (extOrderId != null) {
            Optional<RechargeResponse> existing = repository.findRechargeByExternalOrder(extOrderId);
            if (existing.isPresent()) {
                throw new IllegalArgumentException("duplicate external order id");
            }
        }

        return repository.createRechargeOrder(userId, amount, fee, req.channel(),
                extOrderId, null, req.remark());
    }

    @Transactional
    public RechargeResponse completeRecharge(long orderId, long userId) {
        FundAccountResponse account = lockOrCreateAccount(userId);
        RechargeResponse order = repository.findRechargeById(orderId, userId)
                .orElseThrow(() -> new IllegalArgumentException("recharge order not found"));
        if (!"pending".equals(order.status())) {
            throw new IllegalArgumentException("recharge order is not pending");
        }

        BigDecimal amount = order.amount();
        BigDecimal newBalance = account.balance().add(amount);
        repository.updateAccountBalance(userId, newBalance, account.frozen_amount());
        repository.updateAccountCumulative(userId, amount, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        if (repository.completeRechargeOrder(orderId, newBalance) == 0) {
            throw new IllegalStateException("recharge order already completed or not pending (concurrent modification)");
        }
        repository.insertTransaction(userId, "recharge", "in", amount,
                account.balance(), newBalance, "recharge", orderId, null,
                BigDecimal.ZERO, null, "", "recharge via " + order.channel());
        repository.insertAuditLog(userId, "fund_recharge_complete", "recharge", orderId,
                amount, account.balance(), newBalance, "recharge completed",
                userId, "user", null, "success");

        return repository.findRechargeById(orderId, userId).orElseThrow();
    }

    @Transactional
    public void recordCheckinStake(long userId, long amountInCents, String checkinDate) {
        if (amountInCents <= 0) return;
        BigDecimal amount = BigDecimal.valueOf(amountInCents, 8);
        FundAccountResponse account = lockOrCreateAccount(userId);
        BigDecimal newBalance = account.balance().subtract(amount);
        repository.updateAccountBalance(userId, newBalance, account.frozen_amount());
        repository.insertTransaction(userId, "checkin_stake", "out", amount,
                account.balance(), newBalance, "checkin", null, null,
                BigDecimal.ZERO, null, "", "checkin stake for " + checkinDate);
    }

    @Transactional
    public void recordCheckinReward(long userId, long amountInCents, String checkinDate) {
        if (amountInCents <= 0) return;
        BigDecimal amount = BigDecimal.valueOf(amountInCents, 8);
        FundAccountResponse account = lockOrCreateAccount(userId);
        BigDecimal newBalance = account.balance().add(amount);
        repository.updateAccountBalance(userId, newBalance, account.frozen_amount());
        repository.updateAccountCumulative(userId, amount, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        repository.insertTransaction(userId, "checkin_reward", "in", amount,
                account.balance(), newBalance, "checkin", null, null,
                BigDecimal.ZERO, null, "", "checkin reward for " + checkinDate);
    }

    public PageResponse<RechargeResponse> getRecharges(CurrentUser user, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<RechargeResponse> items = repository.findRecharges(user.userId(), status, ps, offset);
        long total = repository.countRecharges(user.userId(), status);
        return new PageResponse<>(items, total, p, ps);
    }

    // ===== New Feature: P2P Lending Fund (9) =====

    @Transactional
    public LendingDetailResponse fundLendingOffer(CurrentUser user, LendingFundRequest req) {
        if (req.offer_id() == null || req.offer_id() <= 0) {
            throw new IllegalArgumentException("offer_id is required");
        }

        LendingDetailResponse offer = repository.findLendingDetailById(req.offer_id())
                .orElseThrow(() -> new IllegalArgumentException("lending offer not found"));
        if (!"open".equals(offer.status())) {
            throw new IllegalArgumentException("offer is not open");
        }
        if (offer.user_id() == user.userId()) {
            throw new IllegalArgumentException("cannot fund your own offer");
        }

        BigDecimal amount = offer.amount();
        BigDecimal interestRate = offer.interest_rate();
        int durationDays = offer.duration_days();
        BigDecimal interestAmount = amount.multiply(interestRate)
                .multiply(BigDecimal.valueOf(durationDays))
                .divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);
        BigDecimal platformFee = interestAmount.multiply(BigDecimal.valueOf(0.10))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalRepay = amount.add(interestAmount).subtract(platformFee);
        BigDecimal lenderNeeded = amount;

        FundAccountResponse[] locked = lockTwoAccounts(user.userId(), offer.user_id());
        FundAccountResponse lenderAccount = locked[0];
        FundAccountResponse borrowerAccount = locked[1];
        BigDecimal lenderAvailable = lenderAccount.balance().subtract(lenderAccount.frozen_amount());
        if (lenderAvailable.compareTo(lenderNeeded) < 0) {
            throw new IllegalArgumentException("insufficient available balance as lender");
        }

        BigDecimal lenderNewBalance = lenderAccount.balance().subtract(lenderNeeded);
        BigDecimal borrowerNewBalance = borrowerAccount.balance().add(amount);

        repository.updateAccountBalance(user.userId(), lenderNewBalance, lenderAccount.frozen_amount());
        repository.updateAccountBalance(offer.user_id(), borrowerNewBalance, borrowerAccount.frozen_amount());
        repository.updateAccountCumulative(user.userId(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, amount, amount, BigDecimal.ZERO);
        repository.updateAccountCumulative(offer.user_id(), BigDecimal.ZERO, BigDecimal.ZERO,
                amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        if (repository.updateLendingFunded(offer.id(), user.userId(), offer.user_id(), platformFee, totalRepay) == 0) {
            throw new IllegalStateException("lending offer is no longer open (concurrent modification)");
        }

        String groupId = newGroupId();
        long fromTxId = repository.insertTransaction(user.userId(), "lend_out", "out", amount,
                lenderAccount.balance(), lenderNewBalance, "lending_offer", offer.id(), offer.user_id(),
                BigDecimal.ZERO, groupId, "", "lending to user " + offer.user_id());
        long toTxId = repository.insertTransaction(offer.user_id(), "lend_in", "in", amount,
                borrowerAccount.balance(), borrowerNewBalance, "lending_offer", offer.id(), user.userId(),
                BigDecimal.ZERO, groupId, "", "lending from user " + user.userId());

        repository.insertAuditLog(user.userId(), "lending_fund", "lending_offer", offer.id(),
                amount, lenderAccount.balance(), lenderNewBalance, "funded lending offer " + req.offer_id(),
                user.userId(), "user", null, "success");
        repository.insertAuditLog(offer.user_id(), "lending_receive", "lending_offer", offer.id(),
                amount, borrowerAccount.balance(), borrowerNewBalance, "received lending from user " + user.userId(),
                user.userId(), "user", null, "success");

        return repository.findLendingDetailById(offer.id()).orElseThrow();
    }

    @Transactional
    public LendingDetailResponse repayLending(CurrentUser user, LendingRepayRequest req) {
        if (req.offer_id() <= 0) {
            throw new IllegalArgumentException("offer_id is required");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        LendingDetailResponse offer = repository.findLendingDetailByIdForUpdate(req.offer_id())
                .orElseThrow(() -> new IllegalArgumentException("lending offer not found"));
        if (offer.user_id() != user.userId()) {
            throw new IllegalArgumentException("only the borrower can repay");
        }
        if (!"funded".equals(offer.status()) && !"active".equals(offer.status())) {
            throw new IllegalArgumentException("offer is not in repayable status");
        }

        BigDecimal amount = decimal(req.amount());
        if (amount.compareTo(offer.remaining_amount()) > 0) {
            amount = offer.remaining_amount();
        }

        FundAccountResponse[] locked = lockTwoAccounts(user.userId(), offer.lender_id());
        FundAccountResponse borrowerAccount = locked[0];
        FundAccountResponse lenderAccount = locked[1];
        BigDecimal borrowerAvailable = borrowerAccount.balance().subtract(borrowerAccount.frozen_amount());
        if (borrowerAvailable.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }

        BigDecimal borrowerNewBalance = borrowerAccount.balance().subtract(amount);
        BigDecimal lenderNewBalance = lenderAccount.balance().add(amount);

        repository.updateAccountBalance(user.userId(), borrowerNewBalance, borrowerAccount.frozen_amount());
        repository.updateAccountBalance(offer.lender_id(), lenderNewBalance, lenderAccount.frozen_amount());
        repository.updateAccountCumulative(user.userId(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, amount.negate());
        repository.updateAccountCumulative(offer.lender_id(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, amount.negate(), BigDecimal.ZERO);

        BigDecimal newRepaid = offer.total_repay_amount().add(amount);
        BigDecimal newRemaining = offer.remaining_amount().subtract(amount);
        repository.updateLendingRepaid(offer.id(), newRepaid, newRemaining);

        String groupId = newGroupId();
        long fromTxId = repository.insertTransaction(user.userId(), "lend_repay", "out", amount,
                borrowerAccount.balance(), borrowerNewBalance, "lending_offer", offer.id(), offer.lender_id(),
                BigDecimal.ZERO, groupId, req.remark(), "repay lending to user " + offer.lender_id());
        long toTxId = repository.insertTransaction(offer.lender_id(), "lend_repay", "in", amount,
                lenderAccount.balance(), lenderNewBalance, "lending_offer", offer.id(), user.userId(),
                BigDecimal.ZERO, groupId, req.remark(), "repay lending from user " + user.userId());

        repository.insertAuditLog(user.userId(), "lending_repay_out", "lending_offer", offer.id(),
                amount, borrowerAccount.balance(), borrowerNewBalance, "repay lending offer " + req.offer_id(),
                user.userId(), "user", null, "success");
        repository.insertAuditLog(offer.lender_id(), "lending_repay_in", "lending_offer", offer.id(),
                amount, lenderAccount.balance(), lenderNewBalance, "received repay for offer " + req.offer_id(),
                user.userId(), "user", null, "success");

        return repository.findLendingDetailById(offer.id()).orElseThrow();
    }

    public PageResponse<LendingDetailResponse> getUserLendingDetails(CurrentUser user, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<LendingDetailResponse> items = repository.findLendingDetails(user.userId(), status, ps, offset);
        long total = repository.countLendingDetails(user.userId(), status);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public void cancelLendingOffer(CurrentUser user, long offerId) {
        LendingDetailResponse offer = repository.findLendingDetailById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("lending offer not found"));
        if (offer.user_id() != user.userId()) {
            throw new StructuredApiErrorException(403, "FORBIDDEN", "not your offer");
        }
        if (!"open".equals(offer.status())) {
            throw new IllegalArgumentException("only open offers can be cancelled");
        }
        repository.updateLendingCancel(offerId);
        repository.insertAuditLog(user.userId(), "lending_cancel", "lending_offer", offerId,
                offer.amount(), null, null, "cancelled lending offer",
                user.userId(), "user", null, "success");
    }

    // ===== New Feature: Audit Logs (10) =====

    public PageResponse<AuditLogResponse> getAuditLogs(CurrentUser user, Integer page, Integer pageSize, String action) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<AuditLogResponse> items = repository.findAuditLogs(user.userId(), ps, offset, action, null, null);
        long total = repository.countAuditLogs(user.userId(), action, null, null);
        return new PageResponse<>(items, total, p, ps);
    }

    public PageResponse<AuditLogResponse> findLendingAuditLogs(CurrentUser user, long offerId, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<AuditLogResponse> items = repository.findAuditLog(user.userId(), ps, offset, null, offerId, null, null);
        long total = repository.countAuditLogs(user.userId(), null, offerId, null, null);
        return new PageResponse<>(items, total, p, ps);
    }

    // ===== Admin Operations =====

    public FundAccountResponse getUserAccount(long userId) {
        return getOrCreateAccount(userId);
    }

    public PageResponse<FundTransactionResponse> getUserTransactions(long userId, Integer page, Integer pageSize,
                                                                      String txType, String direction,
                                                                      String startDate, String endDate) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<FundTransactionResponse> items = repository.findTransactions(userId, ps, offset, txType, direction, null, startDate, endDate);
        long total = repository.countTransactions(userId, txType, direction, null, startDate, endDate);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public CreditResponse updateCredit(long userId, AdminCreditUpdateRequest req) {
        if (req.credit_limit() == null || req.credit_limit() < 0) {
            throw new IllegalArgumentException("credit_limit must be >= 0");
        }
        BigDecimal creditLimit = decimal(req.credit_limit());
        BigDecimal interestRate = req.interest_rate() != null ? decimal(req.interest_rate()) : BigDecimal.ZERO;

        CreditResponse existing = repository.findCreditByUserId(userId).orElse(null);
        BigDecimal beforeLimit = existing != null ? existing.credit_limit() : BigDecimal.ZERO;
        if (existing == null) {
            repository.createCredit(userId);
        }
        repository.updateCredit(userId, creditLimit, interestRate);
        repository.insertAuditLog(userId, "admin_credit_update", "credit", userId,
                creditLimit, beforeLimit, creditLimit, "admin update credit",
                null, "admin", null, "success");
        return repository.findCreditByUserId(userId).orElseThrow();
    }

    @Transactional
    public FundAccountResponse adjustBalance(long userId, AdminFundAdjustRequest req, long adminId) {
        if (req.amount() == null || req.amount() == 0) {
            throw new IllegalArgumentException("amount must not be 0");
        }
        BigDecimal amount = decimal(req.amount());
        FundAccountResponse account = lockOrCreateAccount(userId);
        BigDecimal balanceBefore = account.balance();
        BigDecimal newBalance = balanceBefore.add(amount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("adjustment would result in negative balance");
        }

        repository.updateAccountBalance(userId, newBalance, account.frozen_amount());
        String direction = amount.compareTo(BigDecimal.ZERO) > 0 ? "in" : "out";
        long txId = repository.insertTransaction(userId, "admin_adjust", direction, amount.abs(),
                balanceBefore, newBalance, "admin", adminId, null,
                BigDecimal.ZERO, null, req.reason(), "admin adjustment");
        repository.insertAuditLog(adminId, "admin_balance_adjust", "account", userId,
                amount, balanceBefore, newBalance, req.reason(),
                adminId, "admin", null, "success");
        return repository.findAccountByUserId(userId).orElseThrow();
    }

    @Transactional
    public TransferResponse adminTransfer(AdminTransferRequest req, long adminId) {
        if (req.from_user_id() == null || req.to_user_id() == null) {
            throw new IllegalArgumentException("from_user_id and to_user_id are required");
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = decimal(req.amount());
        String remark = req.remark() != null ? req.remark() : "";
        String groupId = newGroupId();

        FundAccountResponse[] locked = lockTwoAccounts(req.from_user_id(), req.to_user_id());
        FundAccountResponse fromAccount = locked[0];
        FundAccountResponse toAccount = locked[1];
        BigDecimal fromAvailable = fromAccount.balance().subtract(fromAccount.frozen_amount());
        if (fromAvailable.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        BigDecimal fromNewBalance = fromAccount.balance().subtract(amount);
        BigDecimal toNewBalance = toAccount.balance().add(amount);

        repository.updateAccountBalance(req.from_user_id(), fromNewBalance, fromAccount.frozen_amount());
        repository.updateAccountBalance(req.to_user_id(), toNewBalance, toAccount.frozen_amount());
        repository.updateAccountCumulative(req.from_user_id(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, amount, BigDecimal.ZERO, BigDecimal.ZERO);
        repository.updateAccountCumulative(req.to_user_id(), BigDecimal.ZERO, BigDecimal.ZERO,
                amount, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        long fromTxId = repository.insertTransaction(req.from_user_id(), "admin_transfer", "out", amount,
                fromAccount.balance(), fromNewBalance, "admin_transfer", req.to_user_id(), req.to_user_id(),
                BigDecimal.ZERO, groupId, remark, "admin transfer to " + req.to_user_id());
        long toTxId = repository.insertTransaction(req.to_user_id(), "admin_transfer", "in", amount,
                toAccount.balance(), toNewBalance, "admin_transfer", req.from_user_id(), req.from_user_id(),
                BigDecimal.ZERO, groupId, remark, "admin transfer from " + req.from_user_id());

        repository.insertAuditLog(adminId, "admin_transfer_out", "account", req.from_user_id(),
                amount, fromAccount.balance(), fromNewBalance, remark,
                adminId, "admin", null, "success");
        repository.insertAuditLog(adminId, "admin_transfer_in", "account", req.to_user_id(),
                amount, toAccount.balance(), toNewBalance, remark,
                adminId, "admin", null, "success");

        return new TransferResponse(groupId, req.from_user_id(), req.to_user_id(), amount, BigDecimal.ZERO,
                fromNewBalance, toNewBalance, fromTxId, toTxId, null);
    }

    public PageResponse<AuditLogResponse> getAllAuditLogs(Long userId, String action, String status,
                                                          String startDate, String endDate,
                                                          Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<AuditLogResponse> items = repository.findAllAuditLogs(userId, action, status, startDate, endDate, ps, offset);
        long total = repository.countAllAuditLogs(userId, action, status, startDate, endDate);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public FreezeResponse adminFreeze(long userId, AdminFreezeRequest req, long adminId) {
        if (req.amount() == null || req.amount() <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = decimal(req.amount());
        FundAccountResponse account = lockOrCreateAccount(userId);
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        if (available.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }

        FreezeResponse freezeResult = repository.createFreeze(
                userId, amount, req.reason(), req.ref_type(), req.ref_id(), adminId);
        repository.updateAccountBalance(userId, account.balance(), account.frozen_amount().add(amount));
        repository.insertTransaction(userId, "admin_freeze", "out", amount,
                available, available.subtract(amount), "freeze", freezeResult.id(), null,
                BigDecimal.ZERO, null, "", "admin freeze: " + req.reason());
        repository.insertAuditLog(adminId, "admin_freeze", "freeze", freezeResult.id(),
                amount, available, available.subtract(amount), req.reason(),
                adminId, "admin", null, "success");
        return freezeResult;
    }

    @Transactional
    public FreezeResponse adminUnfreeze(long freezeId, String reason, long adminId) {
        FreezeResponse freezeResult = repository.findFreezeByIdAdminForUpdate(freezeId)
                .orElseThrow(() -> new IllegalArgumentException("freeze not found"));
        if (!"frozen".equals(freezeResult.status())) {
            throw new IllegalArgumentException("freeze is not in frozen status");
        }

        FundAccountResponse account = lockOrCreateAccount(freezeResult.user_id());
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        repository.updateFreezeStatus(freezeId, "unfrozen", reason);
        repository.updateAccountBalance(freezeResult.user_id(), account.balance(), account.frozen_amount().subtract(freezeResult.amount()));
        repository.insertTransaction(freezeResult.user_id(), "admin_unfreeze", "in", freezeResult.amount(),
                available, available.add(freezeResult.amount()), "freeze", freezeId, null,
                BigDecimal.ZERO, null, "", "admin unfreeze: " + reason);
        repository.insertAuditLog(adminId, "admin_unfreeze", "freeze", freezeId,
                freezeResult.amount(), available, available.add(freezeResult.amount()), reason,
                adminId, "admin", null, "success");
        return repository.findFreezeByIdAdmin(freezeId).orElseThrow();
    }

    public PageResponse<FreezeResponse> getAllFreezes(Long userId, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<FreezeResponse> items = repository.findAllFreezes(userId, status, ps, offset);
        long total = repository.countAllFreezes(userId, status);
        return new PageResponse<>(items, total, p, ps);
    }

    // ===== House Account =====

    public HouseAccountResponse getHouseAccount() {
        return repository.findHouseAccount();
    }

    public PageResponse<HouseTransactionResponse> getHouseTransactions(Long userId, String txType,
                                                                        String startDate, String endDate,
                                                                        Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<HouseTransactionResponse> items = repository.findHouseTransactions(userId, txType, startDate, endDate, ps, offset);
        long total = repository.countHouseTransactions(userId, txType, startDate, endDate);
        return new PageResponse<>(items, total, p, ps);
    }

    public HouseReportResponse getHouseReport(String startDate, String endDate) {
        return repository.getHouseReport(startDate, endDate);
    }

    @Transactional
    public void recordHouseIncome(long userId, BigDecimal amount, String txType, String refType,
                                    Long refId, String description) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        HouseAccountResponse house = lockHouseAccount();
        BigDecimal newBalance = house.balance().add(amount);
        BigDecimal newTotalIncome = house.total_income().add(amount);
        repository.updateHouseAccountBalance(newBalance, newTotalIncome, house.total_expense());
        repository.insertHouseTransaction(txType, amount, house.balance(), newBalance,
                refType, refId, userId, description);
    }

    @Transactional
    public ApiDeductResponse deductGameLoss(long userId, long amountInCents, String txType,
                                             String refType, Long refId, String description) {
        if (amountInCents <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        BigDecimal amount = BigDecimal.valueOf(amountInCents, 8);
        FundAccountResponse account = lockOrCreateAccount(userId);
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        if (available.compareTo(amount) < 0) {
            throw new IllegalArgumentException("insufficient balance");
        }

        BigDecimal newBalance = account.balance().subtract(amount);
        repository.updateAccountBalance(userId, newBalance, account.frozen_amount());
        repository.updateAccountCumulative(userId, BigDecimal.ZERO, amount,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long txId = repository.insertTransaction(userId, txType, "out", amount,
                account.balance(), newBalance, refType, refId, null,
                BigDecimal.ZERO, null, "", description != null ? description : "game/bet loss");
        repository.insertAuditLog(userId, txType, refType, refId,
                amount, account.balance(), newBalance, description,
                userId, "system", null, "success");

        recordHouseIncome(userId, amount, txType, refType, refId, description);

        BigDecimal newAvailable = newBalance.subtract(account.frozen_amount());
        return new ApiDeductResponse(txId, userId, amount, account.balance(), newBalance, newAvailable, "success", null);
    }

    @Transactional
    public void recordHouseExpense(long userId, BigDecimal amount, String txType, String refType,
                                     Long refId, String description) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        HouseAccountResponse house = lockHouseAccount();
        if (house.balance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("house account insufficient balance");
        }
        BigDecimal newBalance = house.balance().subtract(amount);
        BigDecimal newTotalExpense = house.total_expense().add(amount);
        repository.updateHouseAccountBalance(newBalance, house.total_income(), newTotalExpense);
        repository.insertHouseTransaction(txType, amount, house.balance(), newBalance,
                refType, refId, userId, description);
    }

    public FundOverviewResponse getOverview() {
        return repository.getOverview();
    }

    public PageResponse<RechargeResponse> getAllRecharges(Long userId, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<RechargeResponse> items = repository.findAllRecharges(userId, status, ps, offset);
        long total = repository.countAllRecharges(userId, status);
        return new PageResponse<>(items, total, p, ps);
    }

    public PageResponse<LendingDetailResponse> getAllLendingDetails(Long userId, String status, Integer page, Integer pageSize) {
        int p = resolvePage(page);
        int ps = resolvePageSize(pageSize);
        int offset = (p - 1) * ps;
        List<LendingDetailResponse> items = repository.findLendingDetails(userId, status, ps, offset);
        long total = repository.countLendingDetails(userId, status);
        return new PageResponse<>(items, total, p, ps);
    }

    @Transactional
    public void adminUpdateLending(long offerId, AdminLendingUpdateRequest req, long adminId) {
        LendingDetailResponse offer = repository.findLendingDetailById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("lending offer not found"));
        repository.updateLendingAdminStatus(offerId, req.status(), req.remark());
        repository.insertAuditLog(adminId, "admin_lending_update", "lending_offer", offerId,
                null, null, null, req.remark(),
                adminId, "admin", null, req.status());
    }
}
