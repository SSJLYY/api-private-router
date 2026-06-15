package org.apiprivaterouter.javabackend.userfund.controller;

import jakarta.validation.Valid;

import org.apiprivaterouter.javabackend.common.api.ApiResponse;
import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUserContext;
import org.apiprivaterouter.javabackend.userfund.model.*;
import org.apiprivaterouter.javabackend.userfund.service.FundService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/fund")
public class FundController {

    private final FundService fundService;
    private final CurrentUserContext currentUserContext;

    public FundController(
            FundService fundService,
            CurrentUserContext currentUserContext
    ) {
        this.fundService = fundService;
        this.currentUserContext = currentUserContext;
    }

    @GetMapping("/account")
    public ApiResponse<FundAccountResponse> getAccount() {
        return ApiResponse.success(fundService.getAccount(currentUserContext.requireUser()));
    }

    @GetMapping("/stats")
    public ApiResponse<FundStatsResponse> getStats() {
        return ApiResponse.success(fundService.getStats(currentUserContext.requireUser()));
    }

    @GetMapping("/transactions")
    public ApiResponse<PageResponse<FundTransactionResponse>> getTransactions(
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "tx_type", required = false) String txType
    ) {
        return ApiResponse.success(fundService.getTransactions(currentUserContext.requireUser(), page, pageSize, txType));
    }

    @PostMapping("/freeze")
    public ApiResponse<FreezeResponse> freeze(
            @Valid @RequestBody FreezeRequest request
    ) {
        return ApiResponse.success(fundService.freeze(currentUserContext.requireUser(), request));
    }

    @PostMapping("/unfreeze")
    public ApiResponse<FreezeResponse> unfreeze(
            @RequestParam(name = "freeze_id") long freezeId
    ) {
        return ApiResponse.success(fundService.unfreeze(freezeId, currentUserContext.requireUser()));
    }

    @GetMapping("/freezes")
    public ApiResponse<PageResponse<FreezeResponse>> getFreezes(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getFreezes(currentUserContext.requireUser(), status, page, pageSize));
    }

    @GetMapping("/credit")
    public ApiResponse<CreditResponse> getCredit() {
        return ApiResponse.success(fundService.getCredit(currentUserContext.requireUser()));
    }

    @PostMapping("/loan")
    public ApiResponse<LoanResponse> loan(
            @Valid @RequestBody LoanRequest request
    ) {
        return ApiResponse.success(fundService.loan(currentUserContext.requireUser(), request));
    }

    @PostMapping("/repay")
    public ApiResponse<LoanResponse> repay(
            @Valid @RequestBody RepayRequest request
    ) {
        return ApiResponse.success(fundService.repay(currentUserContext.requireUser(), request));
    }

    @GetMapping("/loans")
    public ApiResponse<PageResponse<LoanResponse>> getLoans(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getLoans(currentUserContext.requireUser(), status, page, pageSize));
    }

    @PostMapping("/lending")
    public ApiResponse<LendingDetailResponse> createLendingOffer(
            @Valid @RequestBody LendingOfferRequest request
    ) {
        return ApiResponse.success(fundService.createLendingOffer(currentUserContext.requireUser(), request));
    }

    @GetMapping("/lending")
    public ApiResponse<PageResponse<LendingOfferResponse>> getLendingOffers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getLendingOffers(status, page, pageSize));
    }

    // ===== Transfer (5) =====

    @PostMapping("/transfer")
    public ApiResponse<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request
    ) {
        return ApiResponse.success(fundService.transfer(currentUserContext.requireUser(), request));
    }

    // ===== Recharge (4) =====

    @PostMapping("/recharge")
    public ApiResponse<RechargeResponse> createRecharge(
            @Valid @RequestBody RechargeRequest request
    ) {
        return ApiResponse.success(fundService.createRecharge(currentUserContext.requireUser(), request));
    }

    @GetMapping("/recharges")
    public ApiResponse<PageResponse<RechargeResponse>> getRecharges(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getRecharges(currentUserContext.requireUser(), status, page, pageSize));
    }

    @PostMapping("/recharge/{id}/complete")
    public ApiResponse<RechargeResponse> completeRecharge(
            @PathVariable long id
    ) {
        var user = currentUserContext.requireUser();
        return ApiResponse.success(fundService.completeRecharge(id, user.userId()));
    }

    // ===== P2P Lending Fund/Repay (9) =====

    @PostMapping("/lending/fund")
    public ApiResponse<LendingDetailResponse> fundLendingOffer(
            @Valid @RequestBody LendingFundRequest request
    ) {
        return ApiResponse.success(fundService.fundLendingOffer(currentUserContext.requireUser(), request));
    }

    @PostMapping("/lending/repay")
    public ApiResponse<LendingDetailResponse> repayLending(
            @Valid @RequestBody LendingRepayRequest request
    ) {
        return ApiResponse.success(fundService.repayLending(currentUserContext.requireUser(), request));
    }

    @GetMapping("/lendings")
    public ApiResponse<PageResponse<LendingDetailResponse>> getUserLendingDetails(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getUserLendingDetails(currentUserContext.requireUser(), status, page, pageSize));
    }

    @PostMapping("/lending/{id}/cancel")
    public ApiResponse<Void> cancelLendingOffer(
            @PathVariable long id
    ) {
        fundService.cancelLendingOffer(currentUserContext.requireUser(), id);
        return ApiResponse.successMessage("cancelled");
    }

    // ===== API Deduction (3) =====

    @PostMapping("/api/deduct")
    public ApiResponse<ApiDeductResponse> deductApi(
            @Valid @RequestBody ApiDeductRequest request
    ) {
        var user = currentUserContext.requireUser();
        long amountInCents = BigDecimal.valueOf(request.amount())
                .movePointRight(8).longValue();
        return ApiResponse.success(fundService.deductApiUsage(
                user.userId(), amountInCents,
                request.ref_type(), request.ref_id(), request.description()));
    }

    // ===== Credit Set (7) — Admin Only =====

    @PostMapping("/credit")
    public ApiResponse<CreditResponse> setCredit(
            @Valid @RequestBody AdminCreditUpdateRequest request
    ) {
        currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.setCredit(request.user_id(), request));
    }

    // ===== Lending My Offers (8) =====

    @GetMapping("/lending/my-offers")
    public ApiResponse<PageResponse<LendingDetailResponse>> getMyLendingOffers(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getMyLendingOffers(currentUserContext.requireUser(), status, page, pageSize));
    }

    @PutMapping("/lending/{id}")
    public ApiResponse<LendingDetailResponse> updateLendingOffer(
            @PathVariable long id,
            @Valid @RequestBody LendingOfferRequest request
    ) {
        return ApiResponse.success(fundService.updateLendingOffer(currentUserContext.requireUser(), id, request));
    }

    // ===== Audit Logs (10) =====

    @GetMapping("/audit")
    public ApiResponse<PageResponse<AuditLogResponse>> getAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize
    ) {
        return ApiResponse.success(fundService.getAuditLogs(currentUserContext.requireUser(), page, pageSize, action));
    }

    // ===== House Account (11) — Admin Only =====

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

    @PostMapping("/house/game-loss")
    public ApiResponse<ApiDeductResponse> recordGameLoss(
            @RequestParam(name = "amount") long amount,
            @RequestParam(name = "ref_type", required = false) String refType,
            @RequestParam(name = "ref_id", required = false) Long refId,
            @RequestParam(required = false) String description
    ) {
        var user = currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.deductGameLoss(
                user.userId(), amount, "game_loss",
                refType, refId, description != null ? description : "game loss"));
    }

    @PostMapping("/house/bet-loss")
    public ApiResponse<ApiDeductResponse> recordBetLoss(
            @RequestParam(name = "amount") long amount,
            @RequestParam(name = "ref_type", required = false) String refType,
            @RequestParam(name = "ref_id", required = false) Long refId,
            @RequestParam(required = false) String description
    ) {
        var user = currentUserContext.requireAdmin();
        return ApiResponse.success(fundService.deductGameLoss(
                user.userId(), amount, "bet_loss",
                refType, refId, description != null ? description : "bet loss"));
    }
}
