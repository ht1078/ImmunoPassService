package com.immunopass.restclient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.immunopass.model.Voucher;


@Service
public class SMSService {

    private RestTemplate restTemplate;

    @Value("${sms.endpoint}")
    private String endpoint;
    @Value("${sms.auth}")
    private String auth;

    public SMSService() {
        restTemplate = new RestTemplate();
        List<HttpMessageConverter<?>> messageConverters = new ArrayList<>();
        //Add the Jackson Message converter
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        // Note: here we are making this converter to process any kind of response,
        // not only application/*json, which is the default behaviour
        converter.setSupportedMediaTypes(Collections.singletonList(MediaType.ALL));
        messageConverters.add(converter);
        restTemplate.setMessageConverters(messageConverters);
    }

    public boolean sendOTPSMS(String userName, String to, String otp) {
        LoginOtpRequest otpRequest = LoginOtpRequest.builder()
                .otp(otp)
                .to(to)
                .userName(userName).build();
        return restExchange(otpRequest, "/v1/sms/login-otp");
    }

    public boolean sendVoucherSMS(Voucher voucher) {
        SendVoucherRequest request = SendVoucherRequest.builder()
                .to(voucher.getUserMobile())
                .userMobileNumber(voucher.getUserMobile())
                .userName(voucher.getUserName())
                .voucherCode(voucher.getVoucherCode())
                .userDOB("xx/yy/zzzz") // todo: make DOB optional.
                .build();
        return restExchange(request, "/v1/sms/send-voucher");
    }

    public boolean sendImmunoPassSMS(String to, String token, String status) {
        ImmunoPassRequest passRequest = ImmunoPassRequest.builder()
                .to(to)
                .token(token)
                .userStatus(status).build();
        return restExchange(passRequest, "/v1/sms/send-pass");
    }

    private boolean restExchange(Object request, String endpointPath) {

        HttpHeaders requestHeaders = setHTTPHeaders();
        try {
            RequestEntity requestEntity =
                    new RequestEntity(request, requestHeaders, HttpMethod.POST,
                            new URI(endpoint + endpointPath)
                    );
            ResponseEntity<SendSMSResponse> otpResponse = restTemplate.exchange(
                    requestEntity, SendSMSResponse.class);

            if (otpResponse.getStatusCode() == HttpStatus.OK) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private HttpHeaders setHTTPHeaders() {
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.set("Authentication", auth);
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        requestHeaders.setAccept(Stream.of(MediaType.ALL).collect(Collectors.toList()));
        return requestHeaders;
    }

}
