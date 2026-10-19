package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.DraftVault;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.SignalDeviceKeys;
import xyz.eclipseofficial.vyrn.data.SignalMessages;
import xyz.eclipseofficial.vyrn.data.TokenVault;
import static xyz.eclipseofficial.vyrn.test.ReactionReadIntegration.await;

/** Native composer/Back, real process death, offline restoration and persistent clearing. */
final class DraftUiIntegration {
    private static final int SENDER = 9183;
    static void run(Instrumentation test, Activity activity, JSONObject fixture, String phase, AuthUiIntegration.Check check) throws Exception {
        Context context = test.getTargetContext();
        JSONObject state;
        if (phase.equals("draft-cold-prepare")) {
            AuthUiIntegration.registration(test, activity, fixture, check);
            int account = UserConfig.selectedAccount;
            RestRepository repository = VyrnBridge.forAccount(account).repository;
            RestRepository sender = SignalNetworkIntegration.register(context, fixture, SENDER, "-draft-sender");
            String privateConversation;
            String peer;
            try (SignalDeviceKeys a = new SignalDeviceKeys(context, sender); SignalDeviceKeys b = new SignalDeviceKeys(context, repository)) {
                a.publishInitial(); b.publishInitial(); peer = a.user;
                privateConversation = ((JSONObject) sender.request("POST", "/v1/conversations", new JSONObject().put("kind", "private").put("members", new JSONArray().put(b.user)), true)).getString("id");
                SignalMessages outbound = new SignalMessages(a), inbound = new SignalMessages(b);
                String client = UUID.randomUUID().toString();
                outbound.prepare(b.user, privateConversation, client, "Draft fixture");
                String message = outbound.send(client);
                inbound.receive(a.user, privateConversation, (JSONObject) repository.request("GET", "/v1/messages/" + message, null, true));
            }
            String group = ((JSONObject) repository.request("POST", "/v1/conversations", new JSONObject().put("kind", "group").put("title", "Draft UI").put("members", new JSONArray()), true)).getString("id");
            repository.request("POST", "/v1/conversations/" + group + "/messages", new JSONObject().put("client_id", UUID.randomUUID().toString()).put("encrypted", false).put("payload", new JSONObject().put("text", "Draft fixture")), true);
            CompletableFuture<TLRPC.TL_messages_dialogs> dialogs = new CompletableFuture<>();
            ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_getDialogs(), (value, error) -> { if (error == null) dialogs.complete((TLRPC.TL_messages_dialogs) value); else dialogs.completeExceptionally(new AssertionError("draft_dialogs")); });
            TLRPC.TL_messages_dialogs loaded = dialogs.get(30, TimeUnit.SECONDS);
            test.runOnMainSync(() -> { MessagesController.getInstance(account).putUsers(loaded.users, false); MessagesController.getInstance(account).putChats(loaded.chats, false); });
            try (IdMap ids = new IdMap(context, account)) {
                state = new JSONObject().put("account", account).put("group", ids.local("conversation", group)).put("peer", ids.local("user", peer));
            }
            state.put("nonce", UUID.randomUUID().toString()).put("first_pid", android.os.Process.myPid());
            for (boolean privatePeer : new boolean[]{false, true}) {
                long dialog = state.getLong(privatePeer ? "peer" : "group");
                ChatActivity chat = SearchUiIntegration.openChat(test, dialog, privatePeer, check);
                enter(test, chat, text(state, privatePeer), check);
                leave(test, chat, check);
                await(test, () -> draft(account, privatePeer ? dialog : -dialog, text(state, privatePeer)), check, privatePeer ? "private_draft_back_saves_native_model" : "group_draft_back_saves_native_model");
            }
            verifyStored(context, state, check);
        } else {
            state = new JSONObject(new String(Files.readAllBytes(file(context).toPath()), StandardCharsets.UTF_8));
            int account = state.getInt("account");
            check.verify(account == UserConfig.selectedAccount && android.os.Process.myPid() != state.getInt("first_pid"), "draft_recovery_is_a_new_native_process");
            boolean cleared = phase.equals("draft-cold-confirm");
            if (cleared) check.verify(android.os.Process.myPid() != state.getInt("second_pid"), "draft_clear_confirmation_is_another_native_process");
            await(test, () -> VyrnBridge.connectionState(account) != ConnectionsManager.ConnectionStateConnected, check, "draft_recovery_transport_disconnected");
            for (boolean privatePeer : new boolean[]{false, true}) {
                long local = state.getLong(privatePeer ? "peer" : "group"), dialog = privatePeer ? local : -local;
                ChatActivity chat = SearchUiIntegration.openChat(test, local, privatePeer, check);
                String expected = cleared ? "" : text(state, privatePeer);
                await(test, () -> expected.equals(field(chat)), check, (privatePeer ? "private" : "group") + (cleared ? "_draft_stays_cleared_after_offline_process_death" : "_draft_restored_into_original_composer_offline"));
                if (!cleared) enter(test, chat, "", check);
                leave(test, chat, check);
                await(test, () -> empty(account, dialog), check, (privatePeer ? "private" : "group") + "_draft_clear_removes_native_model");
                check.verify(!preferences(context, account).contains(Long.toString(dialog)), (privatePeer ? "private" : "group") + "_draft_clear_removes_persistent_record");
            }
            if (!cleared) state.put("second_pid", android.os.Process.myPid());
            else {
                AuthUiIntegration.networkStep(test, LaunchActivity.instance, "online-retry", check);
                RestRepository sender = new RestRepository(new TokenVault(context, SENDER));
                try { sender.vault.load(); sender.request("POST", "/v1/auth/logout", new JSONObject(), true); }
                finally { sender.vault.clear(); }
                check.verify(true, "draft_fixture_sender_session_logged_out");
            }
        }
        Files.write(file(context).toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static java.io.File file(Context context) { return new java.io.File(context.getFilesDir(), "vyrn-test-draft-state.json"); }
    private static String text(JSONObject state, boolean privatePeer) { return (privatePeer ? "پیش‌نویس خصوصی " : "پیش‌نویس گروه ") + state.optString("nonce"); }
    private static String field(ChatActivity chat) { CharSequence value = chat.getChatActivityEnterView().getFieldText(); return value == null ? "" : value.toString(); }
    private static boolean draft(int account, long dialog, String expected) { TLRPC.DraftMessage value = MediaDataController.getInstance(account).getDraft(dialog, 0); return value != null && expected.equals(value.message); }
    private static boolean empty(int account, long dialog) { TLRPC.DraftMessage value = MediaDataController.getInstance(account).getDraft(dialog, 0); return value == null || value instanceof TLRPC.TL_draftMessageEmpty; }
    private static android.content.SharedPreferences preferences(Context context, int account) { return context.getSharedPreferences(account == 0 ? "drafts" : "drafts" + account, Context.MODE_PRIVATE); }
    private static void enter(Instrumentation test, ChatActivity chat, String value, AuthUiIntegration.Check check) throws Exception {
        boolean[] entered = {false};
        test.runOnMainSync(() -> {
            var edit = chat.getChatActivityEnterView().getEditField(); edit.requestFocus();
            var input = edit.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            entered[0] = input != null && input.setSelection(0, edit.length()) && input.commitText(value, 1) && value.contentEquals(edit.getText());
        });
        check.verify(entered[0], "draft_original_composer_ime_input");
    }
    private static void leave(Instrumentation test, ChatActivity chat, AuthUiIntegration.Check check) throws Exception {
        test.runOnMainSync(() -> AndroidUtilities.hideKeyboard(chat.getChatActivityEnterView().getEditField()));
        await(test, () -> chat.getFragmentView().getRootWindowInsets() != null && !chat.getFragmentView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()), check, "draft_keyboard_closed_before_back");
        test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(test, () -> LaunchActivity.getLastFragment() != chat, check, "draft_original_back_leaves_chat");
    }
    private static void verifyStored(Context context, JSONObject state, AuthUiIntegration.Check check) throws Exception {
        int account = state.getInt("account");
        for (boolean privatePeer : new boolean[]{false, true}) {
            String record = Long.toString(privatePeer ? state.getLong("peer") : -state.getLong("group"));
            String envelope = preferences(context, account).getString(record, "");
            check.verify(!envelope.isEmpty() && (!privatePeer || envelope.startsWith("vyrn-draft-v1:")), (privatePeer ? "private" : "group") + "_draft_record_persisted");
            SerializedData data = new SerializedData(DraftVault.decode(account, record, envelope));
            try { TLRPC.DraftMessage value = TLRPC.DraftMessage.TLdeserialize(data, data.readInt32(true), true); check.verify(value != null && text(state, privatePeer).equals(value.message), (privatePeer ? "private" : "group") + "_draft_roundtrip_is_exact"); }
            finally { data.cleanup(); }
        }
        String privateText = text(state, true), hex = org.telegram.messenger.Utilities.bytesToHex(privateText.getBytes(StandardCharsets.UTF_8)).toLowerCase(java.util.Locale.ROOT);
        for (Object value : preferences(context, account).getAll().values()) if (value instanceof String) check.verify(!((String) value).contains(privateText) && !((String) value).toLowerCase(java.util.Locale.ROOT).contains(hex), "private_draft_preferences_have_no_plaintext_or_hex_copy");
    }
}
