package com.prizm.campaign.controller;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.service.VoucherService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/vouchers")
public class VoucherController {

    private final VoucherService voucherService;

    public VoucherController(VoucherService voucherService) {
        this.voucherService = voucherService;
    }

    /**
     * The user id comes from the verified token subject, never from the request.
     * It used to be a {@code userId} query parameter, which made the per-user
     * limit advisory: any caller could pick a fresh id and keep redeeming.
     */
    @PostMapping("/{code}/redeem")
    public RedeemResponse redeem(@PathVariable String code,
                                 @AuthenticationPrincipal Jwt jwt) {
        return voucherService.redeem(code, jwt.getSubject());
    }

    @PostMapping("/{code}/void")
    public RedeemResponse voidVoucher(@PathVariable String code) {
        return voucherService.voidVoucher(code);
    }
}
