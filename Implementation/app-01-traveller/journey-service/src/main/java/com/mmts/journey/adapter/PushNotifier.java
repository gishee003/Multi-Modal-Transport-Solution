package com.mmts.journey.adapter;

/**
 * Sends a push notification to the traveller's phone (Adapter pattern).
 * Production: Firebase Cloud Messaging (FCM). Demo: {@link StubFcmPushNotifier}, which only logs.
 */
public interface PushNotifier {

    void send(String userId, String title, String body);
}
