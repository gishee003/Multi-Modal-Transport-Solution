package com.mmts.identity.adapter;

/**
 * Port to the SMS provider that delivers one-time login codes (Adapter pattern).
 * The demo uses {@link StubSmsGateway}; production would use a DLT-registered Indian SMS gateway
 * (TRAI rules: registered sender id and pre-approved message template).
 */
public interface SmsGateway {

    void send(String mobile, String message);
}
