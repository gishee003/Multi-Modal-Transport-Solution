package com.mmts.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmts.payment.common.error.ApiException;
import com.mmts.payment.domain.CardDataPolicy;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Driver D9 guard: looks at every JSON request body before it is turned into a Java object.
 * If it contains card data (a "cardNumber", "cvv", "expiry"... field, or a real-looking card number),
 * the request is refused with 400 CARD_DATA_NOT_ALLOWED, so card data is never stored or even logged.
 * (Without this, the JSON reader would silently ignore unknown fields, and the caller would never know.)
 */
@RestControllerAdvice
public class CardDataGuardAdvice extends RequestBodyAdviceAdapter {

    private final ObjectMapper objectMapper;

    public CardDataGuardAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Raw String bodies (the gateway webhook) are checked by their signature instead. */
    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType != String.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = input.getBody().readAllBytes();
        List<String> cardFields = List.of();
        try {
            JsonNode json = objectMapper.readTree(body);
            cardFields = CardDataPolicy.findCardData(json);
        } catch (IOException notJson) {
            // Not valid JSON: let the normal reader report it as a bad request.
        }
        if (!cardFields.isEmpty()) {
            throw ApiException.badRequest("CARD_DATA_NOT_ALLOWED",
                    "Card details must never be sent to MMTS; pay on the gateway checkout and send its token. "
                            + "Refused fields: " + String.join(", ", cardFields));
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
