package xyz.eclipseofficial.vyrn.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import xyz.eclipseofficial.vyrn.data.RestRepository;
import xyz.eclipseofficial.vyrn.data.TokenVault;

/** Actual recovery/sign-in controls against disposable local TLS accounts. */
final class AuthUiIntegration {
    interface Check { void verify(boolean value,String name); }
    private final Instrumentation instrumentation;
    private final View root;
    private final Check check;
    private final boolean fa="fa".equals(Locale.getDefault().getLanguage());
    private AuthUiIntegration(Instrumentation instrumentation,Activity activity,Check check){this.instrumentation=instrumentation;root=activity.getWindow().getDecorView();this.check=check;}
    static void run(Instrumentation instrumentation,Activity activity,JSONObject fixture,Check check) throws Exception {new AuthUiIntegration(instrumentation,activity,check).run(fixture);}
    static void registration(Instrumentation instrumentation,Activity activity,JSONObject fixture,Check check) throws Exception {new AuthUiIntegration(instrumentation,activity,check).register(fixture);}
    static void logout(Instrumentation instrumentation,Activity activity,JSONObject fixture,Check check,boolean offline) throws Exception {new AuthUiIntegration(instrumentation,activity,check).logout(fixture,offline);}
    static void onboarding(Instrumentation instrumentation,Activity activity,JSONObject fixture,Check check,String phase) throws Exception {new AuthUiIntegration(instrumentation,activity,check).onboarding(fixture,phase);}
    static void sessions(Instrumentation instrumentation,Activity activity,JSONObject fixture,Check check) throws Exception {new AuthUiIntegration(instrumentation,activity,check).sessions(fixture);}
    static void sessionNetwork(Instrumentation instrumentation,Activity activity,JSONObject fixture,Check check) throws Exception {new AuthUiIntegration(instrumentation,activity,check).sessions(fixture,true);}
    static boolean handlePrompts(Instrumentation instrumentation,Activity activity,Check check){return new AuthUiIntegration(instrumentation,activity,check).systemPromptBlocks();}
    static void clickWindow(Instrumentation instrumentation,Activity activity,String text,Check check) throws Exception {new AuthUiIntegration(instrumentation,activity,check).windowClick(text);}
    private String label(String en,String persian){return fa?persian:en;}
    private View find(View view,String value){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()) || view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private boolean visible(String value){boolean[] found={false};instrumentation.runOnMainSync(()->found[0]=find(root,value)!=null);return found[0];}
    private void click(String value){instrumentation.runOnMainSync(()->{View view=find(root,value);if(view==null||!view.isEnabled())throw new AssertionError("control_unavailable");view.performClick();});}
    private void fill(String value,String text){instrumentation.runOnMainSync(()->{View view=find(root,value);if(!(view instanceof EditText))throw new AssertionError("field_unavailable");((EditText)view).setText(text);});}
    private void await(BooleanSupplier predicate,String name) throws Exception {long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);while(!predicate.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(100);check.verify(predicate.getAsBoolean(),name);}
    private org.telegram.ui.ActionBar.AlertDialog dialog(){
        org.telegram.ui.ActionBar.AlertDialog[] result={null};
        instrumentation.runOnMainSync(()->result[0]=currentDialog());
        return result[0];
    }
    private org.telegram.ui.ActionBar.AlertDialog currentDialog(){
        org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getLastFragment();
        return fragment!=null&&fragment.visibleDialog instanceof org.telegram.ui.ActionBar.AlertDialog&&fragment.visibleDialog.isShowing()?(org.telegram.ui.ActionBar.AlertDialog)fragment.visibleDialog:null;
    }
    private void register(JSONObject fixture) throws Exception {
        register(fixture,false);
    }
    private void register(JSONObject fixture,boolean leavePending) throws Exception {
        int account=UserConfig.selectedAccount;
        check.verify(!UserConfig.getInstance(account).isClientActivated(),"registration_ui_fresh_account");
        click(label("Create account with invite","ثبت‌نام با دعوت"));
        check.verify(visible(label("Display name","نام نمایشی"))&&visible(label("Invite code","کد دعوت")),"registration_ui_fields");
        click(label("Create account","ساخت حساب"));
        check.verify(visible(label("Complete the account details.","مشخصات حساب را کامل وارد کنید.")),"registration_ui_missing_input_rejected");
        fill(label("Email","ایمیل"),"registration-"+fixture.getString("email"));fill(label("Password","رمز عبور"),fixture.getString("password"));fill(label("Display name","نام نمایشی"),"Registration UI fixture");fill(label("Invite code","کد دعوت"),"invalid");click(label("Create account","ساخت حساب"));
        await(()->visible(label("Could not create account. Check your connection, invite code and account details.","حساب ساخته نشد. اتصال، کد دعوت و مشخصات حساب را بررسی کنید.")),"registration_ui_invalid_invite_feedback");
        check.verify(!UserConfig.getInstance(account).isClientActivated(),"registration_ui_invalid_invite_no_activation");
        fill(label("Invite code","کد دعوت"),fixture.getString("invite"));click(label("Create account","ساخت حساب"));
        await(()->dialog()!=null,"registration_ui_recovery_dialog");
        RestRepository repository=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));
        JSONObject tokens=repository.vault.load();String code=tokens.getString("recovery_code");
        boolean[] message={false},selectable={false},login={false};
        instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.AlertDialog alert=currentDialog();message[0]=code.length()==43&&alert.getMessageTextView().getText().toString().contains(code);selectable[0]=alert.getMessageTextView().isTextSelectable();login[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity;});
        check.verify(message[0],"registration_ui_correct_recovery_code");check.verify(selectable[0],"registration_ui_code_selectable");check.verify(login[0],"registration_ui_confirmation_before_navigation");
        check.verify(repository.me().id.equals(tokens.getString("user_id")),"registration_ui_server_identity");
        boolean[] retained={false};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.AlertDialog alert=currentDialog();alert.onBackPressed();retained[0]=alert.isShowing();});
        check.verify(retained[0],"registration_ui_back_preserves_code");
        check.verify(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME),"registration_ui_home_action");
        instrumentation.waitForIdleSync();
        instrumentation.getTargetContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN).setClassName(instrumentation.getTargetContext(),"org.telegram.ui.LaunchActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        instrumentation.waitForIdleSync();
        await(()->dialog()!=null,"registration_ui_home_return_preserves_code");
        if(leavePending){
            check.verify(repository.vault.hasPendingRecovery(),"onboarding_marker_persisted");
            boolean rejected=false;try{repository.vault.acknowledgeRecovery(tokens.getString("user_id"),"different-session",code);}catch(SecurityException expected){rejected=true;}
            check.verify(rejected&&repository.vault.hasPendingRecovery()&&repository.vault.load().getString("recovery_code").equals(code),"onboarding_stale_confirmation_preserves_code");
            JSONObject renewed=(JSONObject)repository.request("POST","/v1/auth/refresh",new JSONObject().put("refresh_token",repository.vault.load().getString("refresh_token")),false);
            check.verify(!renewed.has("recovery_code"),"onboarding_real_refresh_has_no_code");repository.vault.save(renewed);
            check.verify(repository.vault.hasPendingRecovery()&&repository.vault.load().getString("recovery_code").equals(code),"onboarding_refresh_preserves_code");
            JSONObject stale=repository.vault.load();stale.put("access_token","invalid");repository.vault.save(stale);
            check.verify(repository.me(tokens.getString("user_id"),tokens.getString("session_id")).id.equals(tokens.getString("user_id"))&&repository.vault.hasPendingRecovery()&&repository.vault.load().getString("recovery_code").equals(code),"onboarding_bound_auto_refresh_preserves_code");
            rejected=false;try{repository.me("different-user",tokens.getString("session_id"));}catch(SecurityException expected){rejected=true;}
            check.verify(rejected&&repository.vault.hasPendingRecovery()&&repository.vault.load().getString("recovery_code").equals(code),"onboarding_wrong_identity_lookup_preserves_code");
            boolean encrypted=true;for(Object value:instrumentation.getTargetContext().getSharedPreferences("vyrn_session_"+account,android.content.Context.MODE_PRIVATE).getAll().values())if(String.valueOf(value).contains(code))encrypted=false;
            check.verify(encrypted,"onboarding_code_not_in_plain_preferences");
            writeOnboarding(new JSONObject().put("user",tokens.getString("user_id")).put("session",tokens.getString("session_id")).put("native_user",UserConfig.getInstance(account).getClientUserId()).put("code_hash",codeHash(code)).put("pid",android.os.Process.myPid()));return;
        }
        instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
        await(()->{boolean[] main={false};instrumentation.runOnMainSync(()->main[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof org.telegram.ui.MainTabsActivity);return main[0];},"registration_ui_saved_opens_main");
        check.verify(!repository.vault.hasPendingRecovery()&&!repository.vault.load().has("recovery_code"),"registration_ui_saved_removes_device_code");
    }
    private String codeHash(String code) throws Exception {return android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);}
    private java.io.File onboardingFile(){return new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-onboarding.json");}
    private void writeOnboarding(JSONObject state) throws Exception {java.nio.file.Files.write(onboardingFile().toPath(),state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void onboarding(JSONObject fixture,String phase) throws Exception {
        if(phase.equals("onboarding-prepare")){register(fixture,true);return;}
        JSONObject state=new JSONObject(new String(java.nio.file.Files.readAllBytes(onboardingFile().toPath()),java.nio.charset.StandardCharsets.UTF_8));int account=UserConfig.selectedAccount;
        check.verify(state.getInt("pid")!=android.os.Process.myPid(),"onboarding_distinct_process");
        TokenVault vault=new TokenVault(instrumentation.getTargetContext(),account);
        if(phase.equals("onboarding-complete")){
            await(()->UserConfig.getInstance(account).getClientUserId()==state.optLong("native_user"),"onboarding_saved_identity_survives_restart");
            check.verify(!vault.hasPendingRecovery()&&!vault.load().has("recovery_code"),"onboarding_saved_code_stays_removed");
            check.verify(!UserConfig.getInstance(account).syncContacts&&!UserConfig.getInstance(account).getPreferences().getBoolean("syncContacts",true),"onboarding_contact_sync_stays_disabled");
            boolean[] main={false};instrumentation.runOnMainSync(()->main[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof org.telegram.ui.MainTabsActivity&&currentDialog()==null);check.verify(main[0],"onboarding_saved_restart_opens_main");return;
        }
        await(()->dialog()!=null,"onboarding_restart_restores_dialog");
        JSONObject tokens=vault.load();String code=tokens.getString("recovery_code");boolean[] shown={false};
        instrumentation.runOnMainSync(()->shown[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity&&currentDialog().getMessageTextView().getText().toString().contains(code)&&currentDialog().getMessageTextView().isTextSelectable());
        check.verify(shown[0]&&codeHash(code).equals(state.getString("code_hash")),"onboarding_exact_selectable_code_restored");
        check.verify(vault.hasPendingRecovery()&&tokens.getString("user_id").equals(state.getString("user"))&&tokens.getString("session_id").equals(state.getString("session")),"onboarding_original_session_preserved");
        check.verify(UserConfig.getInstance(account).getClientUserId()==state.getLong("native_user"),"onboarding_native_identity_restored");
        if(phase.equals("onboarding-restart")){
            instrumentation.getTargetContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("tg://settings/devices")).setClassName(instrumentation.getTargetContext(),"org.telegram.ui.LaunchActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->shown[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity&&currentDialog()!=null);check.verify(shown[0],"onboarding_settings_link_cannot_skip_confirmation");
        }else if(phase.equals("onboarding-gap")){
            instrumentation.runOnMainSync(()->{UserConfig config=UserConfig.getInstance(account);config.clearConfig();check.verify(config.getPreferences().edit().commit(),"onboarding_missing_native_config_committed");});
            check.verify(!UserConfig.getInstance(account).isClientActivated()&&vault.hasPendingRecovery(),"onboarding_gap_keeps_encrypted_code");
        }else if(phase.equals("onboarding-recover")){
            instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
            await(()->{boolean[] main={false};instrumentation.runOnMainSync(()->main[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof org.telegram.ui.MainTabsActivity);return main[0];},"onboarding_saved_opens_main");
            check.verify(!vault.hasPendingRecovery()&&!vault.load().has("recovery_code"),"onboarding_saved_clears_marker_and_code");
        }else throw new IllegalArgumentException("onboarding_phase");
        state.put("pid",android.os.Process.myPid());writeOnboarding(state);
    }
    private void run(JSONObject fixture) throws Exception {
        int account=UserConfig.selectedAccount;
        check.verify(!UserConfig.getInstance(account).isClientActivated(),"auth_ui_fresh_account");
        RestRepository setup=new RestRepository(new TokenVault(instrumentation.getTargetContext(),9010));
        String email=fixture.getString("email"),oldPassword=fixture.getString("password"),newPassword=oldPassword+"new";
        JSONObject registered=(JSONObject)setup.request("POST","/v1/auth/register",new JSONObject().put("email",email).put("password",oldPassword).put("display_name","Auth UI fixture").put("invite_code",fixture.getString("invite")).put("device_id",setup.vault.device()).put("device_name","Disposable auth UI fixture"),false);
        setup.vault.save(registered);
        click(label("Forgot password?","رمز را فراموش کرده‌اید؟"));
        check.verify(visible(label("Recovery code","کد بازیابی"))&&visible(label("New password","رمز جدید"))&&!visible(label("Display name","نام نمایشی")),"auth_ui_recovery_fields");
        click(label("Reset password","تغییر رمز"));
        check.verify(visible(label("Complete the account details.","مشخصات حساب را کامل وارد کنید.")),"auth_ui_empty_recovery_rejected");
        fill(label("Email","ایمیل"),email);fill(label("New password","رمز جدید"),newPassword);fill(label("Recovery code","کد بازیابی"),"invalid");click(label("Reset password","تغییر رمز"));
        await(()->visible(label("Could not reset your password. Check your connection, recovery code and new password.","رمز تغییر نکرد. اتصال، کد بازیابی و رمز جدید را بررسی کنید.")),"auth_ui_invalid_recovery_feedback");
        fill(label("Recovery code","کد بازیابی"),registered.getString("recovery_code"));click(label("Reset password","تغییر رمز"));
        await(()->visible(label("Password changed. Sign in with your new password.","رمز تغییر کرد. با رمز جدید وارد شوید.")),"auth_ui_recovery_success");
        check.verify(!visible(label("Recovery code","کد بازیابی"))&&visible(label("Password","رمز عبور")),"auth_ui_recovery_returns_to_login");
        boolean revoked=false;try{setup.me();}catch(RestRepository.ApiException error){revoked=error.status==401;}check.verify(revoked,"auth_ui_recovery_revokes_old_session");
        fill(label("Password","رمز عبور"),oldPassword);click(label("Sign in","ورود"));
        await(()->visible(label("Could not sign in. Check your connection and account details.","ورود انجام نشد. اتصال و مشخصات حساب را بررسی کنید.")),"auth_ui_old_password_rejected");
        fill(label("Password","رمز عبور"),newPassword);click(label("Sign in","ورود"));
        await(()->UserConfig.getInstance(account).isClientActivated(),"auth_ui_new_password_login");
        await(()->UserConfig.getInstance(account).getPreferences().contains("user"),"auth_ui_native_identity_persisted");
        check.verify(!UserConfig.getInstance(account).getPreferences().getBoolean("syncContacts",true),"auth_ui_contact_sync_persisted_disabled");
        RestRepository current=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));
        check.verify(current.me().id.equals(registered.getString("user_id")),"auth_ui_authenticated_identity");setup.vault.clear();
    }
    private void confirmLogout(int account) throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.getUiAutomation().setServiceInfo(info);
        instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getLastFragment();if(!(fragment instanceof org.telegram.ui.LogoutActivity))fragment.presentFragment(new org.telegram.ui.LogoutActivity(),false,true);});
        instrumentation.waitForIdleSync();
        String title=org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.LogOutTitle);
        instrumentation.runOnMainSync(()->{ViewGroup form=(ViewGroup)org.telegram.ui.LaunchActivity.getLastFragment().getFragmentView();org.telegram.ui.Components.RecyclerListView list=(org.telegram.ui.Components.RecyclerListView)form.getChildAt(0);list.scrollToPosition(list.getAdapter().getItemCount()-2);});
        android.graphics.Rect row=new android.graphics.Rect();
        try{await(()->{if(systemPromptBlocks())return false;boolean[] found={false};instrumentation.runOnMainSync(()->{ViewGroup form=(ViewGroup)org.telegram.ui.LaunchActivity.getLastFragment().getFragmentView();org.telegram.ui.Components.RecyclerListView list=(org.telegram.ui.Components.RecyclerListView)form.getChildAt(0);if(list.isLayoutRequested()||list.isComputingLayout())return;View view=find(form,title);found[0]=view!=null&&view.getGlobalVisibleRect(row)&&!row.isEmpty();});return found[0];},"logout_ui_native_row_visible");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        check.verify(touch(row),"logout_ui_native_row_touched");
        boolean[] blocked={false};
        try{await(()->{if(systemPromptBlocks()){blocked[0]=true;return false;}if(dialog()!=null)return true;if(blocked[0]){check.verify(touch(row),"logout_ui_row_retouched_after_permission");blocked[0]=false;}return dialog()!=null;},"logout_ui_native_confirmation_shown");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        boolean[] started={false};
        instrumentation.runOnMainSync(()->{currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();started[0]=xyz.eclipseofficial.vyrn.compat.VyrnBridge.isLoggingOut(account);});
        check.verify(started[0],"logout_ui_confirmation_dispatch");
    }
    private boolean touch(android.graphics.Rect row){
        long time=android.os.SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,row.centerX(),row.centerY(),0),up=android.view.MotionEvent.obtain(time,time+50,android.view.MotionEvent.ACTION_UP,row.centerX(),row.centerY(),0);down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{return instrumentation.getUiAutomation().injectInputEvent(down,true)&&instrumentation.getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}
    }
    private Object field(Object target,String name){for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{java.lang.reflect.Field value=type.getDeclaredField(name);value.setAccessible(true);return value.get(target);}catch(NoSuchFieldException missing){}catch(Exception failure){throw new AssertionError("session_field_unavailable");}throw new AssertionError("session_field_unavailable");}
    private org.telegram.ui.SessionsActivity sessionsScreen(){return (org.telegram.ui.SessionsActivity)org.telegram.ui.LaunchActivity.getLastFragment();}
    private org.telegram.ui.SettingsActivity settingsScreen(){org.telegram.ui.ActionBar.BaseFragment last=org.telegram.ui.LaunchActivity.getLastFragment();return (org.telegram.ui.SettingsActivity)(last instanceof org.telegram.ui.MainTabsActivity?((org.telegram.ui.MainTabsActivity)last).getCurrentVisibleFragment():last);}
    private void openDevicesSetting() throws Exception {
        int[] position={-1};instrumentation.runOnMainSync(()->{org.telegram.ui.Components.UniversalRecyclerView list=(org.telegram.ui.Components.UniversalRecyclerView)field(settingsScreen(),"listView");for(int i=0;i<list.adapter.getItemCount();i++)if(list.adapter.getItem(i).id==8){position[0]=i;break;}if(position[0]<0)throw new AssertionError("devices_setting_missing");((androidx.recyclerview.widget.LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(position[0],org.telegram.messenger.AndroidUtilities.dp(160));});
        android.graphics.Rect bounds=new android.graphics.Rect();await(()->{if(systemPromptBlocks())return false;boolean[] ready={false};instrumentation.runOnMainSync(()->{org.telegram.ui.Components.UniversalRecyclerView list=(org.telegram.ui.Components.UniversalRecyclerView)field(settingsScreen(),"listView");androidx.recyclerview.widget.RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position[0]);ready[0]=!list.isLayoutRequested()&&holder!=null&&holder.itemView.getGlobalVisibleRect(bounds);});return ready[0];},"sessions_ui_devices_setting_visible");check.verify(touch(bounds),"sessions_ui_devices_setting_touched");
    }
    private void sessionRow(String name) throws Exception {
        int[] position={-1};instrumentation.runOnMainSync(()->{position[0]=(Integer)field(sessionsScreen(),name);if(position[0]<0)throw new AssertionError("session_row_missing");((org.telegram.ui.Components.RecyclerListView)field(sessionsScreen(),"listView")).scrollToPosition(position[0]);});
        android.graphics.Rect bounds=new android.graphics.Rect();
        await(()->{if(systemPromptBlocks())return false;boolean[] ready={false};instrumentation.runOnMainSync(()->{org.telegram.ui.Components.RecyclerListView list=(org.telegram.ui.Components.RecyclerListView)field(sessionsScreen(),"listView");androidx.recyclerview.widget.RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position[0]);ready[0]=!list.isLayoutRequested()&&!list.isComputingLayout()&&holder!=null&&holder.itemView.getGlobalVisibleRect(bounds)&&!bounds.isEmpty()&&!sessionsScreen().getParentLayout().isTransitionAnimationInProgress();});return ready[0];},"sessions_ui_"+name+"_visible");
        check.verify(touch(bounds),"sessions_ui_"+name+"_touched");
    }
    private boolean nodeText(android.view.accessibility.AccessibilityNodeInfo node,String text){if(node==null)return false;try{if(String.valueOf(node.getText()).contains(text)||String.valueOf(node.getContentDescription()).contains(text))return true;for(int i=0;i<node.getChildCount();i++)if(nodeText(node.getChild(i),text))return true;return false;}finally{node.recycle();}}
    private boolean windowText(String text){return nodeText(instrumentation.getUiAutomation().getRootInActiveWindow(),text);}
    private boolean confirmation(int message){boolean[] matches={false};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.AlertDialog alert=currentDialog();matches[0]=alert!=null&&org.telegram.messenger.LocaleController.getString(message).contentEquals(alert.getMessageTextView().getText());});return matches[0];}
    private void windowClick(String text) throws Exception {
        try{await(()->windowText(text),"sessions_ui_sheet_control_visible");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        boolean accepted=nodeClick(instrumentation.getUiAutomation().getRootInActiveWindow(),text);
        if(!accepted)captureLogoutFailure();check.verify(accepted,"sessions_ui_sheet_control_clicked");
    }
    private boolean nodeClick(android.view.accessibility.AccessibilityNodeInfo node,String text){
        if(node==null)return false;
        try{
            if(node.isVisibleToUser()&&(text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))){
                android.view.accessibility.AccessibilityNodeInfo target=node;
                while(target!=null){
                    if(target.isEnabled()&&target.isClickable()){boolean accepted=target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);if(target!=node)target.recycle();return accepted;}
                    android.view.accessibility.AccessibilityNodeInfo parent=target.getParent();if(target!=node)target.recycle();target=parent;
                }
            }
            for(int i=0;i<node.getChildCount();i++)if(nodeClick(node.getChild(i),text))return true;
            return false;
        }finally{node.recycle();}
    }
    private RestRepository loginSession(JSONObject fixture,int slot,String name) throws Exception {
        RestRepository other=new RestRepository(new TokenVault(instrumentation.getTargetContext(),slot));other.vault.clear();
        other.vault.save((JSONObject)other.request("POST","/v1/auth/login",new JSONObject().put("email","registration-"+fixture.getString("email")).put("password",fixture.getString("password")).put("device_id",other.vault.device()).put("device_name",name),false));return other;
    }
    private void sessions(JSONObject fixture) throws Exception {
        sessions(fixture,false);
    }
    private void networkStep(String step) throws Exception {
        java.io.File directory=instrumentation.getTargetContext().getFilesDir();java.nio.file.Files.write(new java.io.File(directory,"vyrn-test-network-request.txt").toPath(),step.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        await(()->{try{return step.equals(new String(java.nio.file.Files.readAllBytes(new java.io.File(directory,"vyrn-test-network-ack.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8));}catch(java.io.IOException pending){return false;}},"sessions_network_"+step);
    }
    private void sessions(JSONObject fixture,boolean network) throws Exception {
        register(fixture);
        int account=UserConfig.selectedAccount;RestRepository current=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));String identity=current.me().id;
        RestRepository other=loginSession(fixture,9030,"دستگاه دوم VYRN");
        try{await(()->{if(systemPromptBlocks())return false;boolean[] ready={false};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getLastFragment();ready[0]=fragment instanceof org.telegram.ui.MainTabsActivity&&fragment.getParentLayout()!=null&&!fragment.getParentLayout().isTransitionAnimationInProgress();});return ready[0];},"sessions_ui_main_ready");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        android.graphics.Rect tabBounds=new android.graphics.Rect();instrumentation.runOnMainSync(()->{View tab=((View[])field(org.telegram.ui.LaunchActivity.getLastFragment(),"tabs"))[2];check.verify(tab.getGlobalVisibleRect(tabBounds)&&tab.isShown(),"sessions_ui_settings_tab_visible");});check.verify(touch(tabBounds),"sessions_ui_settings_tab_touched");
        try{await(()->{if(systemPromptBlocks())return false;boolean[] ready={false};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.BaseFragment last=org.telegram.ui.LaunchActivity.getLastFragment();if(last instanceof org.telegram.ui.MainTabsActivity){org.telegram.ui.Components.ViewPagerFixed pager=(org.telegram.ui.Components.ViewPagerFixed)field(last,"viewPager");ready[0]=((org.telegram.ui.MainTabsActivity)last).getCurrentVisibleFragment() instanceof org.telegram.ui.SettingsActivity&&!pager.isManualScrolling()&&!pager.isTouch()&&!last.getParentLayout().isTransitionAnimationInProgress();}});return ready[0];},"sessions_ui_settings_ready");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        openDevicesSetting();
        try{await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof org.telegram.ui.SessionsActivity&&sessionsScreen().getSessionsCount()==2&&!(Boolean)field(sessionsScreen(),"loading"));return ready[0];},"sessions_ui_two_sessions_loaded");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        instrumentation.runOnMainSync(()->check.verify((Integer)field(sessionsScreen(),"qrCodeRow")==-1&&(Integer)field(sessionsScreen(),"ttlRow")==-1,"sessions_ui_unsupported_controls_hidden"));
        if(network){
            networkStep("offline-load");instrumentation.runOnMainSync(()->sessionsScreen().loadSessions(false));
            await(()->{boolean[] failed={false};instrumentation.runOnMainSync(()->failed[0]=!(Boolean)field(sessionsScreen(),"loading")&&(Boolean)field(sessionsScreen(),"sessionLoadFailed")&&(Integer)field(sessionsScreen(),"sessionRetryRow")>=0);return failed[0];},"sessions_network_load_error_has_retry");
            instrumentation.runOnMainSync(()->check.verify(sessionsScreen().getSessionsCount()==2,"sessions_network_load_error_preserves_list"));check.verify(UserConfig.getInstance(account).isClientActivated()&&current.vault.load().getString("user_id").equals(identity),"sessions_network_load_error_preserves_account");
            synchronized(current.vault.guard()){sessionRow("sessionRetryRow");await(()->{boolean[] started={false};instrumentation.runOnMainSync(()->started[0]=(Boolean)field(sessionsScreen(),"loading"));return started[0];},"sessions_network_offline_retry_dispatched");}
            await(()->{boolean[] failed={false};instrumentation.runOnMainSync(()->failed[0]=!(Boolean)field(sessionsScreen(),"loading")&&(Boolean)field(sessionsScreen(),"sessionLoadFailed"));return failed[0];},"sessions_network_offline_retry_retains_error");
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.BaseFragment last=org.telegram.ui.LaunchActivity.getLastFragment();ready[0]=last instanceof org.telegram.ui.MainTabsActivity&&!last.getParentLayout().isTransitionAnimationInProgress();});return ready[0];},"sessions_network_back_to_settings_offline");openDevicesSetting();
            await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof org.telegram.ui.SessionsActivity&&!(Boolean)field(sessionsScreen(),"loading")&&(Boolean)field(sessionsScreen(),"sessionLoadFailed")&&(Integer)field(sessionsScreen(),"sessionRetryRow")>=0&&(Integer)field(sessionsScreen(),"currentSessionRow")==-1);return ready[0];},"sessions_network_initial_load_failure_has_retry");
            try{await(()->windowText(label("Could not load devices","فهرست دستگاه‌ها دریافت نشد")),"sessions_network_specific_load_feedback");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
            networkStep("online-retry");sessionRow("sessionRetryRow");await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=!(Boolean)field(sessionsScreen(),"loading")&&!(Boolean)field(sessionsScreen(),"sessionLoadFailed")&&(Integer)field(sessionsScreen(),"sessionRetryRow")==-1&&sessionsScreen().getSessionsCount()==2);return ready[0];},"sessions_network_online_retry_recovers_same_screen");
            networkStep("offline-revoke");sessionRow("otherSessionsStartRow");windowClick(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TerminateSession));await(()->dialog()!=null,"sessions_network_offline_revoke_confirmation");instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());await(()->visible(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.UnknownError)),"sessions_network_offline_revoke_feedback");instrumentation.runOnMainSync(()->check.verify(sessionsScreen().getSessionsCount()==2,"sessions_network_offline_revoke_preserves_list"));await(()->dialog()==null&&!windowText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Application)),"sessions_network_offline_revoke_windows_closed");
            networkStep("online-confirm");check.verify(other.me().id.equals(identity)&&current.me().id.equals(identity),"sessions_network_failed_revoke_preserves_both_sessions");
        }
        String application=org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Application);
        sessionRow("currentSessionRow");await(()->windowText(application),"sessions_ui_current_details");check.verify(!windowText(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TerminateSession)),"sessions_ui_current_has_no_revoke");instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);await(()->!windowText(application),"sessions_ui_current_sheet_closed");
        sessionRow("otherSessionsStartRow");try{await(()->windowText(application)&&windowText("دستگاه دوم VYRN"),"sessions_ui_other_device_metadata");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        windowClick(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TerminateSession));await(()->dialog()!=null,"sessions_ui_single_confirmation");instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick());await(()->dialog()==null,"sessions_ui_single_cancel_dialog_closed");check.verify(other.me().id.equals(identity),"sessions_ui_single_cancel_preserves_access");
        org.telegram.tgnet.TLRPC.TL_authorization[] displayed={null};long[] originalHash={0};
        instrumentation.runOnMainSync(()->{displayed[0]=(org.telegram.tgnet.TLRPC.TL_authorization)((java.util.ArrayList<?>)field(sessionsScreen(),"sessions")).get(0);originalHash[0]=displayed[0].hash;displayed[0].hash=Long.MAX_VALUE;});
        windowClick(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TerminateSession));await(()->dialog()!=null,"sessions_ui_failed_revoke_confirmation");instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
        await(()->visible(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.UnknownError)),"sessions_ui_failed_revoke_feedback");
        instrumentation.runOnMainSync(()->{check.verify(sessionsScreen().getSessionsCount()==2,"sessions_ui_failed_revoke_preserves_list");displayed[0].hash=originalHash[0];});check.verify(other.me().id.equals(identity),"sessions_ui_failed_revoke_preserves_access");await(()->dialog()==null&&!windowText(application),"sessions_ui_failed_revoke_windows_closed");sessionRow("otherSessionsStartRow");await(()->windowText(application),"sessions_ui_failed_revoke_can_retry");
        windowClick(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.TerminateSession));await(()->dialog()!=null,"sessions_ui_single_confirmation_again");instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
        await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=sessionsScreen().getSessionsCount()==1);return ready[0];},"sessions_ui_single_removed_after_response");
        await(()->dialog()==null&&!windowText(application),"sessions_ui_single_windows_closed");
        boolean denied=false;try{other.me();}catch(RestRepository.ApiException failure){denied=failure.status==401;}check.verify(denied,"sessions_ui_revoked_access_and_refresh_rejected");check.verify(current.me().id.equals(identity),"sessions_ui_current_preserved");
        other=loginSession(fixture,9030,"دستگاه دوم VYRN");RestRepository third=loginSession(fixture,9031,"دستگاه سوم VYRN");instrumentation.runOnMainSync(()->sessionsScreen().loadSessions(false));
        await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=sessionsScreen().getSessionsCount()==3&&!(Boolean)field(sessionsScreen(),"loading"));return ready[0];},"sessions_ui_three_sessions_loaded");sessionRow("terminateAllSessionsRow");await(()->confirmation(org.telegram.messenger.R.string.AreYouSureSessions),"sessions_ui_bulk_confirmation");instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick());await(()->dialog()==null,"sessions_ui_bulk_cancel_dialog_closed");check.verify(other.me().id.equals(identity)&&third.me().id.equals(identity),"sessions_ui_bulk_cancel_preserves_access");sessionRow("terminateAllSessionsRow");await(()->confirmation(org.telegram.messenger.R.string.AreYouSureSessions),"sessions_ui_bulk_confirmation_again");instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
        try{await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->ready[0]=sessionsScreen().getSessionsCount()==1&&!(Boolean)field(sessionsScreen(),"loading"));return ready[0];},"sessions_ui_bulk_refreshes_list");}catch(AssertionError failure){
            int count=((org.json.JSONArray)current.request("GET","/v1/sessions",null,true)).length();java.nio.file.Files.write(new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-sessions-count.txt").toPath(),String.valueOf(count).getBytes(java.nio.charset.StandardCharsets.UTF_8));captureLogoutFailure();throw failure;
        }
        for(RestRepository probe:new RestRepository[]{other,third}){denied=false;try{probe.me();}catch(RestRepository.ApiException failure){denied=failure.status==401;}check.verify(denied,"sessions_ui_bulk_revoked_access_and_refresh_rejected");probe.vault.clear();}check.verify(current.me().id.equals(identity)&&UserConfig.getInstance(account).isClientActivated(),"sessions_ui_bulk_preserves_current");
    }
    private void captureLogoutFailure() throws Exception {
        android.graphics.Bitmap screenshot=instrumentation.getUiAutomation().takeScreenshot();
        if(screenshot!=null){try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-logout-screen.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{screenshot.recycle();}}
        android.view.accessibility.AccessibilityNodeInfo window=instrumentation.getUiAutomation().getRootInActiveWindow();
        String owner=window==null?"none":String.valueOf(window.getPackageName());String[] fragment={"none"};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.BaseFragment last=org.telegram.ui.LaunchActivity.getLastFragment();fragment[0]=last==null?"none":last.getClass().getSimpleName();if(last!=null&&last.getParentLayout()!=null)fragment[0]+=" transition="+last.getParentLayout().isTransitionAnimationInProgress()+" swipe="+last.getParentLayout().isSwipeInProgress();});
        java.nio.file.Files.write(new java.io.File(instrumentation.getTargetContext().getFilesDir(),"vyrn-test-logout-window.txt").toPath(),(owner+"\n"+fragment[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private boolean systemPromptBlocks(){
        android.view.accessibility.AccessibilityNodeInfo window=instrumentation.getUiAutomation().getRootInActiveWindow();if(window==null)return true;
        String owner=String.valueOf(window.getPackageName());
        if(owner.equals("com.android.permissioncontroller")){
            boolean notifications=!window.findAccessibilityNodeInfosByText("notifications").isEmpty()||!window.findAccessibilityNodeInfosByText("اعلان").isEmpty();
            boolean photos=!window.findAccessibilityNodeInfosByText("photos").isEmpty()||!window.findAccessibilityNodeInfosByText("عکس").isEmpty();
            boolean audio=!window.findAccessibilityNodeInfosByText("audio").isEmpty()||!window.findAccessibilityNodeInfosByText("صوت").isEmpty();
            boolean contacts=!window.findAccessibilityNodeInfosByText("contacts").isEmpty()||!window.findAccessibilityNodeInfosByText("مخاطبین").isEmpty()||!window.findAccessibilityNodeInfosByText("مخاطبان").isEmpty();
            String kind=notifications?"notifications":photos?"photos":audio?"audio":contacts?"contacts":"";
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons=window.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/"+(notifications?"permission_allow_button":"permission_deny_button"));
            if(buttons.isEmpty()&&!kind.isEmpty())for(android.view.accessibility.AccessibilityNodeInfo node:window.findAccessibilityNodeInfosByText("ALLOW")){
                String text=String.valueOf(node.getText());
                if(node.isClickable()&&(notifications?text.equalsIgnoreCase("ALLOW"):text.matches("(?i)don.t allow"))){buttons=java.util.Collections.singletonList(node);break;}
            }
            if(!kind.isEmpty()&&!buttons.isEmpty())check.verify(buttons.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"logout_ui_permission_"+kind+(notifications?"_allowed":"_denied"));
        }
        return !owner.equals(instrumentation.getTargetContext().getPackageName());
    }
    private void logout(JSONObject fixture,boolean offline) throws Exception {
        int account=UserConfig.selectedAccount;
        check.verify(UserConfig.getInstance(account).isClientActivated(),"logout_ui_active_account");
        RestRepository current=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));JSONObject original=current.vault.load();
        if(offline){
            confirmLogout(account);await(()->!xyz.eclipseofficial.vyrn.compat.VyrnBridge.isLoggingOut(account),"logout_ui_offline_request_completed");
            check.verify(UserConfig.getInstance(account).isClientActivated()&&current.vault.load().getString("session_id").equals(original.getString("session_id")),"logout_ui_offline_preserves_session");return;
        }
        RestRepository originalProbe=new RestRepository(new TokenVault(instrumentation.getTargetContext(),9012));originalProbe.vault.save(original);
        RestRepository replacementProbe=new RestRepository(new TokenVault(instrumentation.getTargetContext(),9011));
        JSONObject replacement=(JSONObject)replacementProbe.request("POST","/v1/auth/login",new JSONObject().put("email",fixture.getString("email")).put("password",fixture.getString("password")+"new").put("device_id",replacementProbe.vault.device()).put("device_name","Logout race fixture"),false);replacementProbe.vault.save(replacement);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Boolean>> pending=new java.util.concurrent.atomic.AtomicReference<>();boolean[] cleaned={false};
        synchronized(current.vault.guard()){
            instrumentation.runOnMainSync(()->pending.set(xyz.eclipseofficial.vyrn.compat.VyrnBridge.logout(account,()->cleaned[0]=true)));
            current.vault.save(replacement);
        }
        check.verify(!pending.get().get(45,java.util.concurrent.TimeUnit.SECONDS)&&!cleaned[0],"logout_ui_changed_session_rejected");
        check.verify(current.vault.load().getString("session_id").equals(replacement.getString("session_id"))&&UserConfig.getInstance(account).isClientActivated(),"logout_ui_replacement_preserved");
        check.verify(originalProbe.me().id.equals(original.getString("user_id")),"logout_ui_original_not_revoked_by_stale_request");
        confirmLogout(account);await(()->!xyz.eclipseofficial.vyrn.compat.VyrnBridge.isLoggingOut(account),"logout_ui_confirmed_request_completed");
        check.verify(!UserConfig.getInstance(account).isClientActivated(),"logout_ui_native_config_cleared");
        check.verify(current.vault.load().length()==0&&current.vault.cachedSession()==null,"logout_ui_keystore_session_cleared");
        check.verify(originalProbe.me().id.equals(original.getString("user_id")),"logout_ui_other_session_survives");
        boolean revoked=false;try{replacementProbe.me();}catch(RestRepository.ApiException failure){revoked=failure.status==401;}check.verify(revoked,"logout_ui_server_session_revoked");
        String start=org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.StartMessaging);
        await(()->visible(start),"logout_ui_returns_to_welcome");
        android.graphics.Rect startBounds=new android.graphics.Rect();
        try{await(()->{boolean[] ready={false};instrumentation.runOnMainSync(()->{org.telegram.ui.ActionBar.BaseFragment fragment=org.telegram.ui.LaunchActivity.getLastFragment();View view=find(root,start);ready[0]=fragment instanceof org.telegram.ui.IntroActivity&&fragment.getParentLayout()!=null&&!fragment.getParentLayout().isTransitionAnimationInProgress()&&!fragment.getParentLayout().isSwipeInProgress()&&view!=null&&view.getGlobalVisibleRect(startBounds);});return ready[0];},"logout_ui_welcome_ready");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        check.verify(touch(startBounds),"logout_ui_start_touched");
        try{await(()->visible(label("Email","ایمیل")),"logout_ui_welcome_opens_login");}catch(AssertionError failure){captureLogoutFailure();throw failure;}
        originalProbe.vault.clear();replacementProbe.vault.clear();
    }
}
