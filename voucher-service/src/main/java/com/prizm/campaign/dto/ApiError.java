package com.prizm.campaign.dto;

/**
 * Error body for request-level failures (404 and similar), as opposed to the
 * business rejections carried by {@link RedeemResponse}.
 */
public record ApiError(String error, String message) {
}
