package com.prizm.campaign.dto;

public class RedeemResponse {

    private String result;
    private String voucherCode;
    private Integer remainingStock;
    private FailureReason reason;
    private String message;

    public static RedeemResponse ok(String code, Integer remaining) {
        RedeemResponse r = new RedeemResponse();
        r.result = "OK";
        r.voucherCode = code;
        r.remainingStock = remaining;
        return r;
    }

    public static RedeemResponse fail(FailureReason reason, String message) {
        RedeemResponse r = new RedeemResponse();
        r.result = "FAILED";
        r.reason = reason;
        r.message = message;
        return r;
    }

    public String getResult() { return result; }
    public String getVoucherCode() { return voucherCode; }
    public Integer getRemainingStock() { return remainingStock; }
    public FailureReason getReason() { return reason; }
    public String getMessage() { return message; }
}
