package com.example.telegramcallnotifier;

import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;
import android.content.Intent;

public class MyInCallService extends InCallService {

    public static Call currentCall;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        currentCall = call;
        Log.d("MyInCallService", "Call added");
        CustomExceptionHandler.log(this, "MyInCallService: Call added");

        Intent intent = new Intent(this, InCallActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (currentCall == call) {
            currentCall = null;
        }
        Log.d("MyInCallService", "Call removed");
        CustomExceptionHandler.log(this, "MyInCallService: Call removed");
    }
}
