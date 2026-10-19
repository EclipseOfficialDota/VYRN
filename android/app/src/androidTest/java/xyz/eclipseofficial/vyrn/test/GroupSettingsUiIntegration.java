package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatEditActivity;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.LaunchActivity;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import static xyz.eclipseofficial.vyrn.test.ReactionReadIntegration.await;

/** Fixture screen entry; real IME and Done touches, confirmed save and offline retry. */
final class GroupSettingsUiIntegration {
    static void run(Instrumentation test, Activity activity, JSONObject fixture, AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(test, activity, fixture, check);
        test.getTargetContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN).setClassName(test.getTargetContext(), "org.telegram.ui.LaunchActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await(test, () -> LaunchActivity.instance != null && LaunchActivity.instance.hasWindowFocus(), check, "group_fixture_activity_in_foreground");
        int account = UserConfig.selectedAccount;
        RestRepository actor = VyrnBridge.forAccount(account).repository;
        RestRepository owner = SignalNetworkIntegration.register(test.getTargetContext(), fixture, 9185, "-group-owner");
        boolean offline = false;
        try {
            String user = actor.me().id;
            String group = ((JSONObject) owner.request("POST", "/v1/conversations", new JSONObject().put("kind", "group").put("title", "Group settings").put("members", new JSONArray().put(user)), true)).getString("id");
            owner.setMemberRole(group, user, true);
            owner.setHistoryTTL(group, 3600);
            long local;
            try (IdMap ids = new IdMap(test.getTargetContext(), account)) { local = ids.local("conversation", group); }
            TLRPC.TL_messages_chatFull full = load(test, account, local);
            TLRPC.Chat chat = full.chats.get(0);
            check.verify(!chat.creator && ChatObject.hasAdminRights(chat) && ChatObject.canChangeChatInfo(chat) && ChatObject.canAddUsers(chat), "group_admin_native_rights_match_backend");
            check.verify(chat.admin_rights.delete_messages && chat.admin_rights.ban_users && !chat.admin_rights.add_admins && !chat.admin_rights.manage_call, "group_admin_has_supported_role_without_owner_or_call_rights");
            SerializedData bytes = new SerializedData(); chat.serializeToStream(bytes);
            SerializedData read = new SerializedData(bytes.toByteArray());
            TLRPC.Chat restored = TLRPC.Chat.TLdeserialize(read, read.readInt32(true), true); read.cleanup(); bytes.cleanup();
            check.verify(ChatObject.hasAdminRights(restored) && restored.admin_rights.change_info && !restored.admin_rights.add_admins, "group_admin_rights_survive_native_serialization");
            ChatEditActivity editor = open(test, local, full, check);
            gates(test, editor, true, check);
            enter(test, editor, "گروه ویرایش‌شده", check);
            done(test, editor, check);
            await(test, () -> LaunchActivity.getLastFragment() != editor, check, "group_confirmed_rename_closes_original_editor");
            check.verify(actor.conversationDetails(group).conversation.title.equals("گروه ویرایش‌شده") && actor.conversationDetails(group).conversation.disappearingSeconds == 3600, "group_admin_rename_preserves_server_ttl");
            await(test, () -> "گروه ویرایش‌شده".equals(MessagesController.getInstance(account).getChat(local).title), check, "group_confirmed_title_reaches_native_cache");

            ChatEditActivity retry = open(test, local, load(test, account, local), check);
            String pending = "ذخیره پس از اتصال";
            enter(test, retry, pending, check);
            AuthUiIntegration.networkStep(test, LaunchActivity.instance, "offline-load", check); offline = true;
            done(test, retry, check);
            await(test, () -> !(Boolean) field(retry, "donePressed") && field(retry, "progressDialog") == null, check, "group_offline_save_releases_busy_state");
            await(test, () -> LaunchActivity.getLastFragment() == retry && pending.contentEquals(name(retry).getText()) && "گروه ویرایش‌شده".equals(MessagesController.getInstance(account).getChat(local).title), check, "group_offline_save_retains_text_and_confirmed_cache");
            AuthUiIntegration.networkStep(test, LaunchActivity.instance, "online-retry", check); offline = false;
            check.verify(actor.conversationDetails(group).conversation.title.equals("گروه ویرایش‌شده"), "group_offline_save_did_not_mutate_server");
            done(test, retry, check);
            await(test, () -> LaunchActivity.getLastFragment() != retry, check, "group_explicit_online_retry_closes_editor");
            check.verify(actor.conversationDetails(group).conversation.title.equals(pending) && actor.conversationDetails(group).members.size() == 2, "group_retry_saves_title_preserving_membership");

            owner.setMemberRole(group, user, false);
            full = load(test, account, local);
            check.verify(!ChatObject.hasAdminRights(full.chats.get(0)) && !ChatObject.canChangeChatInfo(full.chats.get(0)), "group_demoted_member_has_no_native_admin_rights");
            ChatEditActivity member = open(test, local, full, check);
            gates(test, member, false, check);
            test.runOnMainSync(member::finishFragment);
            await(test, () -> LaunchActivity.getLastFragment() != member, check, "group_member_fixture_editor_closed");

            String own = ((JSONObject) actor.request("POST", "/v1/conversations", new JSONObject().put("kind", "group").put("title", "Owned settings").put("members", new JSONArray()), true)).getString("id");
            long ownLocal; try (IdMap ids = new IdMap(test.getTargetContext(), account)) { ownLocal = ids.local("conversation", own); }
            TLRPC.Chat owned = load(test, account, ownLocal).chats.get(0);
            check.verify(owned.creator && owned.admin_rights.add_admins && ChatObject.hasAdminRights(owned), "group_owner_retains_owner_role_management");
        } finally {
            if (offline) AuthUiIntegration.networkStep(test, LaunchActivity.instance, "online-retry", check);
            try { owner.request("POST", "/v1/auth/logout", new JSONObject(), true); } finally { owner.vault.clear(); }
        }
    }
    private static TLRPC.TL_messages_chatFull load(Instrumentation test, int account, long chat) throws Exception {
        TLRPC.TL_messages_getFullChat request = new TLRPC.TL_messages_getFullChat(); request.chat_id = chat;
        CompletableFuture<TLRPC.TL_messages_chatFull> done = new CompletableFuture<>();
        ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> { if (error == null) done.complete((TLRPC.TL_messages_chatFull) response); else done.completeExceptionally(new AssertionError("group_load")); });
        TLRPC.TL_messages_chatFull value = done.get(30, TimeUnit.SECONDS);
        test.runOnMainSync(() -> { MessagesController.getInstance(account).putUsers(value.users, false); MessagesController.getInstance(account).putChats(value.chats, false); });
        return value;
    }
    private static ChatEditActivity open(Instrumentation test, long chat, TLRPC.TL_messages_chatFull full, AuthUiIntegration.Check check) throws Exception {
        await(test, () -> LaunchActivity.getLastFragment() != null && !LaunchActivity.getLastFragment().getParentLayout().checkTransitionAnimation(), check, "group_editor_parent_ready");
        Bundle args = new Bundle(); args.putLong("chat_id", chat); ChatEditActivity editor = new ChatEditActivity(args);
        boolean[] shown = {false};
        test.runOnMainSync(() -> { editor.setInfo(full.full_chat); shown[0] = LaunchActivity.getLastFragment().presentFragment(editor); });
        check.verify(shown[0], "group_fixture_editor_presentation_accepted");
        await(test, () -> editor.isLastFragment() && editor.getFragmentView() != null && !editor.getParentLayout().checkTransitionAnimation(), check, "group_original_editor_fixture_ready");
        AuthUiIntegration.handlePrompts(test, LaunchActivity.instance, check);
        await(test, () -> editor.getFragmentView().isShown() && editor.getParentActivity().hasWindowFocus(), check, "group_original_editor_window_visible");
        return editor;
    }
    private static void gates(Instrumentation test, ChatEditActivity editor, boolean editable, AuthUiIntegration.Check check) throws Exception {
        await(test, () -> name(editor).isEnabled() == editable && (field(editor, "doneButton") == null ? !editable : ((View) field(editor, "doneButton")).isEnabled() == editable), check, "group_editor_rename_controls_match_role");
        for (String control : new String[]{"settingsContainer", "typeEditContainer", "blockCell", "inviteLinksCell", "memberRequestsCell", "logCell", "reactionsCell", "deleteContainer", "stickersContainer"}) {
            await(test, () -> field(editor, control) == null || !((View) field(editor, control)).isShown(), check, "group_unavailable_" + control + "_hidden");
        }
        await(test, () -> ((View) field(editor, "membersCell")).isShown() == editable && ((View) field(editor, "adminCell")).isShown() == editable, check, "group_member_and_admin_rows_match_native_role_visibility");
    }
    private static android.widget.EditText name(ChatEditActivity editor) { return ((EditTextEmoji) field(editor, "nameTextView")).getEditText(); }
    private static void enter(Instrumentation test, ChatEditActivity editor, String title, AuthUiIntegration.Check check) {
        boolean[] ok = {false}; test.runOnMainSync(() -> { var edit = name(editor); edit.requestFocus(); var input = edit.onCreateInputConnection(new android.view.inputmethod.EditorInfo()); ok[0] = input != null && input.setSelection(0, edit.length()) && input.commitText(title, 1) && title.contentEquals(edit.getText()); });
        check.verify(ok[0], "group_title_entered_via_original_ime");
    }
    private static void done(Instrumentation test, ChatEditActivity editor, AuthUiIntegration.Check check) {
        Rect bounds = new Rect(); test.runOnMainSync(() -> { View button = (View) field(editor, "doneButton"); int[] xy = new int[2]; button.getLocationOnScreen(xy); bounds.set(xy[0], xy[1], xy[0] + button.getWidth(), xy[1] + button.getHeight()); });
        check.verify(!bounds.isEmpty() && AuthUiIntegration.touch(test, bounds), "group_original_done_button_touched");
    }
    private static Object field(Object object, String name) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) try { var f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (NoSuchFieldException inherited) { } catch (Exception failure) { throw new IllegalStateException(name, failure); }
        throw new IllegalStateException(name);
    }
}
