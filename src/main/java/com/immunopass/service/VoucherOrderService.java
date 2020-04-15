package com.immunopass.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import com.immunopass.controller.VoucherOrderController;
import com.immunopass.entity.OrganizationEntity;
import com.immunopass.entity.VoucherEntity;
import com.immunopass.entity.VoucherOrderEntity;
import com.immunopass.enums.EntityStatus;
import com.immunopass.enums.IDType;
import com.immunopass.enums.OrderStatus;
import com.immunopass.enums.VoucherStatus;
import com.immunopass.mapper.VoucherMapper;
import com.immunopass.model.Account;
import com.immunopass.repository.OrganizationRepository;
import com.immunopass.repository.VoucherOrderRepository;
import com.immunopass.repository.VoucherRepository;
import com.immunopass.restclient.SMSService;
import com.immunopass.util.S3Util;


@Service
public class VoucherOrderService implements VoucherOrderController {
    private final VoucherOrderRepository voucherOrderRepository;
    private final OrganizationRepository organizationRepository;
    private final VoucherRepository voucherRepository;
    private final SMSService smsService;
    private final S3Util s3Utill;

    private static final int NAME_INDEX = 0;
    private static final int MOBILE_NUMBER_INDEX = 1;
    private static final int ID_CARD_TYPE_INDEX = 2;
    private static final int ID_CARD_NUMBER_INDEX = 3;
    private static final int EMP_ID_INDEX = 4;

    public VoucherOrderService(VoucherOrderRepository voucherOrderRepository,
            OrganizationRepository organizationRepository,
            VoucherRepository voucherRepository,
            SMSService smsService,
            S3Util s3Utill) {
        this.voucherOrderRepository = voucherOrderRepository;
        this.organizationRepository = organizationRepository;
        this.voucherRepository = voucherRepository;
        this.smsService = smsService;
        this.s3Utill = s3Utill;
    }

    @Override
    public void createVoucherOrder(MultipartFile file) {
        Account account = (Account) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        if (account.getOrganizationId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User account isn't linked to any organization.");
        }
        OrganizationEntity organizationEntity =
                organizationRepository
                        .findById(account.getOrganizationId())
                        .filter(organizationEntity1 -> organizationEntity1.getStatus() == EntityStatus.ACTIVE)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.FORBIDDEN,
                                "User account isn't linked to any active organization."));
        List<String> csvRecords;
        try (BufferedReader br =
                new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            csvRecords = br.lines().skip(1).map(this::validateCsvRecord).collect(Collectors.toList());
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Error reading the file: " + e.getLocalizedMessage());
        }
        if (csvRecords.size() > organizationEntity.getTotalVouchers() - organizationEntity.getAllotedVouchers()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The number of records present in the CSV file is greater than the available vouchers to the "
                            + "organization.");
        }
        URL s3URL;
        try {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                    String.join("\n", csvRecords).getBytes(StandardCharsets.UTF_8));
            s3URL = s3Utill.uploadDocumentSync(
                    byteArrayInputStream,
                    "text/csv",
                    String.format("voucher_order_%s.csv", UUID.randomUUID().toString()));
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error uploading the file to the server.");
        }
        VoucherOrderEntity voucherOrderEntity = VoucherOrderEntity.builder()
                .status(OrderStatus.CREATED)
                .uploadedFile(s3URL.toString())
                .voucherCount(csvRecords.size())
                .uploadedFile(s3URL.toString())
                .createdBy(account.getId())
                .build();
        organizationRepository.save(organizationEntity);
        organizationEntity.setAllotedVouchers(organizationEntity.getAllotedVouchers() + csvRecords.size());
        voucherOrderRepository.save(voucherOrderEntity);
    }

    private String validateCsvRecord(String record) {
        String[] fields = record.split(",");
        String name = fields[NAME_INDEX];
        String mobileNumber = fields[MOBILE_NUMBER_INDEX];
        String idCardType = fields[ID_CARD_TYPE_INDEX];
        String idCardNumber = fields[ID_CARD_NUMBER_INDEX];
        String employeeId = fields[EMP_ID_INDEX];

        // Validate name
        name = name.replaceAll("[^a-zA-Z ]", "").trim();
        if (StringUtils.isEmpty(name) || name.length() > 40) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Name is invalid.");
        }
        fields[NAME_INDEX] = name;

        // Validate mobile phone number
        mobileNumber = mobileNumber.replaceAll("[^0-9]", "").trim();
        if (mobileNumber.length() != 10) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Mobile phone number is invalid.");
        }
        fields[MOBILE_NUMBER_INDEX] = mobileNumber;

        // Validate Id Card Type
        idCardType = StringUtils.trim(idCardType);
        if (!EnumUtils.isValidEnum(IDType.class, idCardType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Id Card Type.");
        }
        fields[ID_CARD_TYPE_INDEX] = idCardType;

        // Validate Id Card Number
        idCardNumber = StringUtils.trim(idCardNumber);
        if (idCardNumber.length() == 0 || idCardNumber.length() > 40) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Id Card Number.");
        }
        fields[ID_CARD_NUMBER_INDEX] = idCardNumber;

        // Validate Employee Id
        employeeId = StringUtils.trim(employeeId);
        if (employeeId.length() == 0 || employeeId.length() > 40) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Employee Id.");
        }
        fields[EMP_ID_INDEX] = employeeId;
        return StringUtils.join(fields, ",");
    }

    public void createVouchers() {
        voucherOrderRepository
                .findAllByStatus(OrderStatus.CREATED)
                .forEach(this::createVouchers);
    }

    private void createVouchers(VoucherOrderEntity voucherOrderEntity) {
        s3Utill.getRecords(voucherOrderEntity.getUploadedFile())
                .forEach(record -> {
                    String[] fields = record.split(",");
                    VoucherEntity voucherEntity = VoucherEntity.builder()
                            //TODO: Make sure that voucher code is unique
                            .voucherCode(RandomStringUtils.randomAlphabetic(8))
                            .orderId(voucherOrderEntity.getId())
                            .userName(fields[NAME_INDEX])
                            .userMobile(fields[MOBILE_NUMBER_INDEX])
                            .userGovtIdType(IDType.valueOf(fields[ID_CARD_TYPE_INDEX]))
                            .userGovernmentId(fields[ID_CARD_NUMBER_INDEX])
                            .userEmpId(fields[EMP_ID_INDEX])
                            .status(VoucherStatus.ALLOTTED)
                            //TODO: Issuer should be the organization.
                            .issuerId(voucherOrderEntity.getCreatedBy())
                            .build();
                    voucherRepository.save(voucherEntity);
                });
        voucherOrderEntity.setStatus(OrderStatus.PROCESSING);
        voucherOrderRepository.save(voucherOrderEntity);
    }

    public void processOrders() {
        voucherOrderRepository
                .findAllByStatus(OrderStatus.PROCESSING)
                .forEach(this::processOrder);
    }

    private void processOrder(VoucherOrderEntity voucherOrderEntity) {
        List<VoucherEntity> vouchers = voucherRepository.findAllByOrderId(voucherOrderEntity.getId());
        boolean failure = false;
        for (VoucherEntity voucherEntity : vouchers) {
            if (voucherEntity.getStatus() == VoucherStatus.ALLOTTED) {
                try {
                    if (smsService.sendVoucherSMS(VoucherMapper.map(voucherEntity))) {
                        voucherEntity.setStatus(VoucherStatus.PROCESSED);
                        voucherRepository.save(voucherEntity);
                    } else {
                        failure = true;
                        voucherEntity.setRetryCount(voucherEntity.getRetryCount() + 1);
                        voucherEntity.setLastFailureReason("Failed to send sms");
                        voucherRepository.save(voucherEntity);
                    }
                } catch (Exception e) {
                    failure = true;
                    voucherEntity.setRetryCount(voucherEntity.getRetryCount() + 1);
                    voucherEntity.setLastFailureReason(e.getLocalizedMessage());
                    voucherRepository.save(voucherEntity);
                }
            }
        }
        if (!failure) {
            voucherOrderEntity.setStatus(OrderStatus.PROCESSED);
            voucherOrderRepository.save(voucherOrderEntity);
        }
    }

}
