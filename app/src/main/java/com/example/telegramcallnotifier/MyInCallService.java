package com.example.telegramcallnotifier;

import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

public class MyInCallService extends InCallService {

    public static Call currentCall;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        currentCall = call;
        Log.d("MyInCallService", "Call added");
        CustomExceptionHandler.log(this, "MyInCallService: Call added");
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
