package com.roguard.antivpn;

import com.roguard.RoGuard;

import java.io.*;
import java.net.*;
import java.util.concurrent.CompletableFuture;

public class VPNChecker {

    private final RoGuard plugin;

    public VPNChecker(RoGuard plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<VPNResult> checkIP(String ip) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (ip.equals("127.0.0.1") || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16."))
                    return new VPNResult(false, "Local IP", ip);

                VPNResult r1 = checkIpApi(ip);
                if (r1.isVPN()) return r1;

                VPNResult r2 = checkProxyCheck(ip);
                if (r2.isVPN()) return r2;

                VPNResult r3 = checkVpnApi(ip);
                if (r3.isVPN()) return r3;

                return new VPNResult(false, "Clean", ip);
            } catch (Exception e) {
                return new VPNResult(false, "Check failed", ip);
            }
        });
    }

    private VPNResult checkIpApi(String ip) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://ip-api.com/json/" + ip + "?fields=proxy,hosting").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            if (c.getResponseCode() == 200) {
                String json = new BufferedReader(new InputStreamReader(c.getInputStream())).lines().reduce("", String::concat);
                if (json.contains("\"proxy\":true")) return new VPNResult(true, "Proxy", ip);
                if (json.contains("\"hosting\":true")) return new VPNResult(true, "Hosting/Datacenter", ip);
            }
        } catch (Exception ignored) {}
        return new VPNResult(false, "Clean", ip);
    }

    private VPNResult checkProxyCheck(String ip) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://proxycheck.io/v2/" + ip + "?vpn=1").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            if (c.getResponseCode() == 200) {
                String json = new BufferedReader(new InputStreamReader(c.getInputStream())).lines().reduce("", String::concat);
                if (json.contains("\"proxy\":\"yes\"") || json.contains("\"type\":\"VPN\""))
                    return new VPNResult(true, "VPN/Proxy", ip);
            }
        } catch (Exception ignored) {}
        return new VPNResult(false, "Clean", ip);
    }

    private VPNResult checkVpnApi(String ip) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://vpnapi.io/api/" + ip).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(3000);
            if (c.getResponseCode() == 200) {
                String json = new BufferedReader(new InputStreamReader(c.getInputStream())).lines().reduce("", String::concat);
                if (json.contains("\"vpn\":true") || json.contains("\"proxy\":true") || json.contains("\"tor\":true"))
                    return new VPNResult(true, "VPN/Proxy/Tor", ip);
            }
        } catch (Exception ignored) {}
        return new VPNResult(false, "Clean", ip);
    }

    public static class VPNResult {
        private final boolean vpn;
        private final String reason;
        private final String ip;

        public VPNResult(boolean vpn, String reason, String ip) {
            this.vpn = vpn;
            this.reason = reason;
            this.ip = ip;
        }

        public boolean isVPN() { return vpn; }
        public String getReason() { return reason; }
        public String getIP() { return ip; }
    }
}
