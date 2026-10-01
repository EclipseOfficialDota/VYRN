package xyz.eclipseofficial.vyrn.compat;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.MainTabsActivity;

/** Email authentication hosted in Telegram's original navigation and themed controls. */
public final class VyrnLoginActivity extends BaseFragment {
    private boolean register;
    private EditTextBoldCursor email,password,name,invite;
    private Button submit,toggle;
    private TextView status;
    private boolean fa(){return "fa".equals(Locale.getDefault().getLanguage());}
    private String tr(String en,String fa){return fa()?fa:en;}
    @Override public View createView(Context context){
        actionBar.setTitle("VYRN");
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout form=new LinearLayout(context);form.setOrientation(LinearLayout.VERTICAL);int pad=AndroidUtilities.dp(24);form.setPadding(pad,pad,pad,pad);scroll.addView(form);
        TextView title=new TextView(context);title.setText(tr("Sign in to VYRN","ورود به VYRN"));title.setTextSize(26);title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));form.addView(title);
        email=field(context,form,tr("Email","ایمیل"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password=field(context,form,tr("Password","رمز عبور"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        name=field(context,form,tr("Display name","نام نمایشی"),InputType.TYPE_CLASS_TEXT);
        invite=field(context,form,tr("Invite code","کد دعوت"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        name.setVisibility(View.GONE);invite.setVisibility(View.GONE);
        submit=new Button(context);submit.setText(tr("Sign in","ورود"));form.addView(submit);submit.setOnClickListener(v->authenticate());
        toggle=new Button(context);toggle.setText(tr("Create account with invite","ثبت‌نام با دعوت"));form.addView(toggle);toggle.setOnClickListener(v->{register=!register;name.setVisibility(register?View.VISIBLE:View.GONE);invite.setVisibility(register?View.VISIBLE:View.GONE);submit.setText(register?tr("Create account","ساخت حساب"):tr("Sign in","ورود"));toggle.setText(register?tr("I already have an account","حساب دارم"):tr("Create account with invite","ثبت‌نام با دعوت"));});
        status=new TextView(context);status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));form.addView(status);
        fragmentView=scroll;return scroll;
    }
    private EditTextBoldCursor field(Context context,LinearLayout form,String hint,int type){EditTextBoldCursor field=new EditTextBoldCursor(context);field.setSingleLine(true);field.setTextSize(18);field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));field.setHint(hint);field.setInputType(type);form.addView(field,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(64)));return field;}
    private void authenticate(){
        final String e=email.getText().toString().trim(),p=password.getText().toString(),n=name.getText().toString().trim(),i=invite.getText().toString();final boolean registration=register;
        submit.setEnabled(false);toggle.setEnabled(false);status.setText(tr("Connecting…","در حال اتصال…"));
        VyrnBridge.execute(()->{
            try{
                VyrnBridge bridge=VyrnBridge.forAccount(currentAccount);
                JSONObject input=new JSONObject().put("email",e).put("password",p).put("device_id",bridge.repository.vault.device()).put("device_name",Build.MANUFACTURER+" "+Build.MODEL);
                if(registration)input.put("display_name",n).put("invite_code",i);
                JSONObject tokens=(JSONObject)bridge.repository.request("POST",registration?"/v1/auth/register":"/v1/auth/login",input,false);
                bridge.repository.vault.save(tokens);TLRPC.User user=bridge.mapUser(bridge.repository.me());
                AndroidUtilities.runOnUIThread(()->{
                    if(isFinished)return;
                    UserConfig config=UserConfig.getInstance(currentAccount);config.syncContacts=false;config.setCurrentUser(user);config.saveConfig(true);MessagesController.getInstance(currentAccount).putUser(user,false);
                    Runnable open=()->{MainTabsActivity main=new MainTabsActivity();main.prepareDialogsActivity(null);presentFragment(main,true);NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);};
                    String recovery=tokens.optString("recovery_code");
                    if(!recovery.isEmpty()){AlertDialog.Builder dialog=new AlertDialog.Builder(getParentActivity());dialog.setTitle(tr("Save your recovery code","کد بازیابی را ذخیره کنید"));dialog.setMessage(recovery);dialog.setPositiveButton(tr("Saved","ذخیره کردم"),(d,which)->open.run());AlertDialog alert=dialog.create();alert.setCancelable(false);showDialog(alert);}else open.run();
                });
            }catch(Exception failure){AndroidUtilities.runOnUIThread(()->{if(isFinished)return;submit.setEnabled(true);toggle.setEnabled(true);status.setText(tr("Could not sign in. Check your connection and account details.","ورود انجام نشد. اتصال و مشخصات حساب را بررسی کنید."));});}
        });
    }
}
