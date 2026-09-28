package com.capacitorjs.plugins.pushnotifications.acknowledge;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.messaging.Constants;
import com.google.firebase.messaging.NotificationParams;

import java.util.Collections;
import java.util.Map;

public class NotificationDataExtractor {
    static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";

    public static Bundle getNotificationData(Intent intent) {
        String action = intent.getAction();
        if (ACTION_REMOTE_INTENT.equals(action)) {
            String messageId = intent.getStringExtra(Constants.MessagePayloadKeys.MSGID);
            String messageType = intent.getStringExtra(Constants.MessagePayloadKeys.MESSAGE_TYPE);
            if (messageType == null || messageType.equals(Constants.MessageTypes.MESSAGE)) {
                Log.i("NotificationExtractor", "is message type messageId=" + messageId);
                Bundle data = intent.getExtras();
                if (data != null && NotificationParams.isNotification(data)) {
                    return data;
                }
            }
        }

        return null;
    }
}
