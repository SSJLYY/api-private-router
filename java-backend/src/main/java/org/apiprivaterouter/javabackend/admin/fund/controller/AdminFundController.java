package org.apiprivaterouter.javabackend.admin.fund.controller;

import jakarta.validation.Valid;

import org.apiprivaterouter.javabackend.admin.fund.model.AdminFundAdjustRequest;
import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userfund.model.*;
import org.apiprivaterouter.javabackend.userfund.service.FundService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/admin/fund")
public class AdminFundController {

    private final FundService fundService;
    private final CurrentUserContext currentUserContext;

    public AdminFundController(
            FundService fundService,
            CurrentUserContext currentUserContext
    ) {
        this.fundService = fundService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/overview")
    public ApiResponse<FundOverviewResponse> overview() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getOverview());
    }

    @GetMapping("/users/{userId}/account")
    public ApiResponse<FundAccountResponse> getUserAccount(
            @PathVariable long userId
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getUserAccount(userId));
    }

    @GetMapping("/users/{userId}/transactions")
    public ApiResponse<PageResponse<FundTransactionResponse>> getUserTransactions(
            @PathVariable long userId,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "tx_type", required = false) String txType,
            @RequestParam(required = false) String direction,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getUserTransactions(userId, page, pageSize, txType, direction, startDate, endDate));
    }

    @PostMapping("/users/{userId}/credit")
    public ApiResponse<CreditResponse> updateCredit(
            @PathVariable long userId,
            @Valid @RequestBody AdminCreditUpdateRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.updateCredit(userId, request));
    }

    @PostMapping("/users/{userId}/adjust")
    public ApiResponse<FundAccountResponse> adjustBalance(
            @PathVariable long userId,
            @Valid @RequestBody AdminFundAdjustRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.adjustBalance(userId, request, admin.userId()));
    }

    @GetMapping("/audit")
    public ApiResponse<PageResponse<AuditLogResponse>> getAuditLogs(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllAuditLogs(userId, action, status, startDate, endDate, page, pageSize));
    }

    @GetMapping("/freezes")
    public ApiResponse<PageResponse<FreezeResponse>> getFreezes(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllFreezes(userId, status, page, pageSize));
    }

    @PostMapping("/freeze")
    public ApiResponse<FreezeResponse> adminFreeze(
            @Valid @RequestBody AdminFreezeRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.adminFreeze(request.user_id(), request, admin.userId()));
    }

    @PostMapping("/unfreeze/{id}")
    public ApiResponse<FreezeResponse> adminUnfreeze(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "admin unfreeze") String reason
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.adminUnfreeze(id, reason, admin.userId()));
    }

    @PostMapping("/transfer")
    public ApiResponse<TransferResponse> adminTransfer(
            @Valid @RequestBody AdminTransferRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.adminTransfer(request, admin.userId()));
    }

    @GetMapping("/recharges")
    public ApiResponse<PageResponse<RechargeResponse>> getRecharges(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllRecharges(userId, status, page, pageSize));
    }

    @PostMapping("/recharge/{id}/complete")
    public ApiResponse<RechargeResponse> completeRecharge(
            @PathVariable long id,
            @RequestParam long userId
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.completeRecharge(id, userId));
    }

    @GetMapping("/lendings")
    public ApiResponse<PageResponse<LendingDetailResponse>> getLendings(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllLendingDetails(userId, status, page, pageSize));
    }

    @PostMapping("/lending/{id}/update")
    public ApiResponse<Void> updateLending(
            @PathVariable long id,
            @Valid @RequestBody AdminLendingUpdateRequest request
    ) {
        CurrentUser admin = currentUserContext.requireAdmin();
        fundService.adminUpdateLending(id, request, admin.userId());
        return ApiResponse.successMessage("updated");
    }

    @GetMapping("/loans")
    public ApiResponse<PageResponse<LoanResponse>> getAllLoans(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllLoans(null, status, page, pageSize));
    }

    @GetMapping("/users/{userId}/loans")
    public ApiResponse<PageResponse<LoanResponse>> getUserLoans(
            @PathVariable long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllLoans(userId, status, page, pageSize));
    }

    @GetMapping("/users/{userId}/freezes")
    public ApiResponse<PageResponse<FreezeResponse>> getUserFreezes(
            @PathVariable long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getFreezes(new CurrentUser(userId, null, null, 0), status, page, pageSize));
    }

    @GetMapping("/users/{userId}/lendings")
    public ApiResponse<PageResponse<LendingDetailResponse>> getUserLendings(
            @PathVariable long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getAllLendingDetails(userId, status, page, pageSize));
    }

    // ===== House Account =====

    @GetMapping("/house/account")
    public ApiResponse<HouseAccountResponse> getHouseAccount() {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getHouseAccount());
    }

    @GetMapping("/house/transactions")
    public ApiResponse<PageResponse<HouseTransactionResponse>> getHouseTransactions(
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "tx_type", required = false) String txType,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getHouseTransactions(userId, txType, startDate, endDate, page, pageSize));
    }

    @GetMapping("/house/report")
    public ApiResponse<HouseReportResponse> getHouseReport(
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.getHouseReport(startDate, endDate));
    }

    @PostMapping("/house/income")
    public ApiResponse<Void> recordHouseIncome(
            @RequestParam(name = "user_id") long userId,
            @RequestParam(name = "amount") long amount,
            @RequestParam(name = "tx_type") String txType,
            @RequestParam(name = "ref_type", required = false) String refType,
            @RequestParam(name = "ref_id", required = false) Long refId,
            @RequestParam(required = false) String description
    ) {
        currentUserContext.requireAdmin();
        BigDecimal amountBd = BigDecimal.valueOf(amount, 8);
        fundService.recordHouseIncome(userId, amountBd, txType, refType, refId,
                description != null ? description : "admin income record");
        return ApiResponse.successMessage("recorded");
    }

    @PostMapping("/house/expense")
    public ApiResponse<Void> recordHouseExpense(
            @RequestParam(name = "user_id") long userId,
            @RequestParam(name = "amount") long amount,
            @RequestParam(name = "tx_type") String txType,
            @RequestParam(name = "ref_type", required = false) String refType,
            @RequestParam(name = "ref_id", required = false) Long refId,
            @RequestParam(required = false) String description
    ) {
        currentUserContext.requireAdmin();
        BigDecimal amountBd = BigDecimal.valueOf(amount, 8);
        fundService.recordHouseExpense(userId, amountBd, txType, refType, refId,
                description != null ? description : "admin expense record");
        return ApiResponse.successMessage("recorded");
    }
}
