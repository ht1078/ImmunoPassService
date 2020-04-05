package com.immunopass.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.immunopass.model.Voucher;


@RestController
@RequestMapping("/v1/vouchers")
public interface VoucherController {

    @GetMapping("/{id}")
    public Voucher getVoucher(@PathVariable Long id);

    @GetMapping("/{id}/process")
    public void processVoucher(@PathVariable Long id, @RequestParam("action") String action);
}