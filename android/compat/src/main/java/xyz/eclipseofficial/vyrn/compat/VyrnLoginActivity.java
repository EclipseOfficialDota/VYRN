package xyz.eclipseofficial.vyrn.compat;

import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.TransformableLoginButtonView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.MainTabsActivity;

/** Email authentication hosted in Telegram's original navigation and themed controls. */
public final class VyrnLoginActivity extends BaseFragment {
    private boolean register;
    private EditTextBoldCursor email,password,name,invite;
    private View submit;
    private TextView toggle,status,title,subtitle;
    private boolean fa(){return "fa".equals(Locale.getDefault().getLanguage());}
    private String tr(String en,String fa){return fa()?fa:en;}
    @Override public View createView(Context context){
        actionBar.setAddToContainer(false);
        FrameLayout root=new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setPadding(0,0,0,AndroidUtilities.dp(88));scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(scroll,LayoutHelper.createFrame(-1,-1));
        LinearLayout form=new LinearLayout(context);form.setOrientation(LinearLayout.VERTICAL);form.setGravity(Gravity.CENTER_HORIZONTAL);int pad=AndroidUtilities.dp(16);form.setPadding(pad,AndroidUtilities.statusBarHeight+AndroidUtilities.dp(48),pad,AndroidUtilities.dp(96));scroll.addView(form);
        ImageView logo=new ImageView(context);logo.setImageResource(org.telegram.messenger.R.drawable.vyrn_mark);logo.setColorFilter(Theme.getColor(Theme.key_chats_actionBackground));logo.setContentDescription("Vyrn");logo.setScaleType(ImageView.ScaleType.FIT_CENTER);form.addView(logo,LayoutHelper.createLinear(88,88,Gravity.CENTER_HORIZONTAL,0,0,0,24));
        title=new TextView(context);title.setText(tr("Your email","ایمیل شما"));title.setTextSize(18);title.setTypeface(AndroidUtilities.bold());title.setGravity(Gravity.CENTER);title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));form.addView(title,LayoutHelper.createLinear(-1,-2,Gravity.CENTER_HORIZONTAL,16,0,16,8));
        subtitle=new TextView(context);subtitle.setText(tr("Enter your email and password to sign in to Vyrn.","برای ورود به Vyrn، ایمیل و رمز عبور خود را وارد کنید."));subtitle.setTextSize(14);subtitle.setGravity(Gravity.CENTER);subtitle.setLineSpacing(AndroidUtilities.dp(2),1);subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));form.addView(subtitle,LayoutHelper.createLinear(-1,-2,Gravity.CENTER_HORIZONTAL,16,0,16,24));
        email=field(context,form,tr("Email","ایمیل"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password=field(context,form,tr("Password","رمز عبور"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        name=field(context,form,tr("Display name","نام نمایشی"),InputType.TYPE_CLASS_TEXT);
        invite=field(context,form,tr("Invite code","کد دعوت"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ((View)name.getParent()).setVisibility(View.GONE);((View)invite.getParent()).setVisibility(View.GONE);
        TransformableLoginButtonView next=new TransformableLoginButtonView(context);next.setProgress(1);next.setColor(Theme.getColor(Theme.key_chats_actionIcon));next.setElevation(AndroidUtilities.dp(2));next.setRippleDrawable(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),1));submit=next;submit.setContentDescription(tr("Sign in","ورود"));root.addView(submit,LayoutHelper.createFrame(64,64,Gravity.BOTTOM|Gravity.RIGHT,0,0,16,16));submit.setOnClickListener(v->authenticate());
        toggle=new TextView(context);toggle.setGravity(Gravity.CENTER);toggle.setTextSize(14);toggle.setPadding(pad,pad,pad,pad);toggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));toggle.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector),2));toggle.setText(tr("Create account with invite","ثبت‌نام با دعوت"));form.addView(toggle,LayoutHelper.createLinear(-1,-2));toggle.setOnClickListener(v->{register=!register;((View)name.getParent()).setVisibility(register?View.VISIBLE:View.GONE);((View)invite.getParent()).setVisibility(register?View.VISIBLE:View.GONE);status.setText("");submit.setContentDescription(register?tr("Create account","ساخت حساب"):tr("Sign in","ورود"));title.setText(register?tr("Create your account","حساب خود را بسازید"):tr("Your email","ایمیل شما"));subtitle.setText(register?tr("Enter your details and invite code to join Vyrn.","مشخصات و کد دعوت خود را برای عضویت در Vyrn وارد کنید."):tr("Enter your email and password to sign in to Vyrn.","برای ورود به Vyrn، ایمیل و رمز عبور خود را وارد کنید."));toggle.setText(register?tr("I already have an account","حساب دارم"):tr("Create account with invite","ثبت‌نام با دعوت"));});
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);password.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_DONE&&submit.isEnabled()&&!register){authenticate();return true;}return false;});
        status=new TextView(context);status.setGravity(Gravity.CENTER);status.setTextSize(14);status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));form.addView(status,LayoutHelper.createLinear(-1,-2,16,8,16,0));
        fragmentView=root;return root;
    }
    private EditTextBoldCursor field(Context context,LinearLayout form,String hint,int type){
        OutlineTextContainerView outline=new OutlineTextContainerView(context);outline.setText(hint);
        EditTextBoldCursor field=new EditTextBoldCursor(context);field.setSingleLine(true);field.setTextSize(16);field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));field.setInputType(type);field.setBackground(null);field.setPadding(AndroidUtilities.dp(16),0,AndroidUtilities.dp(16),0);field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));field.setContentDescription(hint);field.setGravity((fa()?Gravity.RIGHT:Gravity.LEFT)|Gravity.CENTER_VERTICAL);outline.attachEditText(field);field.setOnFocusChangeListener((v,focused)->outline.animateSelection(focused||field.length()>0));outline.addView(field,LayoutHelper.createFrame(-1,-1));form.addView(outline,LayoutHelper.createLinear(-1,58,0,0,0,14));return field;
    }
    private void authenticate(){
        final String e=email.getText().toString().trim(),p=password.getText().toString(),n=name.getText().toString().trim(),i=invite.getText().toString();final boolean registration=register;
        if(e.isEmpty()||p.isEmpty()||(registration&&(n.isEmpty()||i.isEmpty()))){status.setText(tr("Complete the account details.","مشخصات حساب را کامل وارد کنید."));return;}
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
