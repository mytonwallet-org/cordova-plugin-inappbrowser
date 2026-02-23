/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.inappbrowser;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.GeolocationPermissions.Callback;
import android.webkit.PermissionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class InAppChromeClient extends WebChromeClient {

    private CordovaWebView webView;
    private String LOG_TAG = "InAppChromeClient";
    private long MAX_QUOTA = 100 * 1024 * 1024;

    // Security fix: track granted permissions per origin
    private final Map<String, Set<String>> grantedPermissionsByOrigin = new HashMap<>();

    private static final String ORIGIN_PERMISSION_CAMERA = "camera";
    private static final String ORIGIN_PERMISSION_MICROPHONE = "microphone";
    private static final String ORIGIN_PERMISSION_GEOLOCATION = "geolocation";

    public InAppChromeClient(CordovaWebView webView) {
        super();
        this.webView = webView;
    }

    /**
     * Clear all tracked per-origin permissions. Call when the InAppBrowser is closed.
     */
    public void clearPermissionState() {
        grantedPermissionsByOrigin.clear();
    }

    @Override
    public void onPermissionRequest(final PermissionRequest request) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        String origin = normalizeOrigin(request.getOrigin());
        Set<String> requestedOriginPermissions = getRequestedMediaOriginPermissions(request.getResources());
        Set<String> missingOriginPermissions = getMissingOriginPermissions(origin, requestedOriginPermissions);

        if (missingOriginPermissions.isEmpty()) {
            safeGrant(request, request.getResources());
            return;
        }

        showOriginPermissionDialog(
            origin,
            missingOriginPermissions,
            new Runnable() {
                @Override
                public void run() {
                    grantOriginPermissions(origin, requestedOriginPermissions);
                    safeGrant(request, request.getResources());
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    safeDeny(request);
                }
            }
        );
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, Callback callback) {
        super.onGeolocationPermissionsShowPrompt(origin, callback);

        String normalizedOrigin = normalizeOrigin(origin);
        if (hasOriginPermission(normalizedOrigin, ORIGIN_PERMISSION_GEOLOCATION)) {
            callback.invoke(origin, true, false);
            return;
        }

        Set<String> geoPermission = new HashSet<>();
        geoPermission.add(ORIGIN_PERMISSION_GEOLOCATION);

        showOriginPermissionDialog(
            normalizedOrigin,
            geoPermission,
            new Runnable() {
                @Override
                public void run() {
                    grantOriginPermissions(normalizedOrigin, geoPermission);
                    callback.invoke(origin, true, false);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    callback.invoke(origin, false, false);
                }
            }
        );
    }

    /**
     * Handle database quota exceeded notification.
     */
    @Override
    public void onExceededDatabaseQuota(String url, String databaseIdentifier, long currentQuota, long estimatedSize,
            long totalUsedQuota, WebStorage.QuotaUpdater quotaUpdater)
    {
        LOG.d(LOG_TAG, "onExceededDatabaseQuota estimatedSize: %d  currentQuota: %d  totalUsedQuota: %d", estimatedSize, currentQuota, totalUsedQuota);
        quotaUpdater.updateQuota(MAX_QUOTA);
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
        if (defaultValue != null && defaultValue.startsWith("gap")) {
            if(defaultValue.startsWith("gap-iab://")) {
                PluginResult scriptResult;
                String scriptCallbackId = defaultValue.substring(10);
                if (scriptCallbackId.matches("^InAppBrowser[0-9]{1,10}$")) {
                    if(message == null || message.length() == 0) {
                        scriptResult = new PluginResult(PluginResult.Status.OK, new JSONArray());
                    } else {
                        try {
                            scriptResult = new PluginResult(PluginResult.Status.OK, new JSONArray(message));
                        } catch(JSONException e) {
                            scriptResult = new PluginResult(PluginResult.Status.JSON_EXCEPTION, e.getMessage());
                        }
                    }
                    this.webView.sendPluginResult(scriptResult, scriptCallbackId);
                    result.confirm("");
                    return true;
                } else {
                    LOG.w(LOG_TAG, "InAppBrowser callback called with invalid callbackId : " + scriptCallbackId);
                    result.cancel();
                    return true;
                }
            } else {
                LOG.w(LOG_TAG, "InAppBrowser does not support Cordova API calls: " + url + " " + defaultValue);
                result.cancel();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        WebView inAppWebView = view;
        final WebViewClient webViewClient =
                new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        inAppWebView.loadUrl(request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        inAppWebView.loadUrl(url);
                        return true;
                    }
                };

        final WebView newWebView = new WebView(view.getContext());
        newWebView.setWebViewClient(webViewClient);

        final WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(newWebView);
        resultMsg.sendToTarget();

        return true;
    }

    // ---- Permission helpers ----

    private Set<String> getRequestedMediaOriginPermissions(String[] resources) {
        Set<String> permissions = new HashSet<>();
        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                permissions.add(ORIGIN_PERMISSION_CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                permissions.add(ORIGIN_PERMISSION_MICROPHONE);
            }
        }
        return permissions;
    }

    private Set<String> getMissingOriginPermissions(String origin, Set<String> requested) {
        Set<String> missing = new HashSet<>();
        for (String perm : requested) {
            if (!hasOriginPermission(origin, perm)) {
                missing.add(perm);
            }
        }
        return missing;
    }

    private boolean hasOriginPermission(String origin, String permissionKey) {
        Set<String> perms = grantedPermissionsByOrigin.get(origin);
        return perms != null && perms.contains(permissionKey);
    }

    private void grantOriginPermissions(String origin, Set<String> permissionKeys) {
        if (permissionKeys.isEmpty()) return;
        Set<String> perms = grantedPermissionsByOrigin.get(origin);
        if (perms == null) {
            perms = new HashSet<>();
            grantedPermissionsByOrigin.put(origin, perms);
        }
        perms.addAll(permissionKeys);
    }

    private void showOriginPermissionDialog(
        final String origin,
        final Set<String> requestedPermissions,
        final Runnable onAllow,
        final Runnable onDeny
    ) {
        Context ctx = webView.getView().getContext();
        Activity activity = (ctx instanceof Activity) ? (Activity) ctx : null;
        if (activity == null || activity.isFinishing()) {
            onDeny.run();
            return;
        }

        String originName = getOriginDisplayName(origin, ctx);
        String permissionList = buildPermissionLabel(requestedPermissions, ctx);
        String message = getString(ctx, "web_permission_prompt", originName, permissionList);
        String allowLabel = getString(ctx, "web_permission_allow");
        String denyLabel = getString(ctx, "web_permission_deny");

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing()) {
                    onDeny.run();
                    return;
                }
                new androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(allowLabel, (dialog, which) -> onAllow.run())
                    .setNegativeButton(denyLabel, (dialog, which) -> onDeny.run())
                    .show();
            }
        });
    }

    private String buildPermissionLabel(Set<String> requestedPermissions, Context ctx) {
        List<String> labels = new ArrayList<>();
        if (requestedPermissions.contains(ORIGIN_PERMISSION_CAMERA)) {
            labels.add(getString(ctx, "web_permission_camera"));
        }
        if (requestedPermissions.contains(ORIGIN_PERMISSION_MICROPHONE)) {
            labels.add(getString(ctx, "web_permission_microphone"));
        }
        if (requestedPermissions.contains(ORIGIN_PERMISSION_GEOLOCATION)) {
            labels.add(getString(ctx, "web_permission_location"));
        }
        if (labels.isEmpty()) {
            return getString(ctx, "web_permission_device_features");
        }
        if (labels.size() == 1) {
            return labels.get(0);
        }
        // "camera and microphone" or "camera, microphone and location"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append(i == labels.size() - 1 ? " and " : ", ");
            }
            sb.append(labels.get(i));
        }
        return sb.toString();
    }

    private String getOriginDisplayName(String origin, Context ctx) {
        if (origin == null || origin.isEmpty()) {
            return getString(ctx, "web_permission_this_site");
        }
        try {
            String host = Uri.parse(origin).getHost();
            return (host != null && !host.isEmpty()) ? host : origin;
        } catch (Exception e) {
            return origin;
        }
    }

    private String normalizeOrigin(Uri uri) {
        if (uri == null) return "";
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) return uri.toString();
        StringBuilder sb = new StringBuilder();
        sb.append(scheme.toLowerCase(Locale.US)).append("://").append(host.toLowerCase(Locale.US));
        if (uri.getPort() != -1) sb.append(":").append(uri.getPort());
        return sb.toString();
    }

    private String normalizeOrigin(String origin) {
        if (origin == null) return "";
        try {
            return normalizeOrigin(Uri.parse(origin));
        } catch (Exception e) {
            return origin;
        }
    }

    private void safeGrant(PermissionRequest request, String[] resources) {
        try {
            request.grant(resources);
        } catch (IllegalStateException e) {
            LOG.w(LOG_TAG, "Permission request already processed: " + e.getMessage());
        }
    }

    private void safeDeny(PermissionRequest request) {
        try {
            request.deny();
        } catch (IllegalStateException e) {
            LOG.w(LOG_TAG, "Permission request already processed: " + e.getMessage());
        }
    }

    // ---- String resource helpers ----

    private String getString(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
        if (id == 0) return name; // fallback to key if not found
        return ctx.getString(id);
    }

    private String getString(Context ctx, String name, Object... formatArgs) {
        int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
        if (id == 0) return name;
        return ctx.getString(id, formatArgs);
    }
}
