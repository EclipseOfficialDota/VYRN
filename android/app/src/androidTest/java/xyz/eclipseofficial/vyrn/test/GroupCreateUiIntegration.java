package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.util.UUID;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.GroupCreateFinalActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.GroupCreateUserCell;
import org.telegram.ui.Components.EditTextEmoji;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.RecyclerListView;
import xyz.eclipseofficial.vyrn.compat.VyrnBridge;
import xyz.eclipseofficial.vyrn.data.IdMap;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import static xyz.eclipseofficial.vyrn.test.GroupSettingsUiIntegration.field;
import static xyz.eclipseofficial.vyrn.test.ReactionReadIntegration.await;

/** Picker entry is a fixture; search, selection, creation, profile and invite controls are native. */
final class GroupCreateUiIntegration {
    static void run(Instrumentation test, Activity activity, JSONObject fixture, AuthUiIntegration.Check check) throws Exception {
        AuthUiIntegration.registration(test, activity, fixture, check);
        test.getTargetContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN).setClassName(test.getTargetContext(), "org.telegram.ui.LaunchActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        await(test, () -> LaunchActivity.instance != null && LaunchActivity.instance.hasWindowFocus(), check, "group_create_fixture_activity_foreground");
        int account = UserConfig.selectedAccount;
        RestRepository actor = VyrnBridge.forAccount(account).repository;
        String nonce = UUID.randomUUID().toString().substring(0, 8), initialQuery = "gcreate-" + nonce, addedQuery = "gadd-" + nonce;
        RestRepository initial = SignalNetworkIntegration.register(test.getTargetContext(), fixture, 9186, "-" + initialQuery);
        RestRepository added = SignalNetworkIntegration.register(test.getTargetContext(), fixture, 9187, "-" + addedQuery);
        try {
            String initialUser = initial.me().id, addedUser = added.me().id, owner = actor.me().id;
            long initialLocal, addedLocal;
            try (IdMap ids = new IdMap(test.getTargetContext(), account)) { initialLocal = ids.local("user", initialUser); addedLocal = ids.local("user", addedUser); }
            Bundle args = new Bundle(); args.putInt("chatType", ChatObject.CHAT_TYPE_CHAT);
            GroupCreateActivity picker = new GroupCreateActivity(args);
            ready(test, LaunchActivity.getLastFragment(), check);
            boolean[] accepted = {false}; test.runOnMainSync(() -> accepted[0] = LaunchActivity.getLastFragment().presentFragment(picker));
            check.verify(accepted[0], "group_create_picker_fixture_presented"); ready(test, picker, check);
            select(test, picker, initialQuery, initialLocal, check);
            tap(test, (View) field(picker, "floatingButton"), check, "group_create_original_next_touched");
            await(test, () -> LaunchActivity.getLastFragment() instanceof GroupCreateFinalActivity, check, "group_create_original_name_screen_opened");
            GroupCreateFinalActivity finish = (GroupCreateFinalActivity) LaunchActivity.getLastFragment(); ready(test, finish, check);
            await(test, () -> !((View) field(finish, "avatarOverlay")).isClickable() && ((View) field(finish, "avatarEditor")).getVisibility() == View.GONE, check, "group_create_unimplemented_photo_picker_unavailable");
            String title = "گروه رابط " + nonce;
            enter(test, ((EditTextEmoji) field(finish, "editText")).getEditText(), title, check);
            keyboardClosed(test, finish, check);
            tap(test, (View) field(finish, "floatingButton"), check, "group_create_original_create_touched");
            await(test, () -> LaunchActivity.getLastFragment() instanceof ChatActivity, check, "group_create_confirmation_opens_original_chat");
            ChatActivity chat = (ChatActivity) LaunchActivity.getLastFragment(); ready(test, chat, check);
            check.verify(chat.getDialogId() < 0, "group_create_native_dialog_is_group");
            String group; try (IdMap ids = new IdMap(test.getTargetContext(), account)) { group = ids.remote("conversation", -chat.getDialogId()); }
            var details = actor.conversationDetails(group);
            check.verify(details.conversation.title.equals(title) && details.conversation.role.equals("owner") && details.members.size() == 2 && details.members.stream().anyMatch(m -> m.user.id.equals(initialUser)) && details.members.stream().anyMatch(m -> m.user.id.equals(owner)), "group_create_server_has_exact_title_owner_and_selected_member");
            check.verify(actor.conversations().stream().filter(c -> c.title.equals(title)).count() == 1, "group_create_single_native_submission_creates_one_group");
            check.verify(initial.conversationDetails(group).members.size() == 2, "group_create_selected_member_can_load_new_group");

            tap(test, chat.avatarContainer, check, "group_create_original_chat_header_touched");
            await(test, () -> LaunchActivity.getLastFragment() instanceof ProfileActivity, check, "group_create_original_profile_opened");
            ProfileActivity profile = (ProfileActivity) LaunchActivity.getLastFragment(); ready(test, profile, check);
            await(test, () -> field(profile, "chatInfo") != null && (int) field(profile, "addMemberRow") >= 0, check, "group_create_profile_membership_and_invite_row_loaded");
            RecyclerListView list = (RecyclerListView) field(profile, "listView");
            int position = (int) field(profile, "addMemberRow");
            test.runOnMainSync(() -> list.scrollToPosition(position));
            await(test, () -> list.findViewHolderForAdapterPosition(position) != null && list.findViewHolderForAdapterPosition(position).itemView.isShown(), check, "group_create_profile_add_member_row_visible");
            tap(test, list.findViewHolderForAdapterPosition(position).itemView, check, "group_create_original_add_member_row_touched");
            await(test, () -> LaunchActivity.getLastFragment() instanceof GroupCreateActivity, check, "group_invite_original_picker_opened_from_profile");
            GroupCreateActivity invite = (GroupCreateActivity) LaunchActivity.getLastFragment(); ready(test, invite, check);
            select(test, invite, addedQuery, addedLocal, check);
            tap(test, (View) field(invite, "floatingButton"), check, "group_invite_original_next_touched");
            await(test, () -> dialog(invite) != null, check, "group_invite_original_confirmation_visible");
            test.runOnMainSync(() -> accepted[0] = countCheckboxes(dialog(invite).getWindow().getDecorView()) == 0);
            check.verify(accepted[0], "group_invite_unsupported_history_forward_checkbox_absent");
            tap(test, dialog(invite).getButton(android.content.DialogInterface.BUTTON_NEGATIVE), check, "group_invite_original_cancel_touched");
            await(test, () -> dialog(invite) == null && invite.isLastFragment(), check, "group_invite_cancel_keeps_original_selection_screen");
            check.verify(actor.conversationDetails(group).members.size() == 2, "group_invite_cancel_does_not_add_member");
            tap(test, (View) field(invite, "floatingButton"), check, "group_invite_original_next_retouched");
            await(test, () -> dialog(invite) != null, check, "group_invite_confirmation_reopened");
            tap(test, dialog(invite).getButton(android.content.DialogInterface.BUTTON_POSITIVE), check, "group_invite_original_add_confirmation_touched");
            await(test, () -> LaunchActivity.getLastFragment() == profile, check, "group_invite_returns_to_original_profile");
            boolean member = false;
            for (int i = 0; i < 30 && !member; i++) { var current = actor.conversationDetails(group); member = current.members.size() == 3 && current.members.stream().anyMatch(m -> m.user.id.equals(addedUser)); if (!member) Thread.sleep(200); }
            check.verify(member, "group_invite_zero_history_request_adds_selected_member");
            check.verify(added.conversationDetails(group).members.size() == 3 && actor.conversationDetails(group).conversation.title.equals(title), "group_invite_new_member_has_access_and_title_is_preserved");
            await(test, () -> ((TLRPC.ChatFull) field(profile, "chatInfo")).participants.participants.size() == 3, check, "group_invite_confirmed_membership_reaches_original_profile");
        } finally {
            for (RestRepository peer : new RestRepository[]{initial, added}) try { peer.request("POST", "/v1/auth/logout", new JSONObject(), true); } finally { peer.vault.clear(); }
        }
    }
    private static void ready(Instrumentation test, BaseFragment fragment, AuthUiIntegration.Check check) throws Exception {
        await(test, () -> fragment != null && fragment.isLastFragment() && fragment.getFragmentView() != null && !fragment.getParentLayout().checkTransitionAnimation(), check, "group_create_native_transition_ready");
        AuthUiIntegration.handlePrompts(test, LaunchActivity.instance, check);
        await(test, () -> fragment.getFragmentView().isShown() && fragment.getParentActivity().hasWindowFocus(), check, "group_create_native_window_visible");
    }
    private static void select(Instrumentation test, GroupCreateActivity picker, String query, long user, AuthUiIntegration.Check check) throws Exception {
        enter(test, ((FragmentSearchField) field(picker, "searchField")).editText, query, check);
        RecyclerListView list = (RecyclerListView) field(picker, "listView"); View[] row = {null};
        await(test, () -> { for (int i = 0; i < list.getChildCount(); i++) { View view = list.getChildAt(i); if (view instanceof GroupCreateUserCell && ((GroupCreateUserCell) view).getObject() instanceof TLRPC.User && ((TLRPC.User) ((GroupCreateUserCell) view).getObject()).id == user) { row[0] = view; return true; } } return false; }, check, "group_create_public_search_returns_original_user_row");
        keyboardClosed(test, picker, check);
        tap(test, row[0], check, "group_create_original_search_result_touched");
        await(test, () -> ((androidx.collection.LongSparseArray<?>) field(picker, "selectedContacts")).indexOfKey(user) >= 0, check, "group_create_original_picker_records_selected_member");
        keyboardClosed(test, picker, check);
    }
    private static void enter(Instrumentation test, EditText edit, String value, AuthUiIntegration.Check check) {
        boolean[] entered = {false}; test.runOnMainSync(() -> { edit.requestFocus(); var input = edit.onCreateInputConnection(new android.view.inputmethod.EditorInfo()); entered[0] = input != null && input.setSelection(0, edit.length()) && input.commitText(value, 1) && value.contentEquals(edit.getText()); }); check.verify(entered[0], "group_create_original_ime_input");
    }
    private static void keyboardClosed(Instrumentation test, BaseFragment fragment, AuthUiIntegration.Check check) throws Exception {
        test.runOnMainSync(() -> AndroidUtilities.hideKeyboard(fragment.getFragmentView()));
        await(test, () -> fragment.getFragmentView().getRootWindowInsets() != null && !fragment.getFragmentView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()), check, "group_create_keyboard_closed_before_touch");
    }
    private static void tap(Instrumentation test, View view, AuthUiIntegration.Check check, String name) throws Exception {
        Rect bounds = new Rect(), previous = new Rect(); long[] stable = {0};
        await(test, () -> { int[] xy = new int[2]; view.getLocationOnScreen(xy); bounds.set(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight()); long now = android.os.SystemClock.uptimeMillis(); if (!view.isShown() || !view.isEnabled() || view.getAlpha() < .99f || view.isLayoutRequested() || bounds.isEmpty() || !bounds.equals(previous)) { previous.set(bounds); stable[0] = now; return false; } return now - stable[0] >= 600; }, check, name + "_stable");
        check.verify(AuthUiIntegration.touch(test, bounds), name);
    }
    private static AlertDialog dialog(BaseFragment fragment) { return fragment.visibleDialog instanceof AlertDialog && fragment.visibleDialog.isShowing() ? (AlertDialog) fragment.visibleDialog : null; }
    private static int countCheckboxes(View view) { int count = view instanceof CheckBoxCell ? 1 : 0; if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) count += countCheckboxes(((ViewGroup) view).getChildAt(i)); return count; }
}
