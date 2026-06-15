package org.apiprivaterouter.javabackend.userredpacket.service;

import org.apiprivaterouter.javabackend.common.api.PageResponse;
import org.apiprivaterouter.javabackend.common.security.CurrentUser;
import org.apiprivaterouter.javabackend.userredpacket.model.ClaimRedpacketRequest;
import org.apiprivaterouter.javabackend.userredpacket.model.ClaimRedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.CreateRedpacketRequest;
import org.apiprivaterouter.javabackend.userredpacket.model.CreateRedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketDetailResponse;
import org.apiprivaterouter.javabackend.userredpacket.model.RedpacketResponse;
import org.apiprivaterouter.javabackend.userredpacket.repository.RedpacketRepository;
import org.apiprivaterouter.javabackend.userfund.model.FundAccountResponse;
import org.apiprivaterouter.javabackend.userfund.repository.FundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RedpacketService {

    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 8;
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RedpacketRepository repository;
    private final FundRepository fundRepository;

    public RedpacketService(RedpacketRepository repository, FundRepository fundRepository) {
        this.repository = repository;
        this.fundRepository = fundRepository;
    }

    private FundAccountResponse getOrCreateFundAccountForUpdate(long userId) {
        return fundRepository.findAccountByUserIdForUpdate(userId)
                .orElseGet(() -> fundRepository.createAccount(userId));
    }

    @Transactional
    public CreateRedpacketResponse createRedpacket(CurrentUser currentUser, CreateRedpacketRequest request) {
        String redpacketType = request.redpacket_type();
        if (redpacketType == null || (!redpacketType.equals("random") && !redpacketType.equals("equal"))) {
            throw new IllegalArgumentException("redpacket_type must be 'random' or 'equal'");
        }

        if (request.total_amount() == null || request.total_amount() <= 0) {
            throw new IllegalArgumentException("total_amount must be > 0");
        }
        BigDecimal totalAmount = roundMoney(BigDecimal.valueOf(request.total_amount()));

        if (request.count() == null || request.count() <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        int count = request.count();

        BigDecimal minTotal = BigDecimal.valueOf(count).multiply(new BigDecimal("0.01"));
        if (totalAmount.compareTo(minTotal) < 0) {
            throw new IllegalArgumentException("total_amount must be >= count * 0.01");
        }

        String memo = request.memo() == null ? "" : request.memo();
        if (memo.length() > 255) {
            throw new IllegalArgumentException("memo must be <= 255 characters");
        }

        OffsetDateTime expireAt = null;
        if (request.expire_at() != null && !request.expire_at().isBlank()) {
            try {
                expireAt = OffsetDateTime.parse(request.expire_at());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("invalid expire_at format");
            }
        }

        String code = generateCode();
        long userId = currentUser.userId();

        FundAccountResponse account = getOrCreateFundAccountForUpdate(userId);
        BigDecimal available = account.balance().subtract(account.frozen_amount());
        if (available.compareTo(totalAmount) < 0) {
            throw new IllegalArgumentException("insufficient balance");
        }

        BigDecimal balanceBefore = account.balance();
        BigDecimal balanceAfter = roundMoney(balanceBefore.subtract(totalAmount));
        fundRepository.updateAccountBalance(userId, balanceAfter, account.frozen_amount());
        fundRepository.insertTransaction(userId, "redpacket_create", "out",
                totalAmount, balanceBefore, balanceAfter, "redpacket", null, null,
                BigDecimal.ZERO, null, "", "Create redpacket");
        fundRepository.insertAuditLog(userId, "redpacket_create", "fund_account", userId,
                totalAmount, balanceBefore, balanceAfter,
                "Create redpacket: " + totalAmount, userId, "user", null, "success");

        RedpacketRepository.RedpacketRecord record = repository.create(
                userId,
                code,
                redpacketType,
                totalAmount,
                totalAmount,
                count,
                count,
                memo,
                expireAt
        );

        return new CreateRedpacketResponse(
                record.id(),
                record.code(),
                record.redpacketType(),
                record.totalAmount(),
                record.totalCount(),
                record.memo(),
                record.expireAt() == null ? null : record.expireAt().toInstant().toString(),
                balanceAfter,
                record.createdAt() == null ? null : record.createdAt().toInstant().toString()
        );
    }

    @Transactional
    public ClaimRedpacketResponse claimRedpacket(CurrentUser currentUser, ClaimRedpacketRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new IllegalArgumentException("code is required");
        }

        RedpacketRepository.RedpacketRecord redpacket = repository.findByCodeForUpdate(request.code().trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("redpacket not found"));

        if (redpacket.expireAt() != null && redpacket.expireAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("redpacket has expired");
        }

        if (redpacket.remainingCount() <= 0) {
            throw new IllegalArgumentException("redpacket is exhausted");
        }

        long userId = currentUser.userId();

        if (redpacket.creatorId() == userId) {
            throw new IllegalArgumentException("cannot claim your own redpacket");
        }

        if (repository.findClaim(redpacket.id(), userId).isPresent()) {
            throw new IllegalArgumentException("already claimed");
        }

        FundAccountResponse claimAccount = getOrCreateFundAccountForUpdate(userId);

        BigDecimal claimAmount = calculateClaimAmount(redpacket);
        BigDecimal balanceBefore = claimAccount.balance();
        BigDecimal balanceAfter = roundMoney(balanceBefore.add(claimAmount));

        fundRepository.updateAccountBalance(userId, balanceAfter, claimAccount.frozen_amount());
        fundRepository.insertTransaction(userId, "redpacket_claim", "in",
                claimAmount, balanceBefore, balanceAfter, "redpacket", redpacket.id(), null,
                BigDecimal.ZERO, null, "", "Claim redpacket " + redpacket.code());
        fundRepository.insertAuditLog(userId, "redpacket_claim", "fund_account", userId,
                claimAmount, balanceBefore, balanceAfter,
                "Claim redpacket " + redpacket.code() + ": " + claimAmount,
                userId, "user", null, "success");

        BigDecimal newRemaining = roundMoney(redpacket.remainingAmount().subtract(claimAmount));
        int newRemainingCount = redpacket.remainingCount() - 1;
        repository.updateRemaining(redpacket.id(), newRemaining, newRemainingCount);

        RedpacketRepository.ClaimRecord claim = repository.createClaim(
                redpacket.id(),
                userId,
                claimAmount,
                balanceBefore,
                balanceAfter
        );

        boolean isBestLuck = checkIsBestLuck(redpacket.id(), claimAmount);

        return new ClaimRedpacketResponse(
                redpacket.id(),
                redpacket.code(),
                claimAmount,
                isBestLuck,
                balanceBefore,
                balanceAfter,
                claim.createdAt() == null ? null : claim.createdAt().toInstant().toString()
        );
    }

    public RedpacketDetailResponse getRedpacketDetail(long id, long requestingUserId) {
        RedpacketRepository.RedpacketRecord redpacket = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("redpacket not found"));

        if (redpacket.creatorId() != requestingUserId) {
            throw new IllegalArgumentException("redpacket not found");
        }

        String status = computeStatus(redpacket);

        List<RedpacketDetailResponse.ClaimItem> claims = repository.listClaims(redpacket.id());

        return new RedpacketDetailResponse(
                redpacket.id(),
                redpacket.creatorId(),
                redpacket.code(),
                redpacket.redpacketType(),
                redpacket.totalAmount(),
                redpacket.remainingAmount(),
                redpacket.totalCount(),
                redpacket.remainingCount(),
                redpacket.memo(),
                redpacket.expireAt() == null ? null : redpacket.expireAt().toInstant().toString(),
                status,
                redpacket.createdAt() == null ? null : redpacket.createdAt().toInstant().toString(),
                claims
        );
    }

    public PageResponse<RedpacketResponse> listByCreator(long userId, Integer page, Integer pageSize) {
        int resolvedPage = page == null || page < 1 ? DEFAULT_PAGE : page;
        int resolvedPageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int offset = (resolvedPage - 1) * resolvedPageSize;

        List<RedpacketResponse> items = repository.listByCreator(userId, resolvedPageSize, offset);
        long total = repository.countByCreator(userId);

        List<RedpacketResponse> itemsWithStatus = new ArrayList<>();
        for (RedpacketResponse item : items) {
            String status = computeStatusFromResponse(item);
            itemsWithStatus.add(new RedpacketResponse(
                    item.id(),
                    item.creator_id(),
                    item.code(),
                    item.redpacket_type(),
                    item.total_amount(),
                    item.remaining_amount(),
                    item.total_count(),
                    item.remaining_count(),
                    item.memo(),
                    item.expire_at(),
                    status,
                    item.created_at()
            ));
        }

        return new PageResponse<>(itemsWithStatus, total, resolvedPage, resolvedPageSize);
    }

    public PageResponse<RedpacketResponse> listAll(Integer page, Integer pageSize) {
        int resolvedPage = page == null || page < 1 ? DEFAULT_PAGE : page;
        int resolvedPageSize = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int offset = (resolvedPage - 1) * resolvedPageSize;

        List<RedpacketResponse> items = repository.listAll(resolvedPageSize, offset);
        long total = repository.countAll();

        List<RedpacketResponse> itemsWithStatus = new ArrayList<>();
        for (RedpacketResponse item : items) {
            String status = computeStatusFromResponse(item);
            itemsWithStatus.add(new RedpacketResponse(
                    item.id(),
                    item.creator_id(),
                    item.code(),
                    item.redpacket_type(),
                    item.total_amount(),
                    item.remaining_amount(),
                    item.total_count(),
                    item.remaining_count(),
                    item.memo(),
                    item.expire_at(),
                    status,
                    item.created_at()
            ));
        }

        return new PageResponse<>(itemsWithStatus, total, resolvedPage, resolvedPageSize);
    }

    private BigDecimal calculateClaimAmount(RedpacketRepository.RedpacketRecord redpacket) {
        if (redpacket.redpacketType().equals("equal")) {
            return roundMoney(redpacket.totalAmount().divide(
                    BigDecimal.valueOf(redpacket.totalCount()), 8, RoundingMode.HALF_UP));
        }

        int remaining = redpacket.remainingCount();
        BigDecimal remainingAmount = redpacket.remainingAmount();

        if (remaining == 1) {
            return roundMoney(remainingAmount);
        }

        double random = ThreadLocalRandom.current().nextDouble();
        BigDecimal maxAmount = remainingAmount.subtract(
                BigDecimal.valueOf(remaining - 1).multiply(new BigDecimal("0.01")));

        if (maxAmount.compareTo(new BigDecimal("0.01")) <= 0) {
            return roundMoney(new BigDecimal("0.01"));
        }

        BigDecimal claimAmount = roundMoney(new BigDecimal("0.01").add(
                maxAmount.subtract(new BigDecimal("0.01")).multiply(BigDecimal.valueOf(random))));

        if (claimAmount.compareTo(new BigDecimal("0.01")) < 0) {
            claimAmount = new BigDecimal("0.01");
        }
        if (claimAmount.compareTo(maxAmount) > 0) {
            claimAmount = roundMoney(maxAmount);
        }

        return claimAmount;
    }

    private boolean checkIsBestLuck(long redpacketId, BigDecimal claimAmount) {
        List<RedpacketDetailResponse.ClaimItem> claims = repository.listClaims(redpacketId);
        for (RedpacketDetailResponse.ClaimItem claim : claims) {
            if (claim.amount().compareTo(claimAmount) > 0) {
                return false;
            }
        }
        return true;
    }

    private String computeStatus(RedpacketRepository.RedpacketRecord record) {
        if (record.remainingCount() <= 0) {
            return "exhausted";
        }
        if (record.expireAt() != null && record.expireAt().isBefore(OffsetDateTime.now())) {
            return "expired";
        }
        return "active";
    }

    private String computeStatusFromResponse(RedpacketResponse response) {
        if (response.remaining_count() <= 0) {
            return "exhausted";
        }
        if (response.expire_at() != null) {
            try {
                OffsetDateTime expireAt = OffsetDateTime.parse(response.expire_at());
                if (expireAt.isBefore(OffsetDateTime.now())) {
                    return "expired";
                }
            } catch (DateTimeParseException ignored) {
            }
        }
        return "active";
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(SECURE_RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private BigDecimal roundMoney(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }
}
