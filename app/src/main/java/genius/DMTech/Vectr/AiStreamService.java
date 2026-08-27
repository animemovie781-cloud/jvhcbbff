package genius.DMTech.Vectr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;

public class AiStreamService extends Service {

    private static final String CHANNEL_ID = "ai_stream_channel";
    private static final String CHANNEL_APPROVALS = "vectr_approvals";
    private static final int NOTIF_WORKING = 1001;
    private static final int NOTIF_DONE = 1002;
    private static final int NOTIF_CMD_BASE = 2100;
    private static final int NOTIF_FILE_BASE = 2200;

    public static final String ACTION_APPROVE_CMD = "genius.DMTech.Vectr.APPROVE_CMD";
    public static final String ACTION_REJECT_CMD = "genius.DMTech.Vectr.REJECT_CMD";
    public static final String ACTION_ACCEPT_FILE = "genius.DMTech.Vectr.ACCEPT_FILE";
    public static final String ACTION_REJECT_FILE = "genius.DMTech.Vectr.REJECT_FILE";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NOTIF_ID = "notif_id";
    // unused extras kept out — notifs post via NotificationManager directly

    private final IBinder binder = new LocalBinder();
    private AiClient aiClient;
    private volatile boolean cancelled = false;
    private NotificationManager nm;

    private volatile AiClient.StreamCallback activeCallback;
    private volatile boolean finished = false;
    private volatile boolean streamingActive = false;
    private String lastErrorMessage;
    private volatile boolean appInForeground = true;
    private volatile boolean workingNotifShowing = false;

    public class LocalBinder extends Binder {
        AiStreamService getService() { return AiStreamService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannels();
    }

    @Override
    public void onDestroy() {
        streamingActive = false;
        if (!finished && !cancelled) {
            finished = true;
            AiClient.StreamCallback cb = activeCallback;
            if (cb != null) cb.onError("Сервис остановлен системой посреди работы");
        }
        activeCallback = null;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_APPROVE_CMD.equals(action)) {
            String id = intent.getStringExtra(EXTRA_REQUEST_ID);
            cancelNotif(intent.getIntExtra(EXTRA_NOTIF_ID, NOTIF_CMD_BASE));
            ApprovalBus.get().dispatchCommandApproved(id);
            return START_NOT_STICKY;
        }
        if (ACTION_REJECT_CMD.equals(action)) {
            String id = intent.getStringExtra(EXTRA_REQUEST_ID);
            cancelNotif(intent.getIntExtra(EXTRA_NOTIF_ID, NOTIF_CMD_BASE));
            ApprovalBus.get().dispatchCommandRejected(id);
            return START_NOT_STICKY;
        }
        if (ACTION_ACCEPT_FILE.equals(action)) {
            String path = intent.getStringExtra(EXTRA_PATH);
            cancelNotif(intent.getIntExtra(EXTRA_NOTIF_ID, NOTIF_FILE_BASE));
            ApprovalBus.get().dispatchFileAccepted(path);
            return START_NOT_STICKY;
        }
        if (ACTION_REJECT_FILE.equals(action)) {
            String path = intent.getStringExtra(EXTRA_PATH);
            cancelNotif(intent.getIntExtra(EXTRA_NOTIF_ID, NOTIF_FILE_BASE));
            ApprovalBus.get().dispatchFileRejected(path);
            return START_NOT_STICKY;
        }

        if (intent.getBooleanExtra("promote_foreground", false)) {
            if (isStreaming() && !appInForeground) {
                showWorkingNotification();
            }
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void setAppInForeground(boolean inForeground) {
        appInForeground = inForeground;
        if (inForeground) {
            dismissDoneNotification();
            if (isStreaming()) {
                hideWorkingNotification();
            }
        } else if (isStreaming()) {
            showWorkingNotification();
        }
    }

    public void startStream(String apiKey, String model, String systemPrompt,
                            List<ChatMessage> history, int maxTokens,
                            boolean thinkingEnabled, boolean allowTools,
                            AiClient.StreamCallback callback) {
        if (aiClient != null) {
            try { aiClient.cancel(); } catch (Exception ignored) {}
            aiClient = null;
        }

        cancelled = false;
        finished = false;
        streamingActive = true;
        lastErrorMessage = null;
        activeCallback = callback;

        if (!appInForeground) {
            showWorkingNotification();
        } else {
            hideWorkingNotification();
        }

        aiClient = AiClientFactory.create(
                AiConfig.getProvider(this),
                AiConfig.getApiBaseUrl(this));
        aiClient.streamChat(apiKey, model, systemPrompt, history, maxTokens,
                thinkingEnabled, allowTools, new AiClient.StreamCallback() {

                    @Override
                    public void onChunk(String textDelta) {
                        if (cancelled) return;
                        AiClient.StreamCallback cb = activeCallback;
                        if (cb != null) cb.onChunk(textDelta);
                    }

                    @Override
                    public void onThinkingChunk(String thinkingDelta) {
                        if (cancelled) return;
                        AiClient.StreamCallback cb = activeCallback;
                        if (cb != null) cb.onThinkingChunk(thinkingDelta);
                    }

                    @Override
                    public void onToolCallsReady(List<ToolCallInfo> calls) {
                        if (cancelled) return;
                        streamingActive = false;
                        hideWorkingNotification();
                        AiClient.StreamCallback cb = activeCallback;
                        if (cb != null) cb.onToolCallsReady(calls);
                    }

                    @Override
                    public void onComplete() {
                        if (cancelled) return;
                        streamingActive = false;
                        finished = true;
                        hideWorkingNotification();
                        if (!appInForeground && ApprovalBus.get().getPendingCommandRequestId() == null) {
                            showDoneNotification("Vectr закончил работу");
                        }
                        AiClient.StreamCallback cb = activeCallback;
                        if (cb != null) cb.onComplete();
                    }

                    @Override
                    public void onError(String message) {
                        if (cancelled) return;
                        streamingActive = false;
                        finished = true;
                        lastErrorMessage = message;
                        hideWorkingNotification();
                        if (!appInForeground) {
                            showDoneNotification("Ошибка: " + message);
                        }
                        AiClient.StreamCallback cb = activeCallback;
                        if (cb != null) cb.onError(message);
                    }
                });
    }

    public void cancelStream() {
        cancelled = true;
        streamingActive = false;
        finished = true;
        if (aiClient != null) {
            try { aiClient.cancel(); } catch (Exception ignored) {}
            aiClient = null;
        }
        activeCallback = null;
        hideWorkingNotification();
        if (nm != null) nm.cancel(NOTIF_DONE);
        stopSelf();
    }

    public boolean isStreaming() {
        return streamingActive && !cancelled && !finished;
    }

    public boolean isFinished() {
        return finished;
    }

    public String getLastError() {
        return lastErrorMessage;
    }

    public void dismissDoneNotification() {
        if (nm != null) nm.cancel(NOTIF_DONE);
    }

    public void attachCallback(AiClient.StreamCallback cb) {
        activeCallback = cb;
    }

    public void detachCallback() {
        activeCallback = null;
    }

    /** Показать нотиф подтверждения команды (без startForeground — только NM). */
    public static void postCommandApproval(Context context, String requestId,
                                           String title, String text) {
        Context app = context.getApplicationContext();
        ensureApprovalChannel(app);
        NotificationManager nm = (NotificationManager)
                app.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        int notifId = NOTIF_CMD_BASE + Math.abs(requestId != null ? requestId.hashCode() : 0) % 80;

        Intent open = new Intent(app, HomeActivity.class);
        open.putExtra("open_tab", "chat");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(app, notifId + 1, open,
                pendingFlagsStatic());

        Intent allow = new Intent(app, AiStreamService.class);
        allow.setAction(ACTION_APPROVE_CMD);
        allow.putExtra(EXTRA_REQUEST_ID, requestId);
        allow.putExtra(EXTRA_NOTIF_ID, notifId);
        PendingIntent allowPi = PendingIntent.getService(app, notifId + 2, allow, pendingFlagsStatic());

        Intent reject = new Intent(app, AiStreamService.class);
        reject.setAction(ACTION_REJECT_CMD);
        reject.putExtra(EXTRA_REQUEST_ID, requestId);
        reject.putExtra(EXTRA_NOTIF_ID, notifId);
        PendingIntent rejectPi = PendingIntent.getService(app, notifId + 3, reject, pendingFlagsStatic());

        Notification n = new NotificationCompat.Builder(app, CHANNEL_APPROVALS)
                .setContentTitle(title != null ? title : app.getString(R.string.notif_cmd_title))
                .setContentText(text != null ? text : "")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(contentPi)
                .setAutoCancel(false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .addAction(0, app.getString(R.string.notif_allow), allowPi)
                .addAction(0, app.getString(R.string.notif_reject), rejectPi)
                .build();
        nm.notify(notifId, n);
    }

    public static void postFileApproval(Context context, String path,
                                        String title, String text) {
        Context app = context.getApplicationContext();
        ensureApprovalChannel(app);
        NotificationManager nm = (NotificationManager)
                app.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        int notifId = NOTIF_FILE_BASE + Math.abs(path != null ? path.hashCode() : 0) % 80;

        Intent open = new Intent(app, HomeActivity.class);
        open.putExtra("open_tab", "chat");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(app, notifId + 1, open,
                pendingFlagsStatic());

        Intent accept = new Intent(app, AiStreamService.class);
        accept.setAction(ACTION_ACCEPT_FILE);
        accept.putExtra(EXTRA_PATH, path);
        accept.putExtra(EXTRA_NOTIF_ID, notifId);
        PendingIntent acceptPi = PendingIntent.getService(app, notifId + 2, accept, pendingFlagsStatic());

        Intent reject = new Intent(app, AiStreamService.class);
        reject.setAction(ACTION_REJECT_FILE);
        reject.putExtra(EXTRA_PATH, path);
        reject.putExtra(EXTRA_NOTIF_ID, notifId);
        PendingIntent rejectPi = PendingIntent.getService(app, notifId + 3, reject, pendingFlagsStatic());

        Notification n = new NotificationCompat.Builder(app, CHANNEL_APPROVALS)
                .setContentTitle(title != null ? title : app.getString(R.string.notif_file_title))
                .setContentText(text != null ? text : path)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(contentPi)
                .setAutoCancel(false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .addAction(0, app.getString(R.string.notif_accept), acceptPi)
                .addAction(0, app.getString(R.string.notif_reject), rejectPi)
                .build();
        nm.notify(notifId, n);
    }

    public static void cancelFileApproval(Context context, String path) {
        int notifId = NOTIF_FILE_BASE + Math.abs(path != null ? path.hashCode() : 0) % 80;
        NotificationManager nm = (NotificationManager)
                context.getApplicationContext().getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId);
    }

    public static void cancelCommandApproval(Context context, String requestId) {
        int notifId = NOTIF_CMD_BASE + Math.abs(requestId != null ? requestId.hashCode() : 0) % 80;
        NotificationManager nm = (NotificationManager)
                context.getApplicationContext().getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId);
    }

    private static void ensureApprovalChannel(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                app.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel appr = new NotificationChannel(
                CHANNEL_APPROVALS,
                app.getString(R.string.notif_channel_approvals),
                NotificationManager.IMPORTANCE_HIGH);
        appr.setDescription(app.getString(R.string.notif_channel_approvals_desc));
        appr.setShowBadge(true);
        nm.createNotificationChannel(appr);
    }

    private static int pendingFlagsStatic() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void cancelNotif(int id) {
        if (nm != null) nm.cancel(id);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AI-стрим",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Уведомления о работе AI");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            ensureApprovalChannel(this);
        }
    }

    private Notification buildNotification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Vectr")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void showWorkingNotification() {
        Notification n = buildNotification("Vectr работает в фоне", true);
        if (!workingNotifShowing) {
            startForeground(NOTIF_WORKING, n);
            workingNotifShowing = true;
        } else {
            nm.notify(NOTIF_WORKING, n);
        }
    }

    private void hideWorkingNotification() {
        if (workingNotifShowing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            workingNotifShowing = false;
        }
        if (nm != null) nm.cancel(NOTIF_WORKING);
    }

    private void showDoneNotification(String text) {
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Vectr")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(NOTIF_DONE, notif);
    }
}
