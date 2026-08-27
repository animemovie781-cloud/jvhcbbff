package genius.DMTech.Vectr;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import java.util.List;

/**
 * Менеджер стрима AI — запускает AiStreamService,
 * чтобы процесс не убивался при сворачивании приложения.
 * Живёт как синглтон, Activity/Fragment просто цепляют колбэк.
 *
 * Важно: bind держим на уровне Activity (не ChatFragment.onPause), иначе при уходе
 * на другую вкладку / шторку следующий ход после tool_calls не стартует.
 */
public class AiStreamManager {

    private static AiStreamManager instance;

    private AiStreamService boundService;
    private boolean serviceBound = false;
    private boolean bindRequested = false;
    private Context appContext;
    private volatile boolean appInForeground = true;

    // держим колбэк сильно, пока идёт стрим (сервис тоже, но менеджер —
    // страховка если binder на мгновение отвалится)
    private AiClient.StreamCallback activeCallback;

    /** Локальный флаг: после unbind boundService=null, но SSE может ещё идти. */
    private volatile boolean streamSessionActive = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            boundService = ((AiStreamService.LocalBinder) service).getService();
            serviceBound = true;
            bindRequested = true;
            boundService.setAppInForeground(appInForeground);

            if (pendingHistory != null) {
                AiClient.StreamCallback cb = pendingCallback != null ? pendingCallback : activeCallback;
                boundService.startStream(pendingApiKey, pendingModel, pendingSystemPrompt,
                        pendingHistory, pendingMaxTokens, pendingThinking, pendingAllowTools,
                        cb);
                clearPendingStart();
                return;
            }

            if (pendingCallback != null) {
                activeCallback = pendingCallback;
                boundService.attachCallback(pendingCallback);
                pendingCallback = null;
            } else if (activeCallback != null) {
                boundService.attachCallback(activeCallback);
            }
            if (boundService.isFinished()) {
                boundService.dismissDoneNotification();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            serviceBound = false;
            bindRequested = false;
        }
    };

    private AiClient.StreamCallback pendingCallback;
    private String pendingApiKey, pendingModel, pendingSystemPrompt;
    private List<ChatMessage> pendingHistory;
    private int pendingMaxTokens;
    private boolean pendingThinking, pendingAllowTools;

    public static synchronized AiStreamManager getInstance() {
        if (instance == null) instance = new AiStreamManager();
        return instance;
    }

    public void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public void setAppInForeground(boolean inForeground) {
        appInForeground = inForeground;
        if (boundService != null) {
            boundService.setAppInForeground(inForeground);
        }
        if (!inForeground && isStreaming() && appContext != null) {
            Intent intent = new Intent(appContext, AiStreamService.class);
            intent.putExtra("promote_foreground", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        }
    }

    public boolean isAppInForeground() {
        return appInForeground;
    }

    public void startStream(Context context, String apiKey, String model, String systemPrompt,
                            List<ChatMessage> history, int maxTokens,
                            boolean thinkingEnabled, boolean allowTools,
                            AiClient.StreamCallback cb) {
        appContext = context.getApplicationContext();
        AiClient.StreamCallback wrapped = wrapStreamCallback(cb);
        activeCallback = wrapped;
        streamSessionActive = true;

        Intent intent = new Intent(appContext, AiStreamService.class);
        // На экране — обычный startService (без FGS-нотифа).
        // В фоне — startForegroundService, иначе Android прибьёт работу.
        if (!appInForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }

        pendingApiKey = apiKey;
        pendingModel = model;
        pendingSystemPrompt = systemPrompt;
        pendingHistory = history;
        pendingMaxTokens = maxTokens;
        pendingThinking = thinkingEnabled;
        pendingAllowTools = allowTools;
        pendingCallback = wrapped;

        if (boundService != null) {
            boundService.startStream(apiKey, model, systemPrompt, history, maxTokens,
                    thinkingEnabled, allowTools, wrapped);
            clearPendingStart();
        } else {
            ensureBound();
        }
    }

    private AiClient.StreamCallback wrapStreamCallback(AiClient.StreamCallback cb) {
        return new AiClient.StreamCallback() {
            @Override
            public void onChunk(String textDelta) {
                if (cb != null) cb.onChunk(textDelta);
            }

            @Override
            public void onThinkingChunk(String thinkingDelta) {
                if (cb != null) cb.onThinkingChunk(thinkingDelta);
            }

            @Override
            public void onToolCallsReady(List<ToolCallInfo> calls) {
                streamSessionActive = false;
                if (cb != null) cb.onToolCallsReady(calls);
            }

            @Override
            public void onComplete() {
                streamSessionActive = false;
                if (cb != null) cb.onComplete();
            }

            @Override
            public void onError(String message) {
                streamSessionActive = false;
                if (cb != null) cb.onError(message);
            }
        };
    }

    public void cancelStream() {
        clearPendingStart();
        streamSessionActive = false;
        activeCallback = null;
        if (boundService != null) {
            boundService.cancelStream();
        }
        if (appContext != null) {
            try {
                appContext.stopService(new Intent(appContext, AiStreamService.class));
            } catch (Exception ignored) {}
        }
    }

    public void attachCallback(AiClient.StreamCallback cb) {
        AiClient.StreamCallback wrapped = wrapStreamCallback(cb);
        activeCallback = wrapped;
        if (boundService != null) {
            boundService.attachCallback(wrapped);
        } else {
            pendingCallback = wrapped;
            ensureBound();
        }
    }

    public void detachCallback() {
        // не затираем activeCallback на destroyView при hide/show — только отцепляем UI,
        // если фрагмент реально умирает во время стрима, attach вернёт колбэк
        if (boundService != null) {
            boundService.detachCallback();
        }
    }

    public void dismissDoneNotification() {
        if (boundService != null) {
            boundService.dismissDoneNotification();
        }
    }

    public boolean isStreaming() {
        if (boundService != null) return boundService.isStreaming();
        // unbind / пересоздание Activity — сервис может ещё стримить
        return streamSessionActive;
    }

    public boolean isFinished() {
        return boundService != null && boundService.isFinished();
    }

    public String getLastError() {
        return boundService != null ? boundService.getLastError() : null;
    }

    public void ensureBound() {
        if (appContext == null) return;
        if (serviceBound || bindRequested) return;
        Intent intent = new Intent(appContext, AiStreamService.class);
        bindRequested = true;
        try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bindRequested = false;
        }
    }

    public void unbind() {
        clearPendingStart();
        // не трогаем streamSessionActive / activeCallback — стрим может жить в сервисе
        if ((serviceBound || bindRequested) && appContext != null) {
            try {
                appContext.unbindService(connection);
            } catch (Exception ignored) {}
        }
        boundService = null;
        serviceBound = false;
        bindRequested = false;
    }

    @Deprecated
    public void unbind(Context context) {
    }

    @Deprecated
    public void bind(Context context) {
        appContext = context.getApplicationContext();
        ensureBound();
    }

    private void clearPendingStart() {
        pendingApiKey = null;
        pendingModel = null;
        pendingSystemPrompt = null;
        pendingHistory = null;
        pendingCallback = null;
    }
}
