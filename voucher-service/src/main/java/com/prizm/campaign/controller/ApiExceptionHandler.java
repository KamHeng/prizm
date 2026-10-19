package com.prizm.campaign.controller;

import com.prizm.campaign.dto.ApiError;
import com.prizm.campaign.service.CampaignNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CampaignNotFoundException.class)
    public ResponseEntity<ApiError> handleCampaignNotFound(CampaignNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("CAMPAIGN_NOT_FOUND", e.getMessage()));
    }
}
