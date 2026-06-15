package org.apiprivaterouter.javabackend.userfund.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.apiprivaterouter.javabackend.userfund.model.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class FundRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public FundRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String fmt(OffsetDateTime dt) {
        return dt == null ? null : dt.toInstant().toString();
    }

    private OffsetDateTime getOdt(ResultSet rs, String col) throws SQLException {
        var ts = rs.getTimestamp(col);
        return ts == null ? null : OffsetDateTime.ofInstant(ts.toInstant(), ZoneOffset.UTC);
    }

    private Long getLongOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private FundAccountResponse mapAccount(ResultSet rs, int n) throws SQLException {
        var balance = rs.getBigDecimal("balance");
        var frozen = rs.getBigDecimal("frozen_amount");
        return new FundAccountResponse(
                rs.getLong("id"), rs.getString("account_type"),
                balance, frozen,
                rs.getBigDecimal("credit_limit"), rs.getBigDecimal("credit_used"),
                balance.subtract(frozen),
                nz(rs.getBigDecimal("total_recharged")),
                nz(rs.getBigDecimal("total_consumed")),
                nz(rs.getBigDecimal("total_transferred_in")),
                nz(rs.getBigDecimal("total_transferred_out")),
                nz(rs.getBigDecimal("total_loan_out")),
                nz(rs.getBigDecimal("total_loan_in")),
                rs.getString("status"), fmt(getOdt(rs, "created_at")));
    }

    private FundTransactionResponse mapTransaction(ResultSet rs, int n) throws SQLException {
        return new FundTransactionResponse(
                rs.getLong("id"), rs.getString("tx_type"), rs.getString("direction"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"), rs.getString("ref_type"),
                getLongOrNull(rs, "ref_id"), getLongOrNull(rs, "related_user_id"),
                nz(rs.getBigDecimal("fee")),
                rs.getString("group_id"), rs.getString("remark"),
                rs.getString("description"),
                fmt(getOdt(rs, "created_at")));
    }

    private FreezeResponse mapFreeze(ResultSet rs, int n) throws SQLException {
        return new FreezeResponse(
                rs.getLong("id"), rs.getLong("user_id"),
                rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getString("status"), rs.getString("ref_type"), getLongOrNull(rs, "ref_id"),
                fmt(getOdt(rs, "frozen_at")), fmt(getOdt(rs, "unfrozen_at")),
                rs.getString("unfreeze_reason"), getLongOrNull(rs, "operator_id"));
    }

    private CreditResponse mapCredit(ResultSet rs, int n) throws SQLException {
        var limit = rs.getBigDecimal("credit_limit");
        var used = rs.getBigDecimal("credit_used");
        return new CreditResponse(
                rs.getLong("id"), limit, used, limit.subtract(used),
                rs.getBigDecimal("interest_rate"), rs.getString("status"),
                fmt(getOdt(rs, "approved_at")),
                rs.getString("risk_level"),
                fmt(getOdt(rs, "next_review_at")),
                rs.getString("remark"));
    }

    private LoanResponse mapLoan(ResultSet rs, int n) throws SQLException {
        return new LoanResponse(
                rs.getLong("id"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("interest_rate"), rs.getBigDecimal("interest_amount"),
                rs.getBigDecimal("repaid_amount"), rs.getBigDecimal("remaining_amount"),
                rs.getString("status"), fmt(getOdt(rs, "due_date")),
                fmt(getOdt(rs, "repaid_at")), fmt(getOdt(rs, "created_at")));
    }

    private LendingDetailResponse mapLendingDetail(ResultSet rs, int n) throws SQLException {
        return new LendingDetailResponse(
                rs.getLong("id"), rs.getLong("user_id"),
                getLongOrNull(rs, "lender_id"), getLongOrNull(rs, "borrower_id"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("interest_rate"),
                rs.getInt("duration_days"),
                nz(rs.getBigDecimal("platform_fee")),
                nz(rs.getBigDecimal("total_repay_amount")),
                nz(rs.getBigDecimal("remaining_amount")),
                rs.getString("status"),
                fmt(getOdt(rs, "funded_at")), fmt(getOdt(rs, "due_date")),
                fmt(getOdt(rs, "repaid_at")), fmt(getOdt(rs, "cancelled_at")),
                rs.getString("remark"),
                fmt(getOdt(rs, "created_at")), fmt(getOdt(rs, "updated_at")));
    }

    private LendingOfferResponse mapLendingOffer(ResultSet rs, int n) throws SQLException {
        return new LendingOfferResponse(
                rs.getLong("id"), rs.getBigDecimal("amount"),
                rs.getBigDecimal("interest_rate"), rs.getInt("duration_days"),
                rs.getString("status"), fmt(getOdt(rs, "funded_at")),
                fmt(getOdt(rs, "created_at")));
    }

    private AuditLogResponse mapAuditLog(ResultSet rs, int n) throws SQLException {
        return new AuditLogResponse(
                rs.getLong("id"), getLongOrNull(rs, "user_id"), rs.getString("action"),
                rs.getString("target_type"), getLongOrNull(rs, "target_id"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("before_value"),
                rs.getBigDecimal("after_value"), rs.getString("description"),
                getLongOrNull(rs, "operator_id") == null ? null : String.valueOf(getLongOrNull(rs, "operator_id")),
                rs.getString("operator_role"), rs.getString("request_id"),
                rs.getString("status"),
                fmt(getOdt(rs, "created_at")));
    }

    private RechargeResponse mapRecharge(ResultSet rs, int n) throws SQLException {
        return new RechargeResponse(
                rs.getLong("id"), rs.getLong("user_id"),
                rs.getBigDecimal("amount"), nz(rs.getBigDecimal("fee")),
                rs.getString("channel"), rs.getString("external_order_id"),
                rs.getString("status"), nz(rs.getBigDecimal("balance_after")),
                fmt(getOdt(rs, "completed_at")), fmt(getOdt(rs, "created_at")));
    }

    private long insert(MapSqlParameterSource p, String sql) {
        String insertSql = sql.replaceAll(";\\s*$", "") + " RETURNING id";
        Long id = jdbc.queryForObject(insertSql, p, Long.class);
        return id != null ? id : 0L;
    }

    // ===== Account =====

    public Optional<FundAccountResponse> findAccountByUserId(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_accounts WHERE user_id = :userId", p, this::mapAccount);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Pessimistic lock: SELECT ... FOR UPDATE to prevent concurrent balance modifications.
     * Must be called within a @Transactional context.
     */
    public Optional<FundAccountResponse> findAccountByUserIdForUpdate(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_accounts WHERE user_id = :userId FOR UPDATE", p, this::mapAccount);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public FundAccountResponse createAccount(long userId) {
        var zero = BigDecimal.ZERO;
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("zero", zero);
        jdbc.update("INSERT INTO fund_accounts (user_id, account_type, balance, frozen_amount, credit_limit, credit_used, status, created_at) " +
                "VALUES (:userId, 'main', :zero, :zero, :zero, :zero, 'active', NOW()) ON CONFLICT (user_id, account_type) DO NOTHING", p);
        return findAccountByUserId(userId).orElseThrow();
    }

    public void updateAccountBalance(long userId, BigDecimal balance, BigDecimal frozenAmount) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("balance", balance).addValue("frozenAmount", frozenAmount);
        jdbc.update("UPDATE fund_accounts SET balance = :balance, frozen_amount = :frozenAmount, updated_at = NOW() WHERE user_id = :userId", p);
    }

    public void updateAccountCumulative(long userId,
                                        BigDecimal rechargedDelta, BigDecimal consumedDelta,
                                        BigDecimal transferredInDelta, BigDecimal transferredOutDelta,
                                        BigDecimal loanOutDelta, BigDecimal loanInDelta) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("r", rechargedDelta)
                .addValue("c", consumedDelta)
                .addValue("ti", transferredInDelta)
                .addValue("to", transferredOutDelta)
                .addValue("lo", loanOutDelta)
                .addValue("li", loanInDelta);
        jdbc.update("UPDATE fund_accounts SET " +
                "total_recharged = COALESCE(total_recharged,0) + :r, " +
                "total_consumed = COALESCE(total_consumed,0) + :c, " +
                "total_transferred_in = COALESCE(total_transferred_in,0) + :ti, " +
                "total_transferred_out = COALESCE(total_transferred_out,0) + :to, " +
                "total_loan_out = COALESCE(total_loan_out,0) + :lo, " +
                "total_loan_in = COALESCE(total_loan_in,0) + :li, " +
                "updated_at = NOW() WHERE user_id = :userId", p);
    }

    // ===== Transactions =====

    public long insertTransaction(long userId, String txType, String direction, BigDecimal amount,
                                   BigDecimal balanceBefore, BigDecimal balanceAfter,
                                   String refType, Long refId, Long relatedUserId,
                                   BigDecimal fee, String groupId, String remark,
                                   String description) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("txType", txType).addValue("direction", direction)
                .addValue("amount", amount).addValue("balanceBefore", balanceBefore)
                .addValue("balanceAfter", balanceAfter).addValue("refType", refType)
                .addValue("refId", refId).addValue("relatedUserId", relatedUserId)
                .addValue("fee", fee == null ? BigDecimal.ZERO : fee)
                .addValue("groupId", groupId).addValue("remark", remark == null ? "" : remark)
                .addValue("description", description);
        return jdbc.queryForObject("INSERT INTO fund_transactions (user_id, tx_type, direction, amount, balance_before, balance_after, ref_type, ref_id, related_user_id, fee, group_id, remark, description, created_at) " +
                "VALUES (:userId, :txType, :direction, :amount, :balanceBefore, :balanceAfter, :refType, :refId, :relatedUserId, :fee, :groupId, :remark, :description, NOW()) RETURNING id", p, Long.class);
    }

    public List<FundTransactionResponse> findTransactions(long userId, int limit, int offset,
                                                          String txType, String direction,
                                                          String refType, String startDate, String endDate) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_transactions WHERE user_id = :userId");
        if (txType != null && !txType.isBlank()) {
            sql.append(" AND tx_type = :txType");
            p.addValue("txType", txType);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = :direction");
            p.addValue("direction", direction);
        }
        if (refType != null && !refType.isBlank()) {
            sql.append(" AND ref_type = :refType");
            p.addValue("refType", refType);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapTransaction);
    }

    public long countTransactions(long userId, String txType, String direction,
                                  String refType, String startDate, String endDate) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_transactions WHERE user_id = :userId");
        if (txType != null && !txType.isBlank()) {
            sql.append(" AND tx_type = :txType");
            p.addValue("txType", txType);
        }
        if (direction != null && !direction.isBlank()) {
            sql.append(" AND direction = :direction");
            p.addValue("direction", direction);
        }
        if (refType != null && !refType.isBlank()) {
            sql.append(" AND ref_type = :refType");
            p.addValue("refType", refType);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    // ===== Freeze =====

    public FreezeResponse createFreeze(long userId, BigDecimal amount, String reason, String refType, Long refId, Long operatorId) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("amount", amount).addValue("reason", reason)
                .addValue("refType", refType).addValue("refId", refId).addValue("operatorId", operatorId);
        Long id = jdbc.queryForObject("INSERT INTO fund_freezes (user_id, amount, reason, status, ref_type, ref_id, operator_id, frozen_at) " +
                "VALUES (:userId, :amount, :reason, 'frozen', :refType, :refId, :operatorId, NOW()) RETURNING id", p, Long.class);
        return findFreezeById(id, userId).orElseThrow();
    }

    public Optional<FreezeResponse> findFreezeById(long freezeId, long userId) {
        var p = new MapSqlParameterSource().addValue("freezeId", freezeId).addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_freezes WHERE id = :freezeId AND user_id = :userId", p, this::mapFreeze);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<FreezeResponse> findFreezeByIdForUpdate(long freezeId, long userId) {
        var p = new MapSqlParameterSource().addValue("freezeId", freezeId).addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_freezes WHERE id = :freezeId AND user_id = :userId FOR UPDATE", p, this::mapFreeze);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<FreezeResponse> findFreezeByIdAdmin(long freezeId) {
        var p = new MapSqlParameterSource().addValue("freezeId", freezeId);
        var list = jdbc.query("SELECT * FROM fund_freezes WHERE id = :freezeId", p, this::mapFreeze);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<FreezeResponse> findFreezeByIdAdminForUpdate(long freezeId) {
        var p = new MapSqlParameterSource().addValue("freezeId", freezeId);
        var list = jdbc.query("SELECT * FROM fund_freezes WHERE id = :freezeId FOR UPDATE", p, this::mapFreeze);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateFreezeStatus(long freezeId, String status, String unfreezeReason) {
        var p = new MapSqlParameterSource()
                .addValue("freezeId", freezeId).addValue("status", status)
                .addValue("unfreezeReason", unfreezeReason == null ? "" : unfreezeReason);
        if ("unfrozen".equals(status)) {
            jdbc.update("UPDATE fund_freezes SET status = :status, unfrozen_at = NOW(), unfreeze_reason = :unfreezeReason WHERE id = :freezeId", p);
        } else {
            jdbc.update("UPDATE fund_freezes SET status = :status WHERE id = :freezeId", p);
        }
    }

    public List<FreezeResponse> findFreezes(long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_freezes WHERE user_id = :userId");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapFreeze);
    }

    public List<FreezeResponse> findAllFreezes(Long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_freezes WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapFreeze);
    }

    public long countFreezes(long userId, String status) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_freezes WHERE user_id = :userId");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    public long countAllFreezes(Long userId, String status) {
        var p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_freezes WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    // ===== Credit =====

    public Optional<CreditResponse> findCreditByUserId(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_credit WHERE user_id = :userId", p, this::mapCredit);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<CreditResponse> findCreditByUserIdForUpdate(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_credit WHERE user_id = :userId FOR UPDATE", p, this::mapCredit);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void createCredit(long userId) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("zero", BigDecimal.ZERO);
        jdbc.update("INSERT INTO fund_credit (user_id, credit_limit, credit_used, interest_rate, status) " +
                "VALUES (:userId, :zero, :zero, :zero, 'active') ON CONFLICT (user_id) DO NOTHING", p);
    }

    public void updateCredit(long userId, BigDecimal creditLimit, BigDecimal interestRate) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("creditLimit", creditLimit).addValue("interestRate", interestRate);
        jdbc.update("UPDATE fund_credit SET credit_limit = :creditLimit, interest_rate = :interestRate, updated_at = NOW() WHERE user_id = :userId", p);
    }

    public void updateCreditUsed(long userId, BigDecimal creditUsed) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("creditUsed", creditUsed);
        jdbc.update("UPDATE fund_credit SET credit_used = :creditUsed, updated_at = NOW() WHERE user_id = :userId", p);
    }

    public int adjustCreditUsed(long userId, BigDecimal delta) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("delta", delta);
        return jdbc.update("UPDATE fund_credit SET credit_used = credit_used + :delta, updated_at = NOW() WHERE user_id = :userId AND credit_used + :delta >= 0", p);
    }

    // ===== Loans =====

    public LoanResponse createLoan(long userId, BigDecimal amount, BigDecimal interestRate,
                                   BigDecimal interestAmount, Integer durationDays) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("amount", amount)
                .addValue("interestRate", interestRate).addValue("interestAmount", interestAmount)
                .addValue("remaining", amount.add(interestAmount)).addValue("days", durationDays);
        Long id = jdbc.queryForObject("INSERT INTO fund_loans (user_id, amount, interest_rate, interest_amount, repaid_amount, remaining_amount, status, due_date, created_at) " +
                "VALUES (:userId, :amount, :interestRate, :interestAmount, 0, :remaining, 'active', NOW() + make_interval(days => :days), NOW()) RETURNING id", p, Long.class);
        return findLoanById(id, userId).orElseThrow();
    }

    public Optional<LoanResponse> findLoanById(long loanId, long userId) {
        var p = new MapSqlParameterSource().addValue("loanId", loanId).addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_loans WHERE id = :loanId AND user_id = :userId", p, this::mapLoan);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateLoanRepayment(long loanId, BigDecimal repaidAmount, BigDecimal remainingAmount, String status) {
        var p = new MapSqlParameterSource()
                .addValue("loanId", loanId).addValue("repaidAmount", repaidAmount)
                .addValue("remaining", remainingAmount).addValue("status", status);
        if ("repaid".equals(status)) {
            jdbc.update("UPDATE fund_loans SET repaid_amount = :repaidAmount, remaining_amount = :remaining, status = :status, repaid_at = NOW(), updated_at = NOW() WHERE id = :loanId", p);
        } else {
            jdbc.update("UPDATE fund_loans SET repaid_amount = :repaidAmount, remaining_amount = :remaining, status = :status, updated_at = NOW() WHERE id = :loanId", p);
        }
    }

    public List<LoanResponse> findAllLoans(Long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_loans WHERE deleted_at IS NULL");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapLoan);
    }

    public long countAllLoans(Long userId, String status) {
        var p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_loans WHERE deleted_at IS NULL");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    public List<LoanResponse> findLoans(long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_loans WHERE user_id = :userId");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapLoan);
    }

    public long countLoans(long userId, String status) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_loans WHERE user_id = :userId");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    // ===== Lending Offers =====

    public LendingDetailResponse createLendingOfferFull(long userId, BigDecimal amount, BigDecimal interestRate, Integer durationDays) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("amount", amount)
                .addValue("interestRate", interestRate).addValue("durationDays", durationDays);
        Long id = jdbc.queryForObject("INSERT INTO fund_lending (user_id, amount, interest_rate, duration_days, status, created_at) " +
                "VALUES (:userId, :amount, :interestRate, :durationDays, 'open', NOW()) RETURNING id", p, Long.class);
        return findLendingDetailById(id).orElseThrow();
    }

    public Optional<LendingDetailResponse> findLendingDetailLatest(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        var list = jdbc.query("SELECT * FROM fund_lending WHERE user_id = :userId ORDER BY id DESC LIMIT 1", p, this::mapLendingDetail);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<LendingDetailResponse> findLendingDetailById(long offerId) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId);
        var list = jdbc.query("SELECT * FROM fund_lending WHERE id = :offerId", p, this::mapLendingDetail);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<LendingDetailResponse> findLendingDetailByIdForUpdate(long offerId) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId);
        var list = jdbc.query("SELECT * FROM fund_lending WHERE id = :offerId FOR UPDATE", p, this::mapLendingDetail);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int updateLendingFunded(long offerId, long lenderId, long borrowerId, BigDecimal platformFee, BigDecimal totalRepay) {
        var p = new MapSqlParameterSource()
                .addValue("offerId", offerId).addValue("lenderId", lenderId).addValue("borrowerId", borrowerId)
                .addValue("fee", platformFee).addValue("totalRepay", totalRepay);
        return jdbc.update("UPDATE fund_lending SET status = 'funded', lender_id = :lenderId, borrower_id = :borrowerId, " +
                "platform_fee = :fee, total_repay_amount = :totalRepay, remaining_amount = :totalRepay, " +
                "funded_at = NOW(), due_date = NOW() + make_interval(days => duration_days), updated_at = NOW() " +
                "WHERE id = :offerId AND status = 'open'", p);
    }

    public void updateLendingRepaid(long offerId, BigDecimal newRepaid, BigDecimal newRemaining) {
        var p = new MapSqlParameterSource()
                .addValue("offerId", offerId).addValue("repaid", newRepaid).addValue("remaining", newRemaining);
        String status = newRemaining.compareTo(BigDecimal.ZERO) <= 0 ? "repaid" : "active";
        if ("repaid".equals(status)) {
            jdbc.update("UPDATE fund_lending SET total_repay_amount = :repaid, remaining_amount = :remaining, " +
                    "status = 'repaid', repaid_at = NOW(), updated_at = NOW() WHERE id = :offerId", p);
        } else {
            jdbc.update("UPDATE fund_lending SET total_repay_amount = :repaid, remaining_amount = :remaining, " +
                    "status = 'active', updated_at = NOW() WHERE id = :offerId", p);
        }
    }

    public void updateLendingCancel(long offerId) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId);
        jdbc.update("UPDATE fund_lending SET status = 'cancelled', cancelled_at = NOW(), updated_at = NOW() " +
                "WHERE id = :offerId AND status = 'open'", p);
    }

    public void updateLendingAdminStatus(long offerId, String status, String remark) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId).addValue("status", status)
                .addValue("remark", remark == null ? "" : remark);
        jdbc.update("UPDATE fund_lending SET status = :status, remark = :remark, updated_at = NOW() WHERE id = :offerId", p);
    }

    public void updateLendingAmount(long offerId, BigDecimal amount) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId).addValue("amount", amount);
        jdbc.update("UPDATE fund_lending SET amount = :amount, updated_at = NOW() WHERE id = :offerId AND status = 'open'", p);
    }

    public void updateLendingInterestRate(long offerId, BigDecimal interestRate) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId).addValue("interestRate", interestRate);
        jdbc.update("UPDATE fund_lending SET interest_rate = :interestRate, updated_at = NOW() WHERE id = :offerId AND status = 'open'", p);
    }

    public void updateLendingDuration(long offerId, int durationDays) {
        var p = new MapSqlParameterSource().addValue("offerId", offerId).addValue("durationDays", durationDays);
        jdbc.update("UPDATE fund_lending SET duration_days = :durationDays, updated_at = NOW() WHERE id = :offerId AND status = 'open'", p);
    }

    public List<LendingOfferResponse> findLendingOffers(String status, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_lending WHERE 1=1");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapLendingOffer);
    }

    public List<LendingDetailResponse> findLendingDetails(Long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_lending WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapLendingDetail);
    }

    public long countLendingOffers(String status) {
        var p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_lending WHERE 1=1");
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    public long countLendingDetails(Long userId, String status) {
        var p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_lending WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    // ===== Audit Logs =====

    public long insertAuditLog(Long userId, String action, String targetType, Long targetId,
                               BigDecimal amount, BigDecimal beforeValue, BigDecimal afterValue,
                               String description, Long operatorId, String operatorRole,
                               String requestId, String status) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("action", action).addValue("targetType", targetType)
                .addValue("targetId", targetId).addValue("amount", amount)
                .addValue("beforeValue", beforeValue).addValue("afterValue", afterValue)
                .addValue("description", description)
                .addValue("operatorId", operatorId).addValue("operatorRole", operatorRole)
                .addValue("requestId", requestId).addValue("status", status == null ? "success" : status);
        return jdbc.queryForObject("INSERT INTO fund_audit_log (user_id, action, target_type, target_id, amount, before_value, after_value, description, operator_id, operator_role, request_id, status, created_at) " +
                "VALUES (:userId, :action, :targetType, :targetId, :amount, :beforeValue, :afterValue, :description, :operatorId, :operatorRole, :requestId, :status, NOW()) RETURNING id", p, Long.class);
    }

    public List<AuditLogResponse> findAuditLogs(long userId, int limit, int offset, String action,
                                                String startDate, String endDate) {
        return findAuditLog(userId, limit, offset, action, null, startDate, endDate);
    }

    public List<AuditLogResponse> findAuditLog(long userId, int limit, int offset, String action,
                                               Long targetId, String startDate, String endDate) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_audit_log WHERE user_id = :userId");
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            p.addValue("action", action);
        }
        if (targetId != null) {
            sql.append(" AND target_id = :targetId");
            p.addValue("targetId", targetId);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapAuditLog);
    }

    public long countAuditLogs(long userId, String action, String startDate, String endDate) {
        return countAuditLogs(userId, action, null, startDate, endDate);
    }

    public long countAuditLogs(long userId, String action, Long targetId, String startDate, String endDate) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_audit_log WHERE user_id = :userId");
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            p.addValue("action", action);
        }
        if (targetId != null) {
            sql.append(" AND target_id = :targetId");
            p.addValue("targetId", targetId);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    public List<AuditLogResponse> findAllAuditLogs(Long userId, String action, String status,
                                                   String startDate, String endDate,
                                                   int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_audit_log WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            p.addValue("action", action);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapAuditLog);
    }

    public long countAllAuditLogs(Long userId, String action, String status, String startDate, String endDate) {
        var p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_audit_log WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            p.addValue("action", action);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            p.addValue("status", status);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    // ===== Stats =====

    public FundStatsResponse getStats(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        String sql = "SELECT " +
                "COALESCE(a.balance, 0) AS total_balance, " +
                "COALESCE(a.frozen_amount, 0) AS total_frozen, " +
                "COALESCE(c.credit_limit, 0) AS total_credit_limit, " +
                "COALESCE(c.credit_used, 0) AS total_credit_used, " +
                "COALESCE(c.credit_limit, 0) - COALESCE(c.credit_used, 0) AS available_credit, " +
                "COALESCE(l.total_loan, 0) AS total_loan_amount, " +
                "COALESCE(l.total_unrepaid, 0) AS total_unrepaid " +
                "FROM fund_accounts a " +
                "LEFT JOIN fund_credit c ON c.user_id = a.user_id " +
                "LEFT JOIN (SELECT user_id, SUM(amount) AS total_loan, SUM(remaining_amount) AS total_unrepaid " +
                "    FROM fund_loans WHERE user_id = :userId AND status = 'active' GROUP BY user_id) l ON l.user_id = a.user_id " +
                "WHERE a.user_id = :userId";
        var list = jdbc.query(sql, p, (rs, n) -> new FundStatsResponse(
                rs.getBigDecimal("total_balance"), rs.getBigDecimal("total_frozen"),
                rs.getBigDecimal("total_credit_limit"), rs.getBigDecimal("total_credit_used"),
                rs.getBigDecimal("available_credit"), rs.getBigDecimal("total_loan_amount"),
                rs.getBigDecimal("total_unrepaid")));
        return list.isEmpty() ? new FundStatsResponse(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) : list.get(0);
    }

    public FundOverviewResponse getOverview() {
        var p = new MapSqlParameterSource();
        String sql = "SELECT " +
                "  (SELECT COUNT(*) FROM fund_accounts) AS total_users, " +
                "  (SELECT COALESCE(SUM(balance),0) FROM fund_accounts) AS total_balance, " +
                "  (SELECT COALESCE(SUM(frozen_amount),0) FROM fund_accounts) AS total_frozen, " +
                "  (SELECT COALESCE(SUM(credit_limit),0) FROM fund_credit) AS total_credit_limit, " +
                "  (SELECT COALESCE(SUM(credit_used),0) FROM fund_credit) AS total_credit_used, " +
                "  (SELECT COALESCE(SUM(remaining_amount),0) FROM fund_loans WHERE status = 'active') AS total_outstanding_loan, " +
                "  (SELECT COALESCE(SUM(remaining_amount),0) FROM fund_lending WHERE status IN ('active','funded')) AS total_outstanding_lend, " +
                "  (SELECT COUNT(*) FROM fund_freezes WHERE status = 'frozen' AND deleted_at IS NULL) AS active_freeze_count, " +
                "  (SELECT COUNT(*) FROM fund_loans WHERE status = 'active') AS active_loan_count, " +
                "  (SELECT COUNT(*) FROM fund_lending WHERE status IN ('open','funded','active')) AS active_lend_count, " +
                "  (SELECT COUNT(*) FROM fund_recharge_orders WHERE created_at >= date_trunc('day', NOW()) AND status = 'completed' AND deleted_at IS NULL) AS today_recharge_count, " +
                "  (SELECT COALESCE(SUM(amount),0) FROM fund_recharge_orders WHERE created_at >= date_trunc('day', NOW()) AND status = 'completed' AND deleted_at IS NULL) AS today_recharge_amount, " +
                "  (SELECT COUNT(*) FROM fund_transactions WHERE created_at >= date_trunc('day', NOW()) AND tx_type = 'transfer' AND direction = 'out') AS today_transfer_count, " +
                "  (SELECT COALESCE(SUM(amount),0) FROM fund_transactions WHERE created_at >= date_trunc('day', NOW()) AND tx_type = 'transfer' AND direction = 'out') AS today_transfer_amount, " +
                "  (SELECT COUNT(*) FROM fund_transactions WHERE created_at >= date_trunc('day', NOW()) AND tx_type = 'api_deduct') AS today_deduct_count, " +
                "  (SELECT COALESCE(SUM(amount),0) FROM fund_transactions WHERE created_at >= date_trunc('day', NOW()) AND tx_type = 'api_deduct') AS today_deduct_amount";
        var list = jdbc.query(sql, p, (rs, n) -> new FundOverviewResponse(
                rs.getLong("total_users"),
                rs.getBigDecimal("total_balance"),
                rs.getBigDecimal("total_frozen"),
                rs.getBigDecimal("total_credit_limit"),
                rs.getBigDecimal("total_credit_used"),
                rs.getBigDecimal("total_outstanding_loan"),
                rs.getBigDecimal("total_outstanding_lend"),
                rs.getLong("active_freeze_count"),
                rs.getLong("active_loan_count"),
                rs.getLong("active_lend_count"),
                rs.getLong("today_recharge_count"),
                rs.getBigDecimal("today_recharge_amount"),
                rs.getLong("today_transfer_count"),
                rs.getBigDecimal("today_transfer_amount"),
                rs.getLong("today_deduct_count"),
                rs.getBigDecimal("today_deduct_amount")));
        return list.isEmpty() ? new FundOverviewResponse(0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO) : list.get(0);
    }

    // ===== Recharge Orders =====

    public RechargeResponse createRechargeOrder(long userId, BigDecimal amount, BigDecimal fee,
                                                String channel, String externalOrderId,
                                                Long paymentOrderId, String remark) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("amount", amount)
                .addValue("fee", fee).addValue("channel", channel)
                .addValue("externalOrderId", externalOrderId)
                .addValue("paymentOrderId", paymentOrderId)
                .addValue("remark", remark == null ? "" : remark);
        Long id = jdbc.queryForObject("INSERT INTO fund_recharge_orders (user_id, amount, fee, channel, external_order_id, payment_order_id, status, remark, created_at) " +
                "VALUES (:userId, :amount, :fee, :channel, :externalOrderId, :paymentOrderId, 'pending', :remark, NOW()) RETURNING id", p, Long.class);
        return findRechargeById(id, userId).orElseThrow();
    }

    public Optional<RechargeResponse> findRechargeById(long id, long userId) {
        var p = new MapSqlParameterSource().addValue("id", id).addValue("userId", userId);
        String sql = "SELECT r.*, COALESCE((SELECT balance_after FROM fund_transactions " +
                "  WHERE user_id = r.user_id AND ref_type = 'recharge' AND ref_id = r.id ORDER BY id DESC LIMIT 1), 0) AS balance_after " +
                "FROM fund_recharge_orders r WHERE r.id = :id AND r.user_id = :userId";
        var list = jdbc.query(sql, p, this::mapRecharge);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<RechargeResponse> findRechargeByExternalOrder(String externalOrderId) {
        var p = new MapSqlParameterSource().addValue("externalOrderId", externalOrderId);
        var list = jdbc.query("SELECT *, 0 AS balance_after FROM fund_recharge_orders WHERE external_order_id = :externalOrderId",
                p, this::mapRecharge);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int completeRechargeOrder(long id, BigDecimal balanceAfter) {
        var p = new MapSqlParameterSource().addValue("id", id).addValue("balanceAfter", balanceAfter);
        return jdbc.update("UPDATE fund_recharge_orders SET status = 'completed', balance_after = :balanceAfter, completed_at = NOW(), " +
                "updated_at = NOW() WHERE id = :id AND status = 'pending'", p);
    }

    public void failRechargeOrder(long id) {
        var p = new MapSqlParameterSource().addValue("id", id);
        jdbc.update("UPDATE fund_recharge_orders SET status = 'failed', updated_at = NOW() WHERE id = :id AND status = 'pending'", p);
    }

    public List<RechargeResponse> findRecharges(long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("userId", userId).addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT r.*, COALESCE((SELECT balance_after FROM fund_transactions " +
                "  WHERE user_id = r.user_id AND ref_type = 'recharge' AND ref_id = r.id ORDER BY id DESC LIMIT 1), 0) AS balance_after " +
                "FROM fund_recharge_orders r WHERE r.user_id = :userId AND r.deleted_at IS NULL");
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY r.id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapRecharge);
    }

    public List<RechargeResponse> findAllRecharges(Long userId, String status, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT r.*, COALESCE((SELECT balance_after FROM fund_transactions " +
                "  WHERE user_id = r.user_id AND ref_type = 'recharge' AND ref_id = r.id ORDER BY id DESC LIMIT 1), 0) AS balance_after " +
                "FROM fund_recharge_orders r WHERE r.deleted_at IS NULL");
        if (userId != null) {
            sql.append(" AND r.user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = :status");
            p.addValue("status", status);
        }
        sql.append(" ORDER BY r.id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapRecharge);
    }

    public long countRecharges(long userId, String status) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        String sql = "SELECT COUNT(*) FROM fund_recharge_orders WHERE user_id = :userId AND deleted_at IS NULL" +
                (status != null && !status.isBlank() ? " AND status = :status" : "");
        if (status != null && !status.isBlank()) p.addValue("status", status);
        return jdbc.queryForObject(sql, p, Long.class);
    }

    public long countAllRecharges(Long userId, String status) {
        var p = new MapSqlParameterSource();
        String sql = "SELECT COUNT(*) FROM fund_recharge_orders WHERE deleted_at IS NULL";
        StringBuilder sb = new StringBuilder(sql);
        if (userId != null) {
            sb.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = :status");
            p.addValue("status", status);
        }
        return jdbc.queryForObject(sb.toString(), p, Long.class);
    }

    // ===== House Account =====

    private HouseAccountResponse mapHouseAccount(ResultSet rs, int n) throws SQLException {
        return new HouseAccountResponse(
                rs.getLong("id"),
                rs.getBigDecimal("balance"),
                rs.getBigDecimal("total_income"),
                rs.getBigDecimal("total_expense"),
                rs.getString("status"),
                fmt(getOdt(rs, "created_at")),
                fmt(getOdt(rs, "updated_at")));
    }

    private HouseTransactionResponse mapHouseTransaction(ResultSet rs, int n) throws SQLException {
        return new HouseTransactionResponse(
                rs.getLong("id"),
                rs.getString("tx_type"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("balance_before"),
                rs.getBigDecimal("balance_after"),
                rs.getString("ref_type"),
                getLongOrNull(rs, "ref_id"),
                getLongOrNull(rs, "user_id"),
                rs.getString("description"),
                fmt(getOdt(rs, "created_at")));
    }

    public HouseAccountResponse findHouseAccount() {
        var p = new MapSqlParameterSource();
        var list = jdbc.query("SELECT * FROM fund_house_accounts WHERE id = 1", p, this::mapHouseAccount);
        return list.isEmpty() ? createHouseAccount() : list.get(0);
    }

    /**
     * Pessimistic lock on house account: SELECT ... FOR UPDATE.
     */
    public HouseAccountResponse findHouseAccountForUpdate() {
        var p = new MapSqlParameterSource();
        var list = jdbc.query("SELECT * FROM fund_house_accounts WHERE id = 1 FOR UPDATE", p, this::mapHouseAccount);
        return list.isEmpty() ? createHouseAccount() : list.get(0);
    }

    public HouseAccountResponse createHouseAccount() {
        var p = new MapSqlParameterSource();
        jdbc.update("INSERT INTO fund_house_accounts (id, balance, total_income, total_expense, status) " +
                "VALUES (1, 0, 0, 0, 'active') ON CONFLICT (id) DO NOTHING", p);
        var list = jdbc.query("SELECT * FROM fund_house_accounts WHERE id = 1", p, this::mapHouseAccount);
        return list.get(0);
    }

    public void updateHouseAccountBalance(BigDecimal balance, BigDecimal totalIncome, BigDecimal totalExpense) {
        var p = new MapSqlParameterSource()
                .addValue("balance", balance)
                .addValue("totalIncome", totalIncome)
                .addValue("totalExpense", totalExpense);
        jdbc.update("UPDATE fund_house_accounts SET balance = :balance, total_income = :totalIncome, " +
                "total_expense = :totalExpense, updated_at = NOW() WHERE id = 1", p);
    }

    public long insertHouseTransaction(String txType, BigDecimal amount, BigDecimal balanceBefore,
                                         BigDecimal balanceAfter, String refType, Long refId,
                                         Long userId, String description) {
        var p = new MapSqlParameterSource()
                .addValue("txType", txType)
                .addValue("amount", amount)
                .addValue("balanceBefore", balanceBefore)
                .addValue("balanceAfter", balanceAfter)
                .addValue("refType", refType)
                .addValue("refId", refId)
                .addValue("userId", userId)
                .addValue("description", description);
        return jdbc.queryForObject("INSERT INTO fund_house_transactions (tx_type, amount, balance_before, balance_after, " +
                "ref_type, ref_id, user_id, description, created_at) " +
                "VALUES (:txType, :amount, :balanceBefore, :balanceAfter, :refType, :refId, :userId, :description, NOW()) RETURNING id", p, Long.class);
    }

    public List<HouseTransactionResponse> findHouseTransactions(Long userId, String txType, String startDate,
                                                                  String endDate, int limit, int offset) {
        var p = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        StringBuilder sql = new StringBuilder("SELECT * FROM fund_house_transactions WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (txType != null && !txType.isBlank()) {
            sql.append(" AND tx_type = :txType");
            p.addValue("txType", txType);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), p, this::mapHouseTransaction);
    }

    public long countHouseTransactions(Long userId, String txType, String startDate, String endDate) {
        var p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM fund_house_transactions WHERE 1=1");
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            p.addValue("userId", userId);
        }
        if (txType != null && !txType.isBlank()) {
            sql.append(" AND tx_type = :txType");
            p.addValue("txType", txType);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }
        return jdbc.queryForObject(sql.toString(), p, Long.class);
    }

    public HouseReportResponse getHouseReport(String startDate, String endDate) {
        var p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (startDate != null && !startDate.isBlank()) {
            where.append(" AND created_at >= :startDate");
            p.addValue("startDate", OffsetDateTime.parse(startDate));
        }
        if (endDate != null && !endDate.isBlank()) {
            where.append(" AND created_at <= :endDate");
            p.addValue("endDate", OffsetDateTime.parse(endDate));
        }

        String sql = "SELECT " +
                "COALESCE(SUM(CASE WHEN tx_type IN ('game_loss','bet_loss','other_income') THEN amount ELSE 0 END), 0) AS total_income, " +
                "COALESCE(SUM(CASE WHEN tx_type IN ('game_win_payout','bet_win_payout','other_expense') THEN amount ELSE 0 END), 0) AS total_expense, " +
                "COUNT(*) AS total_count, " +
                "COALESCE(SUM(CASE WHEN tx_type = 'game_loss' THEN amount ELSE 0 END), 0) AS game_loss_income, " +
                "COUNT(CASE WHEN tx_type = 'game_loss' THEN 1 END) AS game_loss_count, " +
                "COALESCE(SUM(CASE WHEN tx_type = 'bet_loss' THEN amount ELSE 0 END), 0) AS bet_loss_income, " +
                "COUNT(CASE WHEN tx_type = 'bet_loss' THEN 1 END) AS bet_loss_count, " +
                "COALESCE(SUM(CASE WHEN tx_type = 'other_income' THEN amount ELSE 0 END), 0) AS other_income_amount, " +
                "COUNT(CASE WHEN tx_type = 'other_income' THEN 1 END) AS other_income_count, " +
                "COALESCE(SUM(CASE WHEN tx_type = 'game_win_payout' THEN amount ELSE 0 END), 0) AS game_win_payout, " +
                "COUNT(CASE WHEN tx_type = 'game_win_payout' THEN 1 END) AS game_win_payout_count, " +
                "COALESCE(SUM(CASE WHEN tx_type = 'bet_win_payout' THEN amount ELSE 0 END), 0) AS bet_win_payout, " +
                "COUNT(CASE WHEN tx_type = 'bet_win_payout' THEN 1 END) AS bet_win_payout_count, " +
                "COALESCE(SUM(CASE WHEN tx_type = 'other_expense' THEN amount ELSE 0 END), 0) AS other_expense_amount, " +
                "COUNT(CASE WHEN tx_type = 'other_expense' THEN 1 END) AS other_expense_count " +
                "FROM fund_house_transactions" + where;

        return jdbc.queryForObject(sql, p, (rs, n) -> {
            BigDecimal totalIncome = rs.getBigDecimal("total_income");
            BigDecimal totalExpense = rs.getBigDecimal("total_expense");
            return new HouseReportResponse(
                    totalIncome, totalExpense, totalIncome.subtract(totalExpense),
                    rs.getLong("total_count"), rs.getLong("total_count"),
                    rs.getBigDecimal("game_loss_income"), rs.getLong("game_loss_count"),
                    rs.getBigDecimal("bet_loss_income"), rs.getLong("bet_loss_count"),
                    rs.getBigDecimal("other_income_amount"), rs.getLong("other_income_count"),
                    rs.getBigDecimal("game_win_payout"), rs.getLong("game_win_payout_count"),
                    rs.getBigDecimal("bet_win_payout"), rs.getLong("bet_win_payout_count"),
                    rs.getBigDecimal("other_expense_amount"), rs.getLong("other_expense_count"));
        });
    }

    // ===== User Lookup =====

    public Optional<Long> findUserIdByUsername(String username) {
        var p = new MapSqlParameterSource().addValue("username", username);
        var list = jdbc.query("SELECT id FROM users WHERE username = :username LIMIT 1", p,
                (rs, n) -> rs.getLong("id"));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public boolean userExists(long userId) {
        var p = new MapSqlParameterSource().addValue("userId", userId);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :userId", p, Long.class);
        return count != null && count > 0;
    }

    // ===== Idempotency =====

    /**
     * Atomically attempt to claim an idempotency key.
     * Returns true if the key was inserted (new), false if it already existed (duplicate).
     */
    public boolean claimIdempotencyKey(String scope, String idempotencyKey) {
        String hash = sha256Hex(idempotencyKey);
        var p = new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("keyHash", hash)
                .addValue("fingerprint", hash)
                .addValue("expiresAt", java.time.OffsetDateTime.now().plusHours(24));
        int affected = jdbc.update("""
                INSERT INTO idempotency_records (scope, idempotency_key_hash, request_fingerprint, status, expires_at)
                VALUES (:scope, :keyHash, :fingerprint, 'completed', :expiresAt)
                ON CONFLICT (scope, idempotency_key_hash) DO NOTHING
                """, p);
        return affected > 0;
    }

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
