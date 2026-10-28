package com.erickrosso.sementeira;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // O plugin de envio de sementes precisa ser registrado antes do super.onCreate.
        registerPlugin(NearbyPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
