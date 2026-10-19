package xyz.eclipseofficial.vyrn.compat;

import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ChatActivity;
import xyz.eclipseofficial.vyrn.data.TokenVault;

/** Confirmed server state only: dismissing an uncertain write never replays it. */
public final class ReactionUi {
    private static final Set<String> PENDING = new HashSet<>(); // UI thread only.
    private final ChatActivity chat;
    private final int account, message;
    private final long owner, dialog;
    private final String session, key;
    private final TokenVault vault;
    private final boolean fa = LocaleController.isRTL;
    private AlertDialog visible;
    private boolean finished, inFlight;

    public static String label() { return LocaleController.isRTL ? "واکنش" : "React"; }

    public static boolean canChoose(ChatActivity chat, MessageObject value) {
        if (chat == null || value == null || value.messageOwner == null || chat.getChatMode() != ChatActivity.MODE_DEFAULT) return false;
        int account = chat.getCurrentAccount();
        long owner = UserConfig.getInstance(account).getClientUserId(), dialog = chat.getDialogId();
        TLRPC.Peer peer = value.messageOwner.peer_id;
        return owner != 0 && !VyrnBridge.isLoggingOut(account) && value.currentAccount == account
            && dialog != owner && dialog != 0 && value.getDialogId() == dialog && value.getId() > 0
            && value.messageOwner instanceof TLRPC.TL_message && peer != null && peer.channel_id == 0
            && (peer.chat_id > 0 || peer.user_id > 0) && !value.scheduled && !value.isEphemeral()
            && !value.isSponsored() && !value.isExpiredStory() && value.type != MessageObject.TYPE_STORY
            && value.type != MessageObject.TYPE_STORY_MENTION
            && new TokenVault(ApplicationLoader.applicationContext, account).cachedSession() != null;
    }

    public static void show(ChatActivity chat, MessageObject value) {
        if (!canChoose(chat, value)) return;
        ReactionUi ui = new ReactionUi(chat, value.getId());
        if (PENDING.add(ui.key)) ui.read(null);
    }

    private ReactionUi(ChatActivity chat, int message) {
        this.chat = chat; this.message = message; account = chat.getCurrentAccount(); dialog = chat.getDialogId();
        owner = UserConfig.getInstance(account).getClientUserId();
        vault = new TokenVault(ApplicationLoader.applicationContext, account); session = vault.cachedSession();
        key = account + ":" + owner + ":" + session + ":" + dialog + ":" + message;
    }

    private boolean valid() {
        return !finished && session != null && session.equals(vault.cachedSession())
            && owner == UserConfig.getInstance(account).getClientUserId() && !VyrnBridge.isLoggingOut(account)
            && UserConfig.selectedAccount == account && chat.isLastFragment() && chat.getParentActivity() != null
            && !chat.getParentActivity().isFinishing() && !chat.getParentActivity().isDestroyed();
    }

    private void finish() { finished = true; if (!inFlight) PENDING.remove(key); }
    private String text(String english, String persian) { return fa ? persian : english; }
    private void dismiss() { AlertDialog old = visible; visible = null; if (old != null) old.dismiss(); }
    private boolean display(AlertDialog next) {
        if (!valid()) { finish(); return false; }
        dismiss(); visible = next;
        if (chat.showDialog(next, ignored -> { if (visible == next) { visible = null; finish(); } }) == null) {
            visible = null; finish(); return false;
        }
        return true;
    }

    private TLRPC.InputPeer peer() { return MessagesController.getInstance(account).getInputPeer(dialog); }

    private void read(LinkedHashSet<String> desired) {
        if (!valid()) { finish(); return; }
        if (!progress()) return;
        TLRPC.TL_messages_getMessagesReactions request = new TLRPC.TL_messages_getMessagesReactions();
        request.peer = peer(); request.id.add(message);
        send(request, desired, false);
    }

    private void write(LinkedHashSet<String> desired) {
        if (!valid()) { finish(); return; }
        if (!progress()) return;
        TLRPC.TL_messages_sendReaction request = new TLRPC.TL_messages_sendReaction();
        request.peer = peer(); request.msg_id = message; request.flags = desired.isEmpty() ? 0 : 1;
        for (String emoji : desired) { TLRPC.TL_reactionEmoji value = new TLRPC.TL_reactionEmoji(); value.emoticon = emoji; request.reaction.add(value); }
        send(request, new LinkedHashSet<>(desired), true);
    }

    private boolean progress() {
        AlertDialog loading = new AlertDialog.Builder(chat.getParentActivity()).setTitle(label())
            .setMessage(text("Connecting…", "در حال اتصال…")).create();
        if (!display(loading)) return false;
        loading.setCancelable(false); loading.setCanceledOnTouchOutside(false); return true;
    }

    private void send(TLObject request, LinkedHashSet<String> desired, boolean write) {
        inFlight = true;
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            inFlight = false;
            if (!valid()) { dismiss(); finish(); return; }
            if (error != null || !(response instanceof TLRPC.Updates)) { failure(desired); return; }
            TLRPC.Updates updates = (TLRPC.Updates) response;
            MessagesController.getInstance(account).processUpdates(updates, false);
            LinkedHashSet<String> confirmed = null;
            boolean deleted = false;
            for (TLRPC.Update update : updates.updates) {
                if (update instanceof TL_update.TL_updateDeleteMessages && ((TL_update.TL_updateDeleteMessages) update).messages.contains(message)) deleted = true;
                if (update instanceof TL_update.TL_updateMessageReactions) {
                    TL_update.TL_updateMessageReactions value = (TL_update.TL_updateMessageReactions) update;
                    if (value.msg_id == message) {
                        confirmed = new LinkedHashSet<>();
                        for (TLRPC.ReactionCount row : value.reactions.results)
                            if (row.chosen && row.reaction instanceof TLRPC.TL_reactionEmoji) confirmed.add(((TLRPC.TL_reactionEmoji) row.reaction).emoticon);
                    }
                }
            }
            if (deleted) {
                display(new AlertDialog.Builder(chat.getParentActivity()).setTitle(label())
                    .setMessage(text("This message is no longer available.", "این پیام دیگر در دسترس نیست."))
                    .setPositiveButton(text("Close", "بستن"), (d, w) -> finish()).create());
            } else if (confirmed == null) failure(desired);
            else if (desired == null) choose(confirmed);
            else if (confirmed.equals(desired)) { dismiss(); finish(); }
            else if (write) failure(desired);
            else {
                // Reconcile first after any uncertain result; retry only on a fresh explicit confirmation.
                display(new AlertDialog.Builder(chat.getParentActivity()).setTitle(label())
                    .setMessage(text("Server state checked. Send your selection again?", "وضعیت سرور بررسی شد. انتخاب شما دوباره ارسال شود؟"))
                    .setPositiveButton(text("Retry", "تلاش مجدد"), (d, w) -> write(desired))
                    .setNegativeButton(text("Cancel", "انصراف"), (d, w) -> finish()).create());
            }
        }));
    }

    private void failure(LinkedHashSet<String> desired) {
        display(new AlertDialog.Builder(chat.getParentActivity()).setTitle(label())
            .setMessage(text("Connection failed. Your selection may already be saved. Check the server before trying again.",
                "اتصال انجام نشد؛ ممکن است انتخاب شما ذخیره شده باشد. پیش از تلاش دوباره، وضعیت سرور بررسی می‌شود."))
            .setPositiveButton(text("Check again", "بررسی دوباره"), (d, w) -> read(desired))
            .setNegativeButton(text("Cancel", "انصراف"), (d, w) -> finish()).create());
    }

    private void choose(LinkedHashSet<String> confirmed) {
        ArrayList<String> choices = new ArrayList<>(java.util.Arrays.asList("👍", "❤️", "🔥", "🎉", "😂", "😢", "🙏", "👎"));
        for (String emoji : confirmed) if (!choices.contains(emoji)) choices.add(emoji);
        LinkedHashSet<String> desired = new LinkedHashSet<>(confirmed);
        boolean[] checked = new boolean[choices.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = desired.contains(choices.get(i));
        AlertDialog picker = new AlertDialog.Builder(chat.getParentActivity()).setTitle(label())
            .setMultiChoiceItems(choices.toArray(new String[0]), checked, (d, which, selected) -> {
                if (!valid()) { dismiss(); finish(); return; }
                String emoji = choices.get(which);
                if (selected && desired.size() >= 3) {
                    checked[which] = false; // The platform adapter rebinds rows from this array.
                    ((AlertDialog) d).getListView().setItemChecked(which, false);
                    android.widget.Toast.makeText(chat.getParentActivity(), text("Choose up to 3 reactions.", "حداکثر سه واکنش انتخاب کنید."), android.widget.Toast.LENGTH_SHORT).show();
                } else if (selected) desired.add(emoji); else desired.remove(emoji);
            })
            .setPositiveButton(text("Save", "ذخیره"), (d, w) -> write(desired))
            .setNeutralButton(text("Clear", "پاک‌کردن"), (d, w) -> write(new LinkedHashSet<>()))
            .setNegativeButton(text("Cancel", "انصراف"), (d, w) -> finish()).create();
        display(picker);
    }
}
