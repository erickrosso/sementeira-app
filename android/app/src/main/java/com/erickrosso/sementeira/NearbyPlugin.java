package com.erickrosso.sementeira;

import android.Manifest;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;

/**
 * Envio de sementes entre celulares por Bluetooth/Wi-Fi direto (Google Nearby Connections).
 *
 * Fluxo:
 *  - Quem vai RECEBER chama receive()  -> o aparelho fica visível ("anunciando").
 *  - Quem vai ENVIAR chama discover()  -> recebe eventos "found"/"lost" com os aparelhos próximos,
 *    e depois send({ id, seed }) com o aparelho escolhido.
 *  - Eventos para o JS: "found", "lost", "received", "sent", "error", "state".
 */
@CapacitorPlugin(
    name = "Nearby",
    permissions = {
        @Permission(alias = "loc", strings = { Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(
            alias = "bt",
            strings = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            }
        ),
        @Permission(alias = "wifi", strings = { Manifest.permission.NEARBY_WIFI_DEVICES })
    }
)
public class NearbyPlugin extends Plugin {

    private static final String SERVICE_ID = "com.erickrosso.sementeira.seeds";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private ConnectionsClient client;
    private boolean advertising = false;
    private boolean discovering = false;

    /** Semente (JSON) aguardando para ser enviada ao aparelho escolhido. */
    private String pendingSeed = null;
    private String pendingEndpoint = null;
    private long sentPayloadId = -1;

    private ConnectionsClient client() {
        if (client == null) {
            client = Nearby.getConnectionsClient(getContext());
        }
        return client;
    }

    private String deviceName() {
        String m = Build.MODEL == null ? "Android" : Build.MODEL;
        return m.length() > 30 ? m.substring(0, 30) : m;
    }

    /* ---------- permissões (variam conforme a versão do Android) ---------- */

    private String[] neededAliases() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] { "bt", "wifi" };
        } else if (Build.VERSION.SDK_INT >= 31) {
            return new String[] { "bt", "loc" };
        }
        return new String[] { "loc" };
    }

    private boolean hasPermissions() {
        for (String a : neededAliases()) {
            if (getPermissionState(a) != PermissionState.GRANTED) {
                return false;
            }
        }
        return true;
    }

    @PluginMethod
    public void receive(PluginCall call) {
        if (!hasPermissions()) {
            requestPermissionForAliases(neededAliases(), call, "receivePermsDone");
            return;
        }
        startAdvertisingNow(call);
    }

    @PermissionCallback
    private void receivePermsDone(PluginCall call) {
        if (hasPermissions()) {
            startAdvertisingNow(call);
        } else {
            call.reject("permissao_negada");
        }
    }

    @PluginMethod
    public void discover(PluginCall call) {
        if (!hasPermissions()) {
            requestPermissionForAliases(neededAliases(), call, "discoverPermsDone");
            return;
        }
        startDiscoveryNow(call);
    }

    @PermissionCallback
    private void discoverPermsDone(PluginCall call) {
        if (hasPermissions()) {
            startDiscoveryNow(call);
        } else {
            call.reject("permissao_negada");
        }
    }

    /* ---------- receber (anunciar) ---------- */

    private void startAdvertisingNow(final PluginCall call) {
        stopEverything();
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        client()
            .startAdvertising(deviceName(), SERVICE_ID, lifecycle, options)
            .addOnSuccessListener(unused -> {
                advertising = true;
                emitState("receiving");
                call.resolve();
            })
            .addOnFailureListener(e -> call.reject("falha_anunciar: " + e.getMessage()));
    }

    /* ---------- enviar (procurar) ---------- */

    private void startDiscoveryNow(final PluginCall call) {
        stopEverything();
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        client()
            .startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener(unused -> {
                discovering = true;
                emitState("discovering");
                call.resolve();
            })
            .addOnFailureListener(e -> call.reject("falha_procurar: " + e.getMessage()));
    }

    @PluginMethod
    public void send(PluginCall call) {
        final String id = call.getString("id");
        final String seed = call.getString("seed");
        if (id == null || seed == null || seed.isEmpty()) {
            call.reject("parametros_invalidos");
            return;
        }
        pendingSeed = seed;
        pendingEndpoint = id;
        sentPayloadId = -1;
        client()
            .requestConnection(deviceName(), id, lifecycle)
            .addOnSuccessListener(unused -> call.resolve())
            .addOnFailureListener(e -> {
                pendingSeed = null;
                pendingEndpoint = null;
                call.reject("falha_conectar: " + e.getMessage());
            });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopEverything();
        emitState("idle");
        call.resolve();
    }

    private void stopEverything() {
        try {
            if (advertising) {
                client().stopAdvertising();
            }
            if (discovering) {
                client().stopDiscovery();
            }
            if (client != null) {
                client.stopAllEndpoints();
            }
        } catch (Exception ignored) {
            // nada a fazer: já estava parado
        }
        advertising = false;
        discovering = false;
        pendingSeed = null;
        pendingEndpoint = null;
        sentPayloadId = -1;
    }

    @Override
    protected void handleOnDestroy() {
        stopEverything();
        super.handleOnDestroy();
    }

    /* ---------- eventos para o JS ---------- */

    private void emitState(String s) {
        JSObject o = new JSObject();
        o.put("state", s);
        notifyListeners("state", o);
    }

    private void emitError(String msg) {
        JSObject o = new JSObject();
        o.put("message", msg);
        notifyListeners("error", o);
    }

    /* ---------- callbacks do Nearby ---------- */

    private final EndpointDiscoveryCallback discoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            JSObject o = new JSObject();
            o.put("id", endpointId);
            o.put("name", info.getEndpointName());
            notifyListeners("found", o);
        }

        @Override
        public void onEndpointLost(String endpointId) {
            JSObject o = new JSObject();
            o.put("id", endpointId);
            notifyListeners("lost", o);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            // Os dois lados aceitam automaticamente: o que trafega é só uma semente.
            client().acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            int code = result.getStatus().getStatusCode();
            if (code == ConnectionsStatusCodes.STATUS_OK) {
                if (pendingSeed != null && endpointId.equals(pendingEndpoint)) {
                    Payload p = Payload.fromBytes(pendingSeed.getBytes(StandardCharsets.UTF_8));
                    sentPayloadId = p.getId();
                    client().sendPayload(endpointId, p);
                }
            } else if (endpointId.equals(pendingEndpoint)) {
                pendingSeed = null;
                pendingEndpoint = null;
                emitError("conexao_recusada");
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            if (endpointId.equals(pendingEndpoint) && pendingSeed != null) {
                // caiu antes de confirmar o envio
                pendingSeed = null;
                pendingEndpoint = null;
                emitError("desconectou");
            }
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) {
                return;
            }
            byte[] bytes = payload.asBytes();
            if (bytes == null || bytes.length == 0 || bytes.length > 16384) {
                return;
            }
            JSObject o = new JSObject();
            o.put("seed", new String(bytes, StandardCharsets.UTF_8));
            notifyListeners("received", o);
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            if (update.getPayloadId() == sentPayloadId && update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                sentPayloadId = -1;
                pendingSeed = null;
                pendingEndpoint = null;
                // A desconexão fica por conta do stop() chamado pelo JS (com pequeno atraso).
                notifyListeners("sent", new JSObject());
            } else if (update.getPayloadId() == sentPayloadId && update.getStatus() == PayloadTransferUpdate.Status.FAILURE) {
                sentPayloadId = -1;
                pendingSeed = null;
                pendingEndpoint = null;
                emitError("envio_falhou");
            }
        }
    };
}
