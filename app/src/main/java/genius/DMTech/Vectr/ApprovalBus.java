package genius.DMTech.Vectr;

/**
 * Мост между action-кнопками нотификаций (AiStreamService) и ChatFragment.
 */
public final class ApprovalBus {

    public interface Listener {
        void onCommandApproved(String requestId);
        void onCommandRejected(String requestId);
        void onFileAccepted(String path);
        void onFileRejected(String path);
    }

    private static final ApprovalBus INSTANCE = new ApprovalBus();

    private volatile Listener listener;
    private volatile String pendingCommandRequestId;

    public static ApprovalBus get() {
        return INSTANCE;
    }

    private ApprovalBus() {}

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    public void setPendingCommandRequestId(String requestId) {
        pendingCommandRequestId = requestId;
    }

    public String getPendingCommandRequestId() {
        return pendingCommandRequestId;
    }

    public void clearPendingCommandRequestId(String requestId) {
        if (requestId != null && requestId.equals(pendingCommandRequestId)) {
            pendingCommandRequestId = null;
        }
    }

    public void dispatchCommandApproved(String requestId) {
        Listener l = listener;
        if (l != null) l.onCommandApproved(requestId);
    }

    public void dispatchCommandRejected(String requestId) {
        Listener l = listener;
        if (l != null) l.onCommandRejected(requestId);
    }

    public void dispatchFileAccepted(String path) {
        Listener l = listener;
        if (l != null) l.onFileAccepted(path);
    }

    public void dispatchFileRejected(String path) {
        Listener l = listener;
        if (l != null) l.onFileRejected(path);
    }
}
