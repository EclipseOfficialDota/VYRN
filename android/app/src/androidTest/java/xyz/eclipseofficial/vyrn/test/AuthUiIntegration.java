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
    private String label(String en,String persian){return fa?persian:en;}
    private View find(View view,String value){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()) || view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    private boolean visible(String value){boolean[] found={false};instrumentation.runOnMainSync(()->found[0]=find(root,value)!=null);return found[0];}
    private void click(String value){instrumentation.runOnMainSync(()->{View view=find(root,value);if(view==null||!view.isEnabled())throw new AssertionError("control_unavailable");view.performClick();});instrumentation.waitForIdleSync();}
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
        instrumentation.runOnMainSync(()->currentDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick());
        await(()->{boolean[] main={false};instrumentation.runOnMainSync(()->main[0]=org.telegram.ui.LaunchActivity.getLastFragment() instanceof org.telegram.ui.MainTabsActivity);return main[0];},"registration_ui_saved_opens_main");
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
        RestRepository current=new RestRepository(new TokenVault(instrumentation.getTargetContext(),account));
        check.verify(current.me().id.equals(registered.getString("user_id")),"auth_ui_authenticated_identity");setup.vault.clear();
    }
}
