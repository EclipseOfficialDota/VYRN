$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$upstream=Join-Path $root 'upstream'
$pin=(Get-Content (Join-Path $root 'upstream.lock.json') -Raw | ConvertFrom-Json).commit
$head=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream rev-parse HEAD).Trim()
if($head -ne $pin){throw 'Unexpected Telegram revision'}
function Write-Text([string]$Path,[string]$Text){
 if(!(Test-Path -LiteralPath $Path) -or [IO.File]::ReadAllText($Path) -cne $Text){[IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))}
}
function Edit([string]$Relative,[scriptblock]$Transform){$p=Join-Path $upstream $Relative;$old=[IO.File]::ReadAllText($p);$new=& $Transform $old;Write-Text $p $new}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java' {
 param($t)
 if(!$t.Contains('private void vyrnFinishVoiceRecord()') -and !$t.Contains('private boolean vyrnFinishVoiceRecord(')){
  $frame='if (fileBuffer.position() == fileBuffer.limit() || flush) {'
  $write='writeFrame(fileBuffer, !flush ? fileBuffer.limit() : finalBuffer.position())'
  $pause="recordQueue.postRunnable(() -> {`n                    stopRecord();"
  $finish="fileEncodingQueue.postRunnable(() -> {`n                stopRecord();"
  $method='    private void stopRecordingInternal(final int send, boolean notify, int scheduleDate, boolean once, long payStars) {'
  $t=$t.Replace("`r`n","`n")
  foreach($marker in @($frame,$write,$pause,$finish,$method)){if(([regex]::Matches($t,[regex]::Escape($marker))).Count -ne 1){throw 'Pinned voice buffering/finalization marker changed'}}
  $t=$t.Replace($frame,'if (fileBuffer.position() == fileBuffer.limit()) {').Replace($write,'writeFrame(fileBuffer, fileBuffer.limit())')
  $t=$t.Replace($pause,"fileEncodingQueue.postRunnable(() -> {`n                    vyrnFinishVoiceRecord();").Replace($finish,"fileEncodingQueue.postRunnable(() -> {`n                vyrnFinishVoiceRecord();")
  $helper=@'
    private void vyrnFinishVoiceRecord() {
        final int bytes = fileBuffer.position();
        try {
            if (writeFrame(fileBuffer, bytes) == 0) throw new IllegalStateException("voice_final_frame_failed");
            recordTimeCount += bytes / 2 / (sampleRate / 1000);
            fileBuffer.rewind();
        } finally {
            stopRecord();
        }
    }

'@
  $t=$t.Replace($method,$helper+$method)
 }
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java' {
 param($t)
 if(!$t.Contains('private volatile boolean vyrnVoiceWriteFailed;')){
  $t=$t.Replace("`r`n","`n")
  $t=$t.Replace('    private DispatchQueue fileEncodingQueue;',"    private volatile boolean vyrnVoiceWriteFailed;`n    private DispatchQueue fileEncodingQueue;")
  $t=$t.Replace('if (startRecord(recordingAudioFile.getPath(), sampleRate) == 0) {',"vyrnVoiceWriteFailed = false;`n                if (startRecord(recordingAudioFile.getPath(), sampleRate) == 0) {")
  $t=$t.Replace('while (finalBuffer.hasRemaining()) {','while (!vyrnVoiceWriteFailed && finalBuffer.hasRemaining()) {')
  $t=$t.Replace('FileLog.e("writing frame failed");',"vyrnVoiceWriteFailed = true;`n                                    fileBuffer.rewind();")
  $old=@'
    private void vyrnFinishVoiceRecord() {
        final int bytes = fileBuffer.position();
        try {
            if (writeFrame(fileBuffer, bytes) == 0) throw new IllegalStateException("voice_final_frame_failed");
            recordTimeCount += bytes / 2 / (sampleRate / 1000);
            fileBuffer.rewind();
        } finally {
            stopRecord();
        }
    }
'@
  if(!$t.Contains($old)){throw 'Pinned voice storage finalizer marker changed'}
  $new=@'
    private boolean vyrnFinishVoiceRecord(int account, int guid, File previous, File recorded) {
        final int bytes = fileBuffer.position();
        boolean saved = false;
        try {
            saved = !vyrnVoiceWriteFailed && writeFrame(fileBuffer, bytes) != 0;
            if (saved) recordTimeCount += bytes / 2 / (sampleRate / 1000);
        } finally {
            fileBuffer.rewind();
            stopRecord();
        }
        if (!saved) {
            for (File file : new File[]{previous, recorded}) if (file != null) {
                AutoDeleteMediaTask.unlockFile(file);
                file.delete();
            }
            AndroidUtilities.runOnUIThread(() -> {
                requestRecordAudioFocus(false);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.recordStartError, guid);
                android.widget.Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.isRTL ? "ذخیرهٔ ویس انجام نشد؛ فضای حافظه و دسترسی ذخیره‌سازی را بررسی کنید." : "Voice could not be saved. Check storage space and access.",
                    android.widget.Toast.LENGTH_LONG).show();
            });
        }
        return saved;
    }
'@
  $t=$t.Replace($old,$new)
  $t=$t.Replace("                    vyrnFinishVoiceRecord();","                    if (!vyrnFinishVoiceRecord(recordingCurrentAccount, recordingGuid, recordingPrevAudioFile, recordingAudioFile)) {`n                        cleanRecording(true);`n                        return;`n                    }")
  $t=$t.Replace('            final File recordingPrevAudioFileToSend_ = recordingPrevAudioFile;',"            final File recordingPrevAudioFileToSend_ = recordingPrevAudioFile;`n            final int vyrnAccount = recordingCurrentAccount, vyrnGuid = recordingGuid;")
  $t=$t.Replace('                vyrnFinishVoiceRecord();','                if (!vyrnFinishVoiceRecord(vyrnAccount, vyrnGuid, recordingPrevAudioFileToSend_, recordingAudioFileToSend_)) return;')
 }
 $t
}
Edit 'TMessagesProj/jni/audio.c' {
 param($t)
 if(!$t.Contains('static int vyrnWriteFramePacket(')){
  $start='int writeFrame(uint8_t *framePcmBytes, uint32_t frameByteCount, int end) {'
  $end='JNIEXPORT jint Java_org_telegram_messenger_MediaController_startRecord('
  $from=$t.IndexOf($start)
  if($from -lt 0 -or !$t.Contains('const opus_int32 frame_size = 960;')){throw 'Pinned native voice encoder marker missing'}
  $to=$t.IndexOf($end,$from)
  if($to -lt 0){throw 'Pinned native voice encoder end marker missing'}
  $section=$t.Substring($from,$to-$from)
  if(([regex]::Matches($section,[regex]::Escape('total_samples += nb_samples;'))).Count -ne 1){throw 'Pinned voice sample accounting changed'}
  $section=$section.Replace($start,'static int vyrnWriteFramePacket(uint8_t *framePcmBytes, uint32_t frameByteCount, int end, int padding) {').Replace('total_samples += nb_samples;','if (!padding) total_samples += nb_samples;')
  $tail=@'
// Drain encoder lookahead without extending the recorded PCM duration.
// Empty/near-full final input must still produce valid EOS audio packets.
int writeFrame(uint8_t *framePcmBytes, uint32_t frameByteCount, int end) {
    if (!_encoder || !_fileOs || frameByteCount > frame_size * 2 || (!framePcmBytes && frameByteCount)) return 0;
    if (!end) return vyrnWriteFramePacket(framePcmBytes, frameByteCount, 0, 0);
    const ogg_int64_t target = ((total_samples + frameByteCount / 2) * 48000 + rate - 1) / rate + header.preskip;
    const ogg_int64_t step = frame_size * 48000 / coding_rate;
    if (frameByteCount) {
        const int last = enc_granulepos + step >= target;
        if (!vyrnWriteFramePacket(framePcmBytes, frameByteCount, last, 0)) return 0;
        if (last) return 1;
    }
    opus_int16 silence[960] = {0};
    do {
        if (!vyrnWriteFramePacket((uint8_t *)silence, sizeof(silence), enc_granulepos + step >= target, 1)) return 0;
    } while (enc_granulepos < target);
    return 1;
}

'@
  $t=$t.Substring(0,$from)+$section+$tail+$t.Substring($to)
 }
 $t
}
Edit 'TMessagesProj/jni/audio.c' {
 param($t)
 if(!$t.Contains('if (last) return fflush(_fileOs) == 0;')){
  $t=$t.Replace('if (last) return 1;','if (last) return fflush(_fileOs) == 0;')
  $marker="} while (enc_granulepos < target);`n    return 1;"
  $t=$t.Replace("`r`n","`n")
  if(!$t.Contains($marker)){throw 'Pinned voice flush marker changed'}
  $t=$t.Replace($marker,"} while (enc_granulepos < target);`n    return fflush(_fileOs) == 0;")
 }
 $t
}
function Disable-AiControls([string]$t){
 $available='xyz.eclipseofficial.vyrn.compat.VyrnBridge.aiEditorAvailable()'
 foreach($button in @('aiButton','topAiButton','aiStyleButton')){
  $description="(?m)^([ ]*$button\.setContentDescription\([^\n]+\);)$"
  $t=[regex]::Replace($t,$description,('$1'+"`n        $button.setEnabled($available);`n        $button.setAlpha($available ? 1.0f : 0.4f);"))
  $click="$button.setOnClickListener(v -> {"
  $t=$t.Replace($click,$click+"`n            if (!$available) return;")
 }
 $show='        final boolean show = '
 $start=$t.IndexOf('    private void showAiButton(boolean show_) {')
 if($start -ge 0){
  $position=$t.IndexOf($show,$start)
  if($position -lt 0){throw 'Pinned AI visibility marker missing'}
  $t=$t.Substring(0,$position)+$show+"$available && "+$t.Substring($position+$show.Length)
 }
 $t=$t.Replace('aiButton.setOnClickListener(v -> delegate.onAi());',"aiButton.setOnClickListener(v -> { if ($available) delegate.onAi(); });")
 $t=$t.Replace('aiStyleButton.setOnClickListener(v -> delegate.onAiStyle());',"aiStyleButton.setOnClickListener(v -> { if ($available) delegate.onAiStyle(); });")
 $t=$t.Replace('aiStyleButton.setOnClickListener(v -> onAiStyleSelection());',"aiStyleButton.setOnClickListener(v -> { if ($available) onAiStyleSelection(); });")
 $t
}

Edit 'settings.gradle' {
 param($t)
 $t=$t -replace "(?m)^include ':TMessagesProj_App[^']*'\r?\n",''
 if(!$t.Contains("include ':VyrnApp'")){$t+="`ninclude ':VyrnApp'`nproject(':VyrnApp').projectDir = file('../app')`n"}
 $t
}
Edit 'build.gradle' {param($t) $t -replace "(?m)^.*classpath '(com.google.gms:google-services|com.huawei.agconnect:agcp|com.google.firebase:firebase-crashlytics-gradle):[^']*'.*\r?\n",''}
Edit 'gradle.properties' {
 param($t)
 $t=$t -replace '(?m)^APP_PACKAGE=.*','APP_PACKAGE=xyz.eclipseofficial.vyrn' -replace '(?m)^org.gradle.jvmargs=.*','org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8'
 $t=$t -replace '(?m)^RELEASE_.*\r?\n',''
 $t
}
Edit 'TMessagesProj/build.gradle' {
 param($t)
 $t=$t -replace "apply plugin: 'com.google.gms.google-services'",''
 $t=$t -replace 'minSdkVersion 21','minSdkVersion 26'
 $t=$t.Replace('sourceCompatibility JavaVersion.VERSION_1_8','sourceCompatibility JavaVersion.VERSION_17').Replace('targetCompatibility JavaVersion.VERSION_1_8','targetCompatibility JavaVersion.VERSION_17')
 if(!$t.Contains("com.squareup.okhttp3:okhttp:5.3.0")){$t+="`ndependencies { implementation 'com.squareup.okhttp3:okhttp:5.3.0' }`n"}
 $signalVersion=(Get-Content (Join-Path $root 'libsignal.lock.json') -Raw | ConvertFrom-Json).version
 if($signalVersion -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid pinned libsignal version'}
 if($t.Contains('VYRN_SIGNAL_DEPENDENCIES')){
 $dependencyPattern="(?m)^dependencies \{ (?:api|implementation) 'org\.signal:libsignal-client:[0-9.]+'; implementation 'org\.signal:libsignal-android:[0-9.]+' \}\r?$"
 if(![regex]::IsMatch($t,$dependencyPattern)){throw 'Generated libsignal dependency marker is inconsistent'}
 $t=[regex]::Replace($t,$dependencyPattern,"dependencies { api 'org.signal:libsignal-client:$signalVersion'; implementation 'org.signal:libsignal-android:$signalVersion' }")
 }else{
 $t+="`n// VYRN_SIGNAL_DEPENDENCIES`nrepositories { maven { url 'https://build-artifacts.signal.org/libraries/maven/'; content { includeGroup 'org.signal' } } }`ndependencies { api 'org.signal:libsignal-client:$signalVersion'; implementation 'org.signal:libsignal-android:$signalVersion' }`n"
 }
 if(!$t.Contains('VYRN_API_URL')){
 $extra=@'

android {
    defaultConfig {
        ndk { abiFilters.addAll((findProperty('VYRN_ABIS') ?: 'arm64-v8a,armeabi-v7a').split(',')) }
        buildConfigField 'String', 'VYRN_API_URL', '"' + (findProperty('VYRN_API_URL') ?: 'https://api.vyrn.eclipseofficial.xyz') + '"'
    }
    sourceSets.main.java.srcDirs += ['../../compat/src/main/java']
}
'@
 $t+=$extra
 };$t
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java") -join "`n"
 $t=$t -replace '(?s)private BaseFragment getClientNotActivatedFragment\(\) \{.*?\n    \}', 'private BaseFragment getClientNotActivatedFragment() { return new xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity(); }'
 $pending='xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity.hasPendingRecovery(currentAccount)'
 $startup="if (!UserConfig.getInstance(currentAccount).isClientActivated()) {`n                actionBarLayout.addFragmentToStack(getClientNotActivatedFragment());"
 if(!$t.Contains($startup)){throw 'Pinned onboarding startup marker missing'}
 $t=$t.Replace($startup,"if (!UserConfig.getInstance(currentAccount).isClientActivated() || $pending) {`n                actionBarLayout.addFragmentToStack(getClientNotActivatedFragment());")
 $t=$t.Replace("if (savedInstanceState != null) {`n                    String fragmentName", "if (savedInstanceState != null && !$pending) {`n                    String fragmentName")
 $t=$t.Replace('MainTabsActivity mainTabsActivity = dialogsActivityProvider.provide(null);',"BaseFragment mainTabsActivity = $pending ? new xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity() : dialogsActivityProvider.provide(null);")
 $intentSignature='private boolean handleIntent(Intent intent, boolean isNew, boolean restore, boolean fromPassword, Browser.Progress progress, boolean rebuildFragments, boolean openedTelegram) {'
 if(!$t.Contains($intentSignature)){throw 'Pinned onboarding intent marker missing'}
 $resume="    protected void onResume() {`n        super.onResume();"
 if(!$t.Contains($resume)){throw 'Pinned visible activity resume marker missing'}
 $t=$t.Replace($resume,$resume+"`n        getWindow().getDecorView().post(() -> { if (!isFinishing() && !isDestroyed()) xyz.eclipseofficial.vyrn.compat.VyrnNotificationsService.refresh(); });")
 $t.Replace($intentSignature,"$intentSignature`n        if ($pending) { actionBarLayout.showLastFragment(); return false; }")
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java") -join "`n"
 $marker='public void saveConfig(boolean withFile) {'
 if(!$t.Contains($marker)){throw 'Pinned current-user persistence marker missing'}
 $method=@'
/** Persist a server-confirmed VYRN profile before reporting success or closing its editor. */
    public boolean vyrnSaveCurrentUser(TLRPC.User expectedUser) {
        synchronized (sync) {
            if (!configLoaded || currentUser == null || currentUser != expectedUser) return false;
            SerializedData data = new SerializedData();
            try {
                currentUser.serializeToStream(data);
                return getPreferences().edit().putString("user", Base64.encodeToString(data.toByteArray(), Base64.DEFAULT)).putBoolean("syncContacts", syncContacts).commit();
            } catch (Exception failure) { return false; }
            finally { data.cleanup(); }
        }
    }

'@
 $t.Replace($marker,$method+"`n    "+$marker)
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/ChangeNameActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/ChangeNameActivity.java") -join "`n"
 $t=$t.Replace('private View doneButton;','private View doneButton; private boolean vyrnSaving; private long vyrnProfileOrigin;')
 $t=$t.Replace('public View createView(Context context) {',"public View createView(Context context) {`n        vyrnProfileOrigin = UserConfig.getInstance(currentAccount).getClientUserId();")
 $marker="saveName();`n                        finishFragment();"
 if(!$t.Contains($marker)){throw 'Pinned name confirmation marker missing'}
 $t=$t.Replace($marker,'saveName();')
 $body=@'
private void saveName() {
        if (vyrnSaving || firstNameField.getText() == null || lastNameField.getText() == null) return;
        final long origin = vyrnProfileOrigin;
        if (origin == 0 || UserConfig.getInstance(currentAccount).getClientUserId() != origin) return;
        TL_account.updateProfile request = new TL_account.updateProfile(); request.flags = 3;
        request.first_name = firstNameField.getText().toString(); request.last_name = lastNameField.getText().toString();
        vyrnSaving = true; doneButton.setEnabled(false);
        ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            vyrnSaving = false; doneButton.setEnabled(true);
            if (origin == 0 || UserConfig.getInstance(currentAccount).getClientUserId() != origin) return;
            if (error != null || !(response instanceof TLRPC.User) || ((TLRPC.User) response).id != origin) {
                org.telegram.ui.Components.AlertsCreator.showSimpleAlert(this, LocaleController.isRTL ? "ذخیرهٔ نام انجام نشد. دوباره تلاش کنید." : "Could not save your name. Try again."); return;
            }
            TLRPC.User accepted = (TLRPC.User) response;
            TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser(); if (user == null) return;
            String previousFirst = user.first_name, previousLast = user.last_name;
            int previousFlags = user.flags;
            user.first_name = accepted.first_name; user.last_name = accepted.last_name;
            user.flags |= 2 | 4;
            if (!UserConfig.getInstance(currentAccount).vyrnSaveCurrentUser(user)) {
                user.first_name = previousFirst; user.last_name = previousLast;
                user.flags = previousFlags;
                org.telegram.ui.Components.AlertsCreator.showSimpleAlert(this, LocaleController.isRTL ? "نام روی سرور ذخیره شد، اما ذخیرهٔ محلی انجام نشد. دوباره تلاش کنید." : "Your name was saved on the server, but local storage failed. Try again."); return;
            }
            if (UserConfig.getInstance(currentAccount).getCurrentUser() != user) return;
            MessagesController.getInstance(currentAccount).putUser(user, false);
            java.util.ArrayList<TLRPC.User> users = new java.util.ArrayList<>(); users.add(user);
            org.telegram.messenger.MessagesStorage.getInstance(currentAccount).putUsersAndChats(users, null, true, true);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
            finishFragment();
        }));
    }
'@
 if(!$t.Contains('private void saveName() {')){throw 'Pinned name save method missing'}
 $t=[regex]::Replace($t,'(?s)private void saveName\(\) \{.*?\n    \}',$body)
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/ChangeBioActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/ChangeBioActivity.java") -join "`n"
 $t=$t.Replace('private View doneButton;','private View doneButton; private long vyrnProfileOrigin; private boolean vyrnSaving, vyrnLoading, vyrnDestroyed;')
 $t=$t.Replace('public View createView(Context context) {',"public View createView(Context context) {`n        if (vyrnProfileOrigin == 0) vyrnProfileOrigin = UserConfig.getInstance(currentAccount).getClientUserId();")
 $t=$t.Replace('getMessagesController().getAboutLimit()','280')
 $t=$t.Replace('MessagesController.getInstance(currentAccount).getUserFull(UserConfig.getInstance(currentAccount).getClientUserId())','getMessagesController().getUserFull(vyrnProfileOrigin)')
 $t=$t.Replace('if (userFull != null && userFull.about != null) {','if (vyrnHasOrigin() && userFull != null && userFull.about != null) {')
 $t=$t.Replace('public class ChangeBioActivity extends BaseFragment {','public class ChangeBioActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {')
 $loading=@'
    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        return true;
    }
    @Override public void onFragmentDestroy() {
        vyrnDestroyed = true;
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        super.onFragmentDestroy();
    }
    private boolean vyrnHasOrigin() {
        return !vyrnDestroyed && vyrnProfileOrigin != 0 && getUserConfig().getClientUserId() == vyrnProfileOrigin;
    }
    private void vyrnLoadProfile() {
        if (vyrnLoading || !vyrnHasOrigin()) return;
        vyrnLoading = true; firstNameField.setEnabled(false); doneButton.setEnabled(false);
        helpTextView.setText(LocaleController.getString(R.string.Loading));
        getMessagesController().loadFullUser(getUserConfig().getCurrentUser(), classGuid, true, null, error -> {
            if (!vyrnHasOrigin() || !vyrnLoading) return;
            vyrnLoading = false;
            String message = LocaleController.isRTL ? "بارگیری پروفایل انجام نشد. دوباره تلاش کنید." : "Your profile could not be loaded. Try again.";
            helpTextView.setText(message);
            if (getParentActivity() != null) showDialog(new AlertDialog.Builder(getParentActivity())
                .setMessage(message)
                .setPositiveButton(LocaleController.getString(R.string.Retry), (dialog, which) -> vyrnLoadProfile())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> finishFragment()).create());
        });
    }
    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.userInfoDidLoad || !vyrnLoading || !vyrnHasOrigin() || (Long) args[0] != vyrnProfileOrigin) return;
        TLRPC.UserFull profile = (TLRPC.UserFull) args[1];
        if (profile == null || profile.id != vyrnProfileOrigin) return;
        vyrnLoading = false;
        firstNameField.setText(profile.about == null ? "" : profile.about);
        firstNameField.setSelection(firstNameField.length());
        firstNameField.setEnabled(true); doneButton.setEnabled(true);
        helpTextView.setText(AndroidUtilities.replaceTags(LocaleController.getString(R.string.UserBioInfo)));
    }

'@
 $t=$t.Replace('    @Override'+"`n"+'    public View createView(Context context) {',$loading+'    @Override'+"`n"+'    public View createView(Context context) {')
 $ready='        return fragmentView;'
 $t=$t.Replace($ready,"        if (userFull == null || !vyrnHasOrigin()) { firstNameField.setEnabled(false); doneButton.setEnabled(false); vyrnLoadProfile(); }`n"+$ready)
 $t=$t.Replace('private void saveName() {',"private void saveName() {`n        final long origin = vyrnProfileOrigin;`n        if (vyrnSaving || !firstNameField.isEnabled() || !vyrnHasOrigin()) return;")
 $t=$t.Replace('final AlertDialog progressDialog = new AlertDialog',"final String previousAbout = userFull.about; final int previousFlags = userFull.flags;`n        vyrnSaving = true; doneButton.setEnabled(false);`n        final AlertDialog progressDialog = new AlertDialog")
 $marker="userFull.about = newName;`n                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, user.id, userFull);`n                    finishFragment();"
 if(!$t.Contains($marker)){throw 'Pinned biography confirmation marker missing'}
 $body=@'
if (UserConfig.getInstance(currentAccount).getClientUserId() != origin || user.id != origin) { vyrnSaving = false; doneButton.setEnabled(true); return; }
                    userFull.about = newName; userFull.flags |= 2;
                    org.telegram.messenger.MessagesStorage storage = org.telegram.messenger.MessagesStorage.getInstance(currentAccount);
                    storage.updateUserInfo(userFull, false);
                    storage.getStorageQueue().postRunnable(() -> {
                        java.util.HashSet<Long> ids = new java.util.HashSet<>(); ids.add(origin);
                        boolean persisted = false;
                        for (TLRPC.UserFull saved : storage.loadUserInfos(ids)) if (saved.id == origin && newName.equals(saved.about)) persisted = true;
                        final boolean confirmed = persisted;
                        AndroidUtilities.runOnUIThread(() -> {
                            vyrnSaving = false; doneButton.setEnabled(true);
                            if (UserConfig.getInstance(currentAccount).getClientUserId() != origin) return;
                            if (!confirmed) {
                                userFull.about = previousAbout; userFull.flags = previousFlags;
                                AlertsCreator.showSimpleAlert(this, LocaleController.isRTL ? "توضیحات روی سرور ذخیره شد، اما ذخیرهٔ محلی انجام نشد. دوباره تلاش کنید." : "Your bio was saved on the server, but local storage failed. Try again."); return;
                            }
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.userInfoDidLoad, origin, userFull);
                            finishFragment();
                        });
                    });
'@
 $t=$t.Replace($marker,$body)
 $t=$t.Replace('AlertsCreator.processError(currentAccount, error, ChangeBioActivity.this, req);',"vyrnSaving = false; doneButton.setEnabled(true);`n                    if (UserConfig.getInstance(currentAccount).getClientUserId() == origin) AlertsCreator.processError(currentAccount, error, ChangeBioActivity.this, req);")
 $t=$t.Replace('progressDialog.setOnCancelListener(dialog -> ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true));','progressDialog.setOnCancelListener(dialog -> { vyrnSaving = false; doneButton.setEnabled(true); ConnectionsManager.getInstance(currentAccount).cancelRequest(reqId, true); });')
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/CodepointsLengthInputFilter.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/Components/CodepointsLengthInputFilter.java") -join "`n"
 $start=$t.IndexOf('            keep += start;')
 $end=$t.IndexOf('            return source.subSequence(start, keep);', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned code-point truncation marker missing'}
 $end+='            return source.subSequence(start, keep);'.Length
 $t.Substring(0,$start)+'            return source.subSequence(start, Character.offsetByCodePoints(source, start, keep));'+$t.Substring($end)
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java") -join "`n"
 $field='    private long currentFileId;'
 $constructor='        currentAccount = instance;'
 $key='fileKey = Utilities.MD5(uploadingFilePath + (isEncrypted ? "enc" : ""));'
 $send='sendRequest(finalRequest, (response, error) -> {'
 foreach($marker in @($field,$constructor,$key,$send,'        state = 2;')){if(!$t.Contains($marker)){throw 'Pinned native upload marker missing'}}
 $t=$t.Replace($field,$field+"`n    private final xyz.eclipseofficial.vyrn.compat.VyrnBridge.UploadIdentity vyrnUploadIdentity;")
 $t=$t.Replace($constructor,$constructor+"`n        vyrnUploadIdentity = xyz.eclipseofficial.vyrn.compat.VyrnBridge.captureUploadIdentity(instance);")
 $t=$t.Replace($key,'fileKey = xyz.eclipseofficial.vyrn.compat.VyrnBridge.uploadPreferenceKey(vyrnUploadIdentity, Utilities.MD5(uploadingFilePath + (isEncrypted ? "enc" : "")));')
 $zeroResume="                            } else {`n                                rewrite = true;`n                            }"
 $resumeStart=$t.IndexOf('                            if (uploadedSize > 0) {')
 $resumeEnd=$t.IndexOf("                    } else {`n                        rewrite = true;",$resumeStart)
 if($resumeStart -lt 0 -or $resumeEnd -lt 0){throw 'Pinned upload resume region changed'}
 $resume=$t.Substring($resumeStart,$resumeEnd-$resumeStart)
 if([regex]::Matches($resume,[regex]::Escape($zeroResume)).Count -ne 1){throw 'Pinned zero-acknowledgement upload resume marker changed'}
 $resume=$resume.Replace($zeroResume,"                            } else {`n                                // Replay unacknowledged plaintext parts under the original session-bound upload ID.`n                                rewrite = isEncrypted || currentFileId == 0;`n                            }")
 $t=$t.Substring(0,$resumeStart)+$resume+$t.Substring($resumeEnd)
 $t=$t.Replace($send,'sendRequest(xyz.eclipseofficial.vyrn.compat.VyrnBridge.uploadRequest(currentAccount, finalRequest, vyrnUploadIdentity, isEncrypted), (response, error) -> {'+"`n            if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.currentUploadIdentity(currentAccount, vyrnUploadIdentity)) { state = 4; cleanup(); return; }")
 $t=$t.Replace('        state = 2;',"        state = 2;`n        xyz.eclipseofficial.vyrn.compat.VyrnBridge.cancelUpload(currentAccount, vyrnUploadIdentity, currentFileId);")
 $t=$t.Replace('delegate.didFailedUploadingFile(this);','if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.currentUploadIdentity(currentAccount, vyrnUploadIdentity)) delegate.didFailedUploadingFile(this);')
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java") -join "`n"
 $marker="        state = stateFinished;`n        notifyStreamListeners();"
 if(!$t.Contains($marker)){throw 'Pinned file completion marker missing'}
 $guard=@'
        if (!preload && !isPreloadVideoOperation && !xyz.eclipseofficial.vyrn.compat.VyrnBridge.verifyAttachment(currentAccount, location, finishCode == FINISH_CODE_FILE_ALREADY_EXIST ? cacheFileFinal : cacheFileTemp)) {
            onFail(false, 0);
            if (finishCode != FINISH_CODE_FILE_ALREADY_EXIST) {
                if (cacheFileTemp != null && !cacheFileTemp.equals(cacheFileFinal)) cacheFileTemp.delete();
                if (cacheFileParts != null) cacheFileParts.delete();
            }
            return;
        }
'@
 $t.Replace($marker,$guard+"`n"+$marker)
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/SessionBottomSheet.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/SessionBottomSheet.java") -join "`n"
 foreach($marker in @('return session.api_id != 22;','return session.api_id != 2040 && session.api_id != 2496;')){
  if(!$t.Contains($marker)){throw 'Pinned session capability marker missing'}
  $t=$t.Replace($marker,'return false; // Calls and private E2EE session settings are not available yet.')
 }
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java") -join "`n"
 $stateStart=$t.IndexOf('    public int getConnectionState() {');$stateEnd=$t.IndexOf('    public void setUserId(', $stateStart)
 if($stateStart -lt 0 -or $stateEnd -lt 0){throw 'Pinned connection state getter missing'}
 $t=$t.Substring(0,$stateStart)+"    public int getConnectionState() { return xyz.eclipseofficial.vyrn.compat.VyrnBridge.connectionState(currentAccount); }`n`n"+$t.Substring($stateEnd)
 $marker='private void sendRequestInternal(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken) {'
 if(!$t.Contains($marker)){throw 'Pinned request dispatch marker missing'}
 $t=$t.Replace($marker,$marker+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.dispatch(currentAccount, object, onComplete, onCompleteTimestamp, requestToken)) return;")
 $publicRequest='public int sendRequest(final TLObject object, final RequestDelegate onComplete, final RequestDelegateTimestamp onCompleteTimestamp, final QuickAckDelegate onQuickAck, final WriteToSocketDelegate onWriteToSocket, final int flags, final int datacenterId, final int connectionType, final boolean immediate) {'
 $publicStart=$t.IndexOf($publicRequest);$publicEnd=$t.IndexOf('    private void sendRequestInternal(', $publicStart)
 if($publicStart -lt 0 -or $publicEnd -lt 0){throw 'Pinned public request entry missing'}
 $publicSection=$t.Substring($publicStart,$publicEnd-$publicStart)
 $token='final int requestToken = lastRequestToken.getAndIncrement();'
 $publicSection=$publicSection.Replace($token,$token+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.dispatch(currentAccount, object, onComplete, onCompleteTimestamp, requestToken)) return requestToken;")
 $t=$t.Substring(0,$publicStart)+$publicSection+$t.Substring($publicEnd)
 $t=$t -replace '(?s)public void cancelRequest\(int token, boolean notifyServer, Runnable onCancelled\) \{.*?\n    \}', 'public void cancelRequest(int token, boolean notifyServer, Runnable onCancelled) { Utilities.stageQueue.postRunnable(() -> xyz.eclipseofficial.vyrn.compat.VyrnBridge.cancelRequest(currentAccount, token, onCancelled)); }'
 $t=$t.Replace('native_cancelRequestsForGuid(currentAccount, guid);','xyz.eclipseofficial.vyrn.compat.VyrnBridge.cancelRequestsForGuid(currentAccount, guid);')
 $t=$t.Replace('native_bindRequestToGuid(currentAccount, requestToken, guid);','Utilities.stageQueue.postRunnable(() -> xyz.eclipseofficial.vyrn.compat.VyrnBridge.bindRequestToGuid(currentAccount, requestToken, guid));')
 $logout='public static void onLogout(final int currentAccount) {'
 if(!$t.Contains($logout)){throw 'Pinned native logout marker missing'}
 $t=$t -replace '(?s)public static void onLogout\(final int currentAccount\) \{.*?\n    \}', 'public static void onLogout(final int currentAccount) { /* VYRN authentication is independent of Telegram native auth keys. */ }'
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java") -join "`n"
 $fullStart=$t.IndexOf('    public void loadFullUser(final TLRPC.User user, int classGuid, boolean force, Utilities.Callback<TLRPC.UserFull> whenReceivedFullUser) {')
 $fullEnd=$t.IndexOf('    private void reloadMessages(', $fullStart)
 if($fullStart -lt 0 -or $fullEnd -lt 0){throw 'Pinned full-profile loading section missing'}
 $fullSection=$t.Substring($fullStart,$fullEnd-$fullStart)
 $fullSignature='public void loadFullUser(final TLRPC.User user, int classGuid, boolean force, Utilities.Callback<TLRPC.UserFull> whenReceivedFullUser) {'
 $logoutSignature='public void performLogout(int type) {'
 if(!$t.Contains($logoutSignature)){throw 'Pinned native logout entry missing'}
 $t=$t.Replace($logoutSignature,$logoutSignature+"`n        if (type == 1) { xyz.eclipseofficial.vyrn.compat.VyrnBridge.logout(currentAccount, () -> performLogout(0)); return; }")
 $fullOverload=@'
public void loadFullUser(final TLRPC.User user, int classGuid, boolean force, Utilities.Callback<TLRPC.UserFull> whenReceivedFullUser) {
        loadFullUser(user, classGuid, force, whenReceivedFullUser, null);
    }
    public void loadFullUser(final TLRPC.User user, int classGuid, boolean force, Utilities.Callback<TLRPC.UserFull> whenReceivedFullUser, Utilities.Callback<TLRPC.TL_error> whenFailed) {
'@
 $fullSection=$fullSection.Replace($fullSignature,$fullOverload).Replace('whenReceivedFullUser == null && (loadingFullUsers','whenReceivedFullUser == null && whenFailed == null && (loadingFullUsers')
 $fullError='AndroidUtilities.runOnUIThread(() -> loadingFullUsers.remove(user.id));'
 if(!$fullSection.Contains($fullError)){throw 'Pinned full-profile error marker missing'}
 $fullSection=$fullSection.Replace($fullError,'AndroidUtilities.runOnUIThread(() -> { loadingFullUsers.remove(user.id); if (whenFailed != null) whenFailed.run(error); });')
 $t=$t.Substring(0,$fullStart)+$fullSection+$t.Substring($fullEnd)
 $blocked=@'
public void blockPeer(long id) { vyrnSetPeerBlocked(id, true, null); }
    public boolean vyrnApplyProfile(long origin, TLRPC.UserFull incoming) {
        if (origin == 0 || getUserConfig().getClientUserId() != origin || incoming.user == null || incoming.id != incoming.user.id) return false;
        TLRPC.User source = incoming.user;
        TLRPC.User user = source.id == origin ? getUserConfig().getCurrentUser() : getUser(source.id);
        if (user == null) user = source;
        String first = user.first_name, last = user.last_name; int flags = user.flags;
        TLRPC.UserProfilePhoto photo = user.photo;
        boolean changed = !java.util.Objects.equals(first, source.first_name) || !java.util.Objects.equals(last, source.last_name) || (photo == null ? 0 : photo.photo_id) != (source.photo == null ? 0 : source.photo.photo_id);
        user.first_name = source.first_name; user.last_name = source.last_name; user.flags = (flags & ~(2 | 4 | 32)) | (source.flags & (2 | 4 | 32));
        if ((photo == null ? 0 : photo.photo_id) != (source.photo == null ? 0 : source.photo.photo_id) || photo == null) user.photo = source.photo;
        if (source.id == origin && !getUserConfig().vyrnSaveCurrentUser(user)) { user.first_name = first; user.last_name = last; user.flags = flags; user.photo = photo; return false; }
        putUser(user, false);
        TLRPC.UserFull full = getUserFull(source.id);
        if (full == null) full = incoming;
        full.user = user; full.about = incoming.about; full.blocked = incoming.blocked; full.common_chats_count = incoming.common_chats_count; full.profile_photo = incoming.profile_photo;
        full.flags = (full.flags & ~(1 | 2 | 4)) | (incoming.flags & (1 | 2 | 4));
        fullUsers.put(user.id, full);
        ArrayList<TLRPC.User> users = new ArrayList<>(); users.add(user); getMessagesStorage().putUsersAndChats(users, null, false, true);
        getMessagesStorage().updateUserInfo(full, false);
        getNotificationCenter().postNotificationName(NotificationCenter.userInfoDidLoad, user.id, full);
        if (changed) {
            getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, UPDATE_MASK_NAME | UPDATE_MASK_AVATAR);
            if (user.id == origin) getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
        return true;
    }
    public void vyrnApplyPeerBlocked(long origin, long id, boolean blocked) {
        if (origin == 0 || getUserConfig().getClientUserId() != origin || id <= 0 || id == origin) return;
        boolean previous = blockePeers.indexOfKey(id) >= 0;
        if (blocked) blockePeers.put(id, 1); else blockePeers.delete(id);
        if (previous != blocked && totalBlockedCount >= 0) totalBlockedCount = Math.max(0, totalBlockedCount + (blocked ? 1 : -1));
        TLRPC.UserFull full = getUserFull(id);
        if (full != null && full.blocked != blocked) {
            full.blocked = blocked; full.flags = blocked ? full.flags | 1 : full.flags & ~1;
            getMessagesStorage().updateUserInfo(full, false);
        }
        getNotificationCenter().postNotificationName(NotificationCenter.blockedUsersDidLoad);
    }
    private void vyrnSetPeerBlocked(long id, boolean blocked, Runnable callback) {
        final long origin = getUserConfig().getClientUserId();
        if (origin == 0 || id <= 0 || id == origin || getUser(id) == null) return;
        TLObject request;
        if (blocked) { TLRPC.TL_contacts_block req = new TLRPC.TL_contacts_block(); req.id = getInputPeer(getUser(id)); request = req; }
        else { TLRPC.TL_contacts_unblock req = new TLRPC.TL_contacts_unblock(); req.id = getInputPeer(getUser(id)); request = req; }
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (getUserConfig().getClientUserId() != origin) return;
            if (error != null || !(response instanceof TLRPC.TL_boolTrue)) {
                android.widget.Toast.makeText(ApplicationLoader.applicationContext, LocaleController.isRTL ? "عملیات انجام نشد. دوباره تلاش کنید." : "The operation failed. Try again.", android.widget.Toast.LENGTH_LONG).show(); return;
            }
            vyrnApplyPeerBlocked(origin, id, blocked);
            if (callback != null) callback.run();
        }));
    }
'@
 if(!$t.Contains('    public void blockPeer(long id) {')){throw 'Pinned block-peer method missing'}
 $t=[regex]::Replace($t,'(?s)public void blockPeer\(long id\) \{.*?\n    \}',$blocked)
 $t=[regex]::Replace($t,'(?s)public void unblockPeer\(long id, Runnable callback\) \{.*?\n    \}','public void unblockPeer(long id, Runnable callback) { vyrnSetPeerBlocked(id, false, callback); }')
 $blockStart=$t.IndexOf('    public void getBlockedPeers(boolean reset) {')
 $blockEnd=$t.IndexOf('    public void deleteUserPhoto(', $blockStart)
 if($blockStart -lt 0 -or $blockEnd -lt 0){throw 'Pinned blocked-list section missing'}
 $blockSection=$t.Substring($blockStart,$blockEnd-$blockStart)
 $blockSection=$blockSection.Replace('loadingBlockedPeers = true;',"loadingBlockedPeers = true; vyrnBlockedLoadFailed = false;`n        final long vyrnOrigin = getUserConfig().getClientUserId();")
 $callback='getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {'
 if(!$blockSection.Contains($callback)){throw 'Pinned blocked-list callback missing'}
 $blockSection=$blockSection.Replace($callback,$callback+"`n            if (getUserConfig().getClientUserId() != vyrnOrigin) return;`n            loadingBlockedPeers = false; vyrnBlockedLoadFailed = response == null;`n            if (response == null) { getNotificationCenter().postNotificationName(NotificationCenter.blockedUsersDidLoad); return; }")
 $t=$t.Substring(0,$blockStart)+$blockSection+$t.Substring($blockEnd)
 $photoDelete=@'
public void deleteUserPhoto(TLRPC.InputPhoto photo) {
        final long origin = getUserConfig().getClientUserId();
        TLRPC.User current = getUserConfig().getCurrentUser();
        if (origin == 0 || current == null || photo != null && (current.photo == null || current.photo.photo_id != photo.id)) return;
        TLRPC.TL_photos_updateProfilePhoto request = new TLRPC.TL_photos_updateProfilePhoto(); request.id = new TLRPC.TL_inputPhotoEmpty();
        getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (getUserConfig().getClientUserId() != origin) return;
            if (error != null || !(response instanceof TLRPC.TL_photos_photo)) {
                android.widget.Toast.makeText(ApplicationLoader.applicationContext, LocaleController.isRTL ? "حذف عکس انجام نشد. دوباره تلاش کنید." : "Photo removal failed. Try again.", android.widget.Toast.LENGTH_LONG).show(); return;
            }
            TLRPC.User user = getUserConfig().getCurrentUser(); if (user == null || user.id != origin) return;
            TLRPC.UserProfilePhoto previous = user.photo; user.photo = new TLRPC.TL_userProfilePhotoEmpty();
            if (!getUserConfig().vyrnSaveCurrentUser(user)) { user.photo = previous; return; }
            putUser(user, false); java.util.ArrayList<TLRPC.User> users = new java.util.ArrayList<>(); users.add(user);
            getMessagesStorage().putUsersAndChats(users, null, true, true);
            TLRPC.UserFull full = getUserFull(origin); if (full != null) { full.profile_photo = null; full.flags &= ~4; getMessagesStorage().updateUserInfo(full, false); }
            getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, UPDATE_MASK_AVATAR);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        }));
    }
'@
 if(!$t.Contains('public void deleteUserPhoto(TLRPC.InputPhoto photo) {')){throw 'Pinned profile-photo deletion marker missing'}
 $t=[regex]::Replace($t,'(?s)public void deleteUserPhoto\(TLRPC.InputPhoto photo\) \{.*?\n    \}',$photoDelete)
 if(!$t.Contains('public boolean loadingBlockedPeers = false;')){throw 'Pinned blocked-list state missing'}
 $t=$t.Replace('public boolean loadingBlockedPeers = false;', 'public boolean loadingBlockedPeers = false; public boolean vyrnBlockedLoadFailed;')
 $t=$t.Replace('        totalBlockedCount = -1;', '        totalBlockedCount = -1; vyrnBlockedLoadFailed = false;')
 $deletion='public void deleteMessages(ArrayList<Integer> messages, ArrayList<Long> randoms, TLRPC.EncryptedChat encryptedChat, long dialogId, boolean forAll, int mode, boolean cacheOnly, long taskId, TLObject taskRequest, int topicId, boolean movedToScheduled, int movedToScheduledMessageId) {'
 if(!$t.Contains($deletion)){throw 'Pinned message deletion marker missing'}
 $t=$t.Replace($deletion,$deletion+"`n        if (!cacheOnly) { xyz.eclipseofficial.vyrn.compat.VyrnBridge.deleteMessages(currentAccount, dialogId, messages, forAll, mode, topicId, encryptedChat != null || taskId != 0); return; }")
 $updates='public boolean processUpdateArray(ArrayList<TLRPC.Update> updates, ArrayList<TLRPC.User> usersArr, ArrayList<TLRPC.Chat> chatsArr, boolean fromGetDifference, int date) {'
 if(!$t.Contains($updates)){throw 'Pinned native update array marker missing'}
 $t=$t.Replace($updates,$updates+"`n        if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.filterDeletedUpdates(currentAccount, updates)) return false;")
 $ui='public boolean updateInterfaceWithMessages(long dialogId, ArrayList<MessageObject> messages, int mode) {'
 $edited='                    ArrayList<MessageObject> arrayList = editingMessagesFinal.valueAt(b);'
 foreach($guard in @($ui,$edited)){if(!$t.Contains($guard)){throw 'Pinned native UI application marker missing'}}
 $t=$t.Replace($ui,$ui+"`n        if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.retainLiveMessages(currentAccount, messages)) return false;")
 $t=$t.Replace($edited,$edited+"`n                    if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.retainLiveMessages(currentAccount, arrayList) || arrayList.isEmpty()) continue;")
 foreach($bounds in @(@('public void processLoadedDialogs(', '    private void applyDialogNotificationsSettings('), @('public void processDialogsUpdate(', '    private int messagesMaxDate('))){
  $begin=$t.IndexOf($bounds[0]);$finish=$t.IndexOf($bounds[1],$begin)
  if($begin -lt 0 -or $finish -lt 0){throw 'Pinned native dialog application section missing'}
  $section=$t.Substring($begin,$finish-$begin)
  $dialogMessages='                    ArrayList<MessageObject> newMsgs = new_dialogMessage.get(value.id);'
  if(!$section.Contains($dialogMessages)){throw 'Pinned native dialog message marker missing'}
  $section=$section.Replace($dialogMessages,$dialogMessages+"`n                    ArrayList<MessageObject> guardedMsgs = newMsgs == null ? new ArrayList<>() : newMsgs;`n                    if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.repairDialogTop(currentAccount, value, guardedMsgs, dialogMessage.get(value.id))) continue;`n                    if (!guardedMsgs.isEmpty()) newMsgs = guardedMsgs;")
  $section=$section.Replace('boolean oldMsgsDeleted = false;','boolean oldMsgsDeleted = xyz.eclipseofficial.vyrn.compat.VyrnBridge.isDeletedDialogTop(currentAccount, currentDialog);')
  $section=$section.Replace('if (value.top_message >= currentDialog.top_message ||','if (oldMsgsDeleted || value.top_message >= currentDialog.top_message ||')
  $section=$section.Replace('if (oldMsgs == null || currentDialog.top_message > 0) {','if (oldMsgsDeleted || oldMsgs == null || currentDialog.top_message > 0) {')
  $t=$t.Substring(0,$begin)+$section+$t.Substring($finish)
 }
 $marker='public void setDialogHistoryTTL(long did, int ttl) {'
 if(!$t.Contains($marker)){throw 'Pinned history TTL marker missing'}
 # Commit native cache/UI changes only after the server accepts the setting.
 $replacement=@'
public void setDialogHistoryTTL(long did, int ttl) {
        TLRPC.TL_messages_setHistoryTTL req = new TLRPC.TL_messages_setHistoryTTL();
        req.peer = getInputPeer(did);
        req.period = ttl;
        getConnectionsManager().sendRequest(req, (response, error) -> {
            if (response != null) processUpdates((TLRPC.Updates) response, false);
        });
    }
'@
 $t -replace '(?s)public void setDialogHistoryTTL\(long did, int ttl\) \{.*?\n    \}', $replacement
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/PrivacyUsersActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/PrivacyUsersActivity.java") -join "`n"
 $t=$t.Replace('emptyView.setText(LocaleController.getString(R.string.NoBlocked));',"emptyView.setText(getMessagesController().vyrnBlockedLoadFailed ? vyrnBlockLoadError() : LocaleController.getString(R.string.NoBlocked));`n            emptyView.setOnClickListener(v -> { if (getMessagesController().vyrnBlockedLoadFailed) getMessagesController().getBlockedPeers(true); });")
 $t=$t.Replace('if (getMessagesController().totalBlockedCount < 0) {','if (getMessagesController().totalBlockedCount < 0 && !getMessagesController().vyrnBlockedLoadFailed) {')
 $notification='} else if (id == NotificationCenter.blockedUsersDidLoad) {'
 if(!$t.Contains($notification)){throw 'Pinned blocked-list screen notification missing'}
 $t=$t.Replace($notification,$notification+"`n            emptyView.setText(getMessagesController().vyrnBlockedLoadFailed ? vyrnBlockLoadError() : LocaleController.getString(R.string.NoBlocked));`n            if (getMessagesController().vyrnBlockedLoadFailed) vyrnShowBlockLoadError();")
 $retry=@'
    private void vyrnShowBlockLoadError() {
        if (getParentActivity() == null || getVisibleDialog() != null) return;
        showDialog(new AlertDialog.Builder(getParentActivity()).setMessage(vyrnBlockLoadError())
            .setPositiveButton(LocaleController.getString(R.string.Retry), (dialog, which) -> getMessagesController().getBlockedPeers(true))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
    }

'@
 $t=$t.Replace('    public PrivacyUsersActivity() {',$retry+'    public PrivacyUsersActivity() {')
 $t=$t.Replace('        return fragmentView;',"        if (currentType == TYPE_BLOCKED && getMessagesController().vyrnBlockedLoadFailed) org.telegram.messenger.AndroidUtilities.runOnUIThread(this::vyrnShowBlockLoadError);`n        return fragmentView;")
 $t=$t.Replace('    public PrivacyUsersActivity() {',"    private String vyrnBlockLoadError() { return LocaleController.isRTL ? `"بارگیری انجام نشد. برای تلاش دوباره لمس کنید.`" : `"Could not load. Tap to retry.`"; }`n    public PrivacyUsersActivity() {")
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java") -join "`n"
 $mediaConstructor='new TLRPC.TL_messages_sendMedia()'
 if(!$t.Contains($mediaConstructor)){throw 'Pinned media request constructor missing'}
 $t=$t.Replace($mediaConstructor,'xyz.eclipseofficial.vyrn.compat.VyrnBridge.mediaRequest(currentAccount)')
 $marker='public void cancelSendingMessage(ArrayList<MessageObject> objects) {'
 if(!$t.Contains($marker)){throw 'Pinned message cancellation marker missing'}
 $t=$t.Replace($marker,$marker+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.cancelSendingMessages(currentAccount, objects)) return;")
 $send='public void sendMessage(SendMessageParams sendMessageParams) {'
 if(!$t.Contains($send)){throw 'Pinned native send entry marker missing'}
 $t=$t.Replace($send,$send+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.blockPrivateSend(sendMessageParams.peer)) return;")
 $forward="        MessageSuggestionParams suggestionParams`n    ) {"
 $screenshot='public void sendScreenshotMessage(TLRPC.User user, int messageId, TLRPC.Message resendMessage) {'
 foreach($marker in @($forward,$screenshot)){if(!$t.Contains($marker)){throw 'Pinned alternative private send entry marker missing'}}
 $t=$t.Replace($forward,$forward+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.blockPrivateSend(peer)) return 0;")
 $t=$t.Replace($screenshot,$screenshot+"`n        if (user != null && xyz.eclipseofficial.vyrn.compat.VyrnBridge.blockPrivateSend(user.id)) return;")
 $start=$t.IndexOf('    public int editMessage(MessageObject messageObject, String message,')
 $end=$t.IndexOf('    public int editRichMessage(', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned text edit markers missing'}
 $section=$t.Substring($start,$end-$start)
 $constructor='req = new TLRPC.TL_messages_editMessage();'
 $success='getMessagesController().processUpdates((TLRPC.Updates) response, false);'
 if(!$section.Contains($constructor) -or !$section.Contains($success)){throw 'Pinned text edit request markers missing'}
 $section=$section.Replace($constructor,'req = xyz.eclipseofficial.vyrn.compat.VyrnBridge.editRequest(currentAccount, messageObject);').Replace($success,'xyz.eclipseofficial.vyrn.compat.VyrnBridge.processEditUpdates(currentAccount, req, (TLRPC.Updates) response);')
 $section=$section.Replace('AlertsCreator.processError(currentAccount, error, fragment, req)','xyz.eclipseofficial.vyrn.compat.VyrnBridge.showEditError(currentAccount, error, fragment, req)')
 $t=$t.Substring(0,$start)+$section+$t.Substring($end)
 $proof=@'
    public void vyrnResolveCancellation(int oldId, long dialogId, TLRPC.Message committed) {
        removeFromSendingMessages(oldId, false);
        processSentMessage(oldId);
        if (committed == null) {
            ArrayList<Integer> localIds = new ArrayList<>(); localIds.add(oldId);
            getMessagesController().deleteMessages(localIds, null, null, dialogId, 0, false, 0, true);
        } else {
            ArrayList<MessageObject> messages = new ArrayList<>(); messages.add(new MessageObject(currentAccount, committed, false, false));
            getMessagesController().updateInterfaceWithMessages(dialogId, messages, 0);
            getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer, oldId, committed.id, committed, dialogId, 0L, 0, false);
            getNotificationCenter().postNotificationName(NotificationCenter.messageReceivedByServer2, oldId, committed.id, committed, dialogId, 0L, 0, false);
        }
    }

'@
 $retry='    public boolean retrySendMessage(MessageObject messageObject, boolean unsent, long payStars) {'
 if(!$t.Contains($retry)){throw 'Pinned native retry entry marker missing'}
 $t.Replace($retry,$proof+$retry+"`n        if (messageObject != null && xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(messageObject.getDialogId())) return xyz.eclipseofficial.vyrn.compat.VyrnBridge.retryPrivateMessage(currentAccount, messageObject, unsent);")
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/Components/AlertsCreator.java") -join "`n"
 $start=$t.IndexOf('    public static void createDeleteMessagesAlert(')
 $end=$t.IndexOf('        boolean isActiveGiveawayAndOwner = false;', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned delete confirmation markers missing'}
 $section=$t.Substring($start,$end-$start)
 $state='        final boolean[] deleteForAll = new boolean[1];'
 $options='} else if (!scheduled && !isSavedMessages && !ChatObject.isChannel(chat) && encryptedChat == null) {'
 if(!$section.Contains($state) -or !$section.Contains($options)){throw 'Pinned group deletion options missing'}
 $section=$section.Replace($state,@'
        final boolean vyrnSharedPrivateDelete = user != null && user.id != UserConfig.getInstance(currentAccount).getClientUserId() && encryptedChat == null && !scheduled && !isSavedMessages && mode == 0 && topicId == 0;
        final boolean vyrnSharedGroupDelete = chat != null && !ChatObject.isChannel(chat) && encryptedChat == null && !scheduled && !isSavedMessages && mode == 0 && topicId == 0;
        final boolean[] deleteForAll = new boolean[]{vyrnSharedGroupDelete || vyrnSharedPrivateDelete};
'@).Replace($options,'} else if (!vyrnSharedGroupDelete && !vyrnSharedPrivateDelete && !scheduled && !isSavedMessages && !ChatObject.isChannel(chat) && encryptedChat == null) {')
 $section+="        if (vyrnSharedGroupDelete) builder.setMessage(LocaleController.isRTL ? `"این پیام‌ها برای همهٔ اعضای گروه حذف می‌شوند. ادامه می‌دهید؟`" : `"These messages will be deleted for everyone in the group. Continue?`");`n`n"
 $section+="        if (vyrnSharedPrivateDelete) builder.setMessage(LocaleController.isRTL ? `"این پیام‌ها برای هر دو نفر حذف می‌شوند. ادامه می‌دهید؟`" : `"These messages will be deleted for both people. Continue?`");`n`n"
 $t.Substring(0,$start)+$section+$t.Substring($end)
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java") -join "`n"
 $start=$t.IndexOf('    public void checkRoundVideo() {');$end=$t.IndexOf('    public boolean isInVideoMode()', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned round-video capability marker missing'}
 $voiceOnly=@'
    public void checkRoundVideo() {
        // VYRN supports group voice recording; round video has no backend contract.
        hasRecordVideo = sendRoundEnabled = false;
        sendVoiceEnabled = DialogObject.isChatDialog(dialog_id) && ChatObject.canSendVoice(accountInstance.getMessagesController().getChat(-dialog_id));
        setRecordVideoButtonVisible(false, false);
    }

'@
 $t=$t.Substring(0,$start)+$voiceOnly+$t.Substring($end)
 $t=Disable-AiControls $t
 $send='protected boolean sendMessageInternal(boolean notify, int scheduleDate, int scheduleRepeatPeriod, long payStars, boolean allowConfirm) {'
 $button='public void checkSendButton(boolean animated) {'
 $hint='public void updateFieldHint(boolean animated) {'
 foreach($marker in @($send,$button,$hint)){if(!$t.Contains($marker)){throw 'Pinned private composer gate marker missing'}}
 $privateComposer=@'
    private boolean vyrnStaging;
    private long vyrnTextRevision;
    private boolean vyrnPlainTextReady() {
        return !vyrnStaging && xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateTextReady(currentAccount, dialog_id) && messageEditText != null && !TextUtils.isEmpty(messageEditText.getText()) && editingMessageObject == null && editingBusinessLink == null && !richDraftActive && replyingMessageObject == null && replyingTopMessage == null && replyingQuote == null && (delegate == null || delegate.getReplyToStory() == null) && getThreadMessage() == null && audioToSend == null && videoToSendMessageObject == null && getEffectId() == 0 && getSendMessageSuggestionParams() == null && !isInScheduleMode() && (delegate == null || !delegate.hasForwardingMessages());
    }
    private boolean vyrnSendPrivateText(int scheduleDate, int scheduleRepeatPeriod, long payStars) {
        if (vyrnStaging) return false;
        if (!vyrnPlainTextReady() || scheduleDate != 0 || scheduleRepeatPeriod != 0 || payStars != 0) { xyz.eclipseofficial.vyrn.compat.VyrnBridge.blockPrivateSend(dialog_id); return false; }
        final int account = currentAccount;
        final long dialog = dialog_id, owner = UserConfig.getInstance(account).getClientUserId();
        final String session = xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateTextSession(account), text = messageEditText.getText().toString();
        final long textRevision = vyrnTextRevision;
        vyrnStaging = true;
        checkSendButton(false);
        xyz.eclipseofficial.vyrn.compat.VyrnBridge.forAccount(account).stagePrivateText(dialog, text).whenComplete((binding, failure) -> AndroidUtilities.runOnUIThread(() -> {
            vyrnStaging = false;
            if (currentAccount == account && dialog_id == dialog && xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateTextOrigin(account, dialog, owner, session, binding)) {
                if (failure == null && textRevision == vyrnTextRevision && vyrnPlainTextReady() && text.contentEquals(messageEditText.getText())) { setFieldText(""); if (parentFragment != null) parentFragment.saveDraft(); }
                if (failure != null) android.widget.Toast.makeText(getContext(), LocaleController.isRTL ? "پیام ذخیره نشد؛ پیش‌نویس حفظ شد. دوباره تلاش کنید." : "Message could not be saved; your draft is kept. Retry.", android.widget.Toast.LENGTH_LONG).show();
                else android.widget.Toast.makeText(getContext(), LocaleController.isRTL ? "پیام در صف ارسال قرار گرفت." : "Message queued for sending.", android.widget.Toast.LENGTH_SHORT).show();
            }
            checkSendButton(false);
        }));
        return true;
    }
'@
 $textChanged='public void onTextChanged(CharSequence charSequence, int start, int before, int count) {'
 if(!$t.Contains($textChanged)){throw 'Pinned composer text revision marker missing'}
 $t=$t.Replace($textChanged,$textChanged+"`n                vyrnTextRevision++;")
 $publicSend='public boolean sendMessage() {'
 if(!$t.Contains($publicSend)){throw 'Pinned private send wrapper marker missing'}
 $t=$t.Replace($publicSend,$publicSend+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id)) return sendMessageInternal(true, 0, 0, 0, true);")
 $longSend='private boolean onSendLongClick(View view) {'
 if(!$t.Contains($longSend)){throw 'Pinned private send long-click marker missing'}
 $t=$t.Replace($longSend,$longSend+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.blockPrivateSend(dialog_id)) return true;")
 $effect='        this.effectId = effectId;'
 if(!$t.Contains($effect)){throw 'Pinned private effect setter marker missing'}
 $t=$t.Replace($effect,$effect+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id)) checkSendButton(false);")
 $t=$t.Replace($send,$privateComposer+"`n    "+$send+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id)) return vyrnSendPrivateText(scheduleDate, scheduleRepeatPeriod, payStars);")
 $t=$t.Replace($button,$button+"`n        if (sendButton != null) { boolean unavailable = xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id) && !vyrnPlainTextReady(); sendButton.setEnabled(!unavailable); if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id)) sendButton.setAlpha(1.0f); sendButton.setContentDescription(unavailable ? xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailableLabel() : getString(R.string.Send)); }")
 $t=$t.Replace($hint,$hint+"`n        if (messageEditText != null && xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id) && !xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateTextReady(currentAccount, dialog_id)) { messageEditText.setHintText(xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailableLabel(), animated); return; }")
 $begin=$t.IndexOf('        sendButton = new SendButton(');$end=$t.IndexOf('        sendButton.setVisibility(INVISIBLE);',$begin)
 if($begin -lt 0 -or $end -lt 0){throw 'Pinned native send button marker missing'}
 $section=$t.Substring($begin,$end-$begin)
 if(!$section.Contains('super.setAlpha(alpha);')){throw 'Pinned send button alpha marker missing'}
 $section=$section.Replace('super.setAlpha(alpha);','super.setAlpha(xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id) && !vyrnPlainTextReady() ? alpha * 0.4f : alpha);')
 $t=$t.Substring(0,$begin)+$section+$t.Substring($end)
 $marker='        editingMessageObject = messageObject;'
 if(!$t.Contains($marker)){throw 'Pinned composer edit marker missing'}
 $t=$t.Replace($marker,$marker+"`n        xyz.eclipseofficial.vyrn.compat.VyrnBridge.captureEdit(currentAccount, messageObject);")
 $editText='editingText = editingMessageObject.messageText;'
 if(!$t.Contains($editText)){throw 'Pinned composer editing text marker missing'}
 $t=$t.Replace($editText,'editingText = xyz.eclipseofficial.vyrn.compat.VyrnBridge.editTextForComposer(currentAccount, editingMessageObject);')
 $submit='SendMessagesHelper.getInstance(currentAccount).editMessage(editingMessageObject, null, null, null, null, null, null, false, editingMessageObject.hasMediaSpoilers(), null);'
 if(!$t.Contains($submit)){throw 'Pinned composer edit submit marker missing'}
 $t.Replace($submit,@'
if (dialog_id != 0 && !DialogObject.isEncryptedDialog(dialog_id) && !editingMessageObject.scheduled && (editingMessageObject.messageOwner.media == null || editingMessageObject.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty)) {
                    SendMessagesHelper.getInstance(currentAccount).editMessage(editingMessageObject, message[0].toString(), messageWebPageSearch, parentFragment, entities, 0, 0);
                } else {
                    SendMessagesHelper.getInstance(currentAccount).editMessage(editingMessageObject, null, null, null, null, null, null, false, editingMessageObject.hasMediaSpoilers(), null);
                }
'@)
}
foreach($aiFile in @('Components/CaptionPhotoViewer.java','Components/ChatAttachAlert.java','Components/ChatAttachAlertPhotoLayout.java','iv/RichEditor.java','iv/RichEditorToolbar.java','Components/AIEditorAlert.java','iv/RichAIComposeSheet.java')){
 $relative="TMessagesProj/src/main/java/org/telegram/ui/$aiFile"
 Edit $relative {
  param($t)
  $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:$relative") -join "`n"
  $t=Disable-AiControls $t
  if($aiFile -eq 'Components/ChatAttachAlert.java'){
   $init='    public void init() {'
   $counts='            int count = buttonsCount;'
   $rows='            attachBotsEndRow = -1;'
   $photoInit='        photoLayout.onInit(videosEnabled, photosEnabled, documentsEnabled);'
   foreach($marker in @($init,$counts,$rows,$photoInit)){if(!$t.Contains($marker)){throw 'Pinned attachment capability marker missing'}}
   $helper='    public boolean vyrnGroupAttachments() { return baseFragment instanceof ChatActivity && ((ChatActivity) baseFragment).getCurrentChat() != null && avatarPicker == 0 && !storyMediaPicker && !isPollAttach && editingMessageObject == null; }'
   $t=$t.Replace($init,$helper+"`n"+$init)
   $t=$t.Replace($counts,$counts+"`n            if (vyrnGroupAttachments()) return count;")
   $t=$t.Replace($rows,$rows+"`n            if (vyrnGroupAttachments()) { if (photosEnabled) galleryButton = buttonsCount++; if (documentsEnabled) documentButton = buttonsCount++; super.notifyDataSetChanged(); return; }")
   $t=$t.Replace($photoInit,"        if (vyrnGroupAttachments()) { videosEnabled = musicEnabled = pollsEnabled = todoEnabled = false; /* ponytail: one photo until native album sends have a backend contract. */ setMaxSelectedPhotos(1, false); }`n"+$photoInit)
   $maxPhotos='    public void setMaxSelectedPhotos(int value, boolean order) {'
   if(!$t.Contains($maxPhotos)){throw 'Pinned photo selection limit marker missing'}
   $t=$t.Replace($maxPhotos,$maxPhotos+"`n        if (vyrnGroupAttachments()) { value = 1; order = false; }")
   $layout='        if (isStoryLocationPicker || isBizLocationPicker || isLocationPicker) {'
   if(!$t.Contains($layout)){throw 'Pinned attachment initial layout marker missing'}
   $t=$t.Replace($layout,"        if (vyrnGroupAttachments()) { openDocumentsLayout(false); layoutToSet = documentLayout; selectedId = 4; typeButtonsAvailable = true; } else if (isStoryLocationPicker || isBizLocationPicker || isLocationPicker) {")
   $docStart=$t.IndexOf('                } else if (num == 4) {')
   $docEnd=$t.IndexOf('                } else if (num == 5) {',$docStart)
   if($docStart -lt 0 -or $docEnd -lt 0){throw 'Pinned document permission markers missing'}
   $section=$t.Substring($docStart,$docEnd-$docStart).Replace('if (Build.VERSION.SDK_INT >= 33) {','if (!vyrnGroupAttachments() && Build.VERSION.SDK_INT >= 33) {').Replace('} else if (Build.VERSION.SDK_INT >= 23 &&','} else if (!vyrnGroupAttachments() && Build.VERSION.SDK_INT >= 23 &&')
   $t=$t.Substring(0,$docStart)+$section+$t.Substring($docEnd)
   $t=$t.Replace('err = !checkPhotoAndDocumentsPermission(mContext);','err = !vyrnGroupAttachments() && !checkPhotoAndDocumentsPermission(mContext);')
   $t=$t.Replace('err = !checkPhotoAndCameraPermission(mContext);','err = vyrnGroupAttachments() ? ContextCompat.checkSelfPermission(mContext, Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED : !checkPhotoAndCameraPermission(mContext);')
   $t=$t.Replace('allowLivePhotos = true;','allowLivePhotos = !vyrnGroupAttachments();')
   $schedule='if (editingMessageObject == null && (chatActivity == null || !ChatObject.isMonoForum(chatActivity.getCurrentChat())) && ((chatActivity != null && chatActivity.canScheduleMessage()) || currentAttachLayout.canScheduleMessages())) {'
   if(!$t.Contains($schedule)){throw 'Pinned attachment schedule marker missing'}
   $t=$t.Replace($schedule,$schedule.Replace('if (editingMessageObject','if (!vyrnGroupAttachments() && editingMessageObject'))
  }
  if($aiFile -eq 'Components/ChatAttachAlertPhotoLayout.java'){
   $cameraRequest='                            parentAlert.baseFragment.getParentActivity().requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE}, 17);'
   if(!$t.Contains($cameraRequest)){throw 'Pinned automatic camera permission marker missing'}
   $t=$t.Replace($cameraRequest,$cameraRequest.Replace('parentAlert.baseFragment','if (!parentAlert.vyrnGroupAttachments()) parentAlert.baseFragment'))
   $t=$t.Replace('new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES}','parentAlert.vyrnGroupAttachments() ? new String[]{Manifest.permission.READ_MEDIA_IMAGES} : new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES}')
   $permissions='        return Build.VERSION.SDK_INT >= 23 && ('
   $allMedia='    private boolean shouldLoadAllMedia() {'
   foreach($marker in @($permissions,$allMedia)){if(!$t.Contains($marker)){throw 'Pinned gallery permission marker missing'}}
   $t=$t.Replace($permissions,"        if (parentAlert.vyrnGroupAttachments() && Build.VERSION.SDK_INT >= 33) return activity == null || activity.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED;`n"+$permissions)
   $t=$t.Replace($allMedia,$allMedia+"`n        if (parentAlert.vyrnGroupAttachments()) return false;")
   $schedulePhotos='    public boolean canScheduleMessages() {'
   if(!$t.Contains($schedulePhotos)){throw 'Pinned photo schedule marker missing'}
   $t=$t.Replace($schedulePhotos,$schedulePhotos+"`n        if (parentAlert.vyrnGroupAttachments()) return false;")
   $t=$t.Replace('galleryAlbumEntry = MediaController.allMediaAlbumEntry;','galleryAlbumEntry = parentAlert.vyrnGroupAttachments() ? MediaController.allPhotosAlbumEntry : MediaController.allMediaAlbumEntry;')
   $t=$t.Replace('} else if (shouldLoadAllMedia()) {','} else if (shouldLoadAllMedia() || parentAlert.vyrnGroupAttachments()) {')
   $t=$t.Replace('MediaController.allMediaAlbums','(parentAlert.vyrnGroupAttachments() ? MediaController.allPhotoAlbums : MediaController.allMediaAlbums)')
   $menuEnd="    }`n`n    private void updateStarsItem() {"
   if(!$t.Contains($menuEnd)){throw 'Pinned photo menu marker missing'}
   $guard='        if (parentAlert.vyrnGroupAttachments()) { for (int item : new int[]{compress, group, spoiler, quality, stars, caption, media_gap}) parentAlert.selectedMenuItem.hideSubItem(item); captionItem.setVisibility(View.GONE); }'
   $t=$t.Replace($menuEnd,$guard+"`n"+$menuEnd)
  }
  if($aiFile -eq 'iv/RichEditor.java'){
   $enabled='        public void setEnabled(boolean enabled) {'
   if(!$t.Contains($enabled)){throw 'Pinned rich button enabled marker missing'}
   $t=$t.Replace($enabled,$enabled+"`n            super.setEnabled(enabled);")
  }
  if($aiFile -in @('Components/AIEditorAlert.java','iv/RichAIComposeSheet.java')){
   $show='public void show() {'
   if(!$t.Contains($show)){throw 'Pinned AI sheet entry marker missing'}
   $t=$t.Replace($show,$show+"`n        if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.aiEditorAvailable()) return;")
   $t=$t.Replace('tonesController.load();','if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.aiEditorAvailable()) tonesController.load();')
  }
  $t
 }
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java") -join "`n"
 $marker="        if (checkMediaExists) {`n            checkMediaExistance();`n        }"
 if(!$t.Contains($marker)){throw 'Pinned private text constructor marker missing'}
 foreach($edit in @('public static boolean canEditMessage(int currentAccount, TLRPC.Message message, TLRPC.Chat chat, boolean scheduled) {','public static boolean canEditMessageAnytime(int currentAccount, TLRPC.Message message, TLRPC.Chat chat) {')){
  if(!$t.Contains($edit)){throw 'Pinned private edit capability marker missing'}
  $t=$t.Replace($edit,$edit+"`n        if (message != null && message.peer_id != null && message.peer_id.user_id != 0) return xyz.eclipseofficial.vyrn.compat.VyrnBridge.canEditPrivate(currentAccount, message, $(if ($edit.Contains('boolean scheduled')) {'scheduled'} else {'false'}));")
 }
 $t.Replace($marker,$marker+"`n        xyz.eclipseofficial.vyrn.compat.VyrnBridge.hydratePrivateText(this);")
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java") -join "`n"
 $read='byte[] bytes = Utilities.hexToBytes((String) entry.getValue());'
 if(!$t.Contains($read)){throw 'Pinned draft read marker missing'}
 $t=$t.Replace($read,'byte[] bytes = xyz.eclipseofficial.vyrn.data.DraftVault.decode(currentAccount, key, (String) entry.getValue());')
 foreach($record in @('threadId == 0 ? ("" + dialogId) : ("t_" + dialogId + "_" + threadId)','threadId == 0 ? ("r_" + dialogId) : ("rt_" + dialogId + "_" + threadId)')){
  $write="putString($record, Utilities.bytesToHex(serializedData.toByteArray()))"
  if(!$t.Contains($write)){throw 'Pinned draft write marker missing'}
  $t=$t.Replace($write,"putString($record, xyz.eclipseofficial.vyrn.data.DraftVault.encode(currentAccount, $record, serializedData.toByteArray()))")
 }
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java") -join "`n"
 foreach($hintRunnable in @('    private final Runnable showScheduledOrNoSoundRunnable = () -> {','    private final Runnable showScheduledHintRunnable = () -> {')){
  if(!$t.Contains($hintRunnable)){throw 'Pinned unsupported private hint marker missing'}
  $t=$t.Replace($hintRunnable,$hintRunnable+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateSendUnavailable(dialog_id)) return;")
 }
 $marker='            int updateMask = (Integer) args[0];'
 if(!$t.Contains($marker)){throw 'Pinned private text UI refresh marker missing'}
 $t=$t.Replace($marker,$marker+"`n            if ((updateMask & xyz.eclipseofficial.vyrn.compat.VyrnBridge.PRIVATE_TEXT_UPDATE) != 0) updateVisibleRows();")
 $historyFailure='        } else if (id == NotificationCenter.loadingMessagesFailed) {'
 if(!$t.Contains($historyFailure)){throw 'Pinned private history failure marker missing'}
 $t=$t.Replace($historyFailure,$historyFailure+@'

            if ((Integer) args[0] == classGuid && DialogObject.isUserDialog(dialog_id) && firstLoading) {
                loading = false;
                firstLoading = false;
                showProgressView(false);
                checkDispatchHideSkeletons(true);
                AndroidUtilities.runOnUIThread(() -> getNotificationCenter().runDelayedNotifications());
            }
'@)
 $click='            public void onItemClick(final int id) {'
 $menu='            headerItem.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));'
 if(!$t.Contains($click) -or !$t.Contains($menu)){throw 'Pinned private verification menu markers missing'}
 $t=$t.Replace($click,$click+"`n                if (id == 91001) { xyz.eclipseofficial.vyrn.compat.VyrnBridge.showPrivateVerification(ChatActivity.this, dialog_id); return; }")
 $t.Replace($menu,$menu+"`n            if (dialog_id > 0 && dialog_id != getUserConfig().getClientUserId() && chatMode == MODE_DEFAULT) headerItem.lazilyAddSubItem(91001, R.drawable.msg_secret, xyz.eclipseofficial.vyrn.compat.VyrnBridge.privateVerificationLabel());")
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java") -join "`n"
 $marker='public void postNotificationNameInternal(int id, boolean allowDuringAnimation, Object... args) {'
 if(!$t.Contains($marker)){throw 'Pinned native notification dispatch marker missing'}
 $guard=@'

        if (currentAccount >= 0 && (id == didReceiveNewMessages || id == replaceMessagesObjects || id == messagesDidLoad)) {
            int index = id == messagesDidLoad ? 2 : 1;
            if (args.length > index && args[index] instanceof ArrayList) {
                ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[index];
                if (!xyz.eclipseofficial.vyrn.compat.VyrnBridge.retainLiveMessages(currentAccount, messages)) return;
                if (id != messagesDidLoad && messages.isEmpty()) return;
            }
        }
'@
 $t.Replace($marker,$marker+$guard)
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MessageCustomParamsHelper.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/MessageCustomParamsHelper.java") -join "`n"
 # Extend only the app-private custom_params blob, never a Telegram wire constructor.
 $empty='            message.voiceTranscription == null &&'
 $flag='            flags = setFlag(flags, FLAG_13, message.translatedRichMessage != null);'
 $write="                message.translatedRichMessage.serializeToStream(stream);`n            }"
 $read='                message.translatedRichMessage = TL_iv.RichMessage.TLdeserialize(stream, stream.readInt32(exception), exception);'+"`n            }"
 foreach($marker in @($empty,$flag,$write,$read)){if(!$t.Contains($marker)){throw 'Pinned local message params marker missing'}}
 $t=$t.Replace($empty,"            (message.params == null || !message.params.containsKey(xyz.eclipseofficial.vyrn.compat.VyrnBridge.EDIT_SNAPSHOT)) &&`n"+$empty)
 $t=$t.Replace($flag,$flag+"`n            flags = setFlag(flags, FLAG_14, message.params != null && message.params.containsKey(xyz.eclipseofficial.vyrn.compat.VyrnBridge.EDIT_SNAPSHOT));")
 $t=$t.Replace($write,$write+"`n            if (hasFlag(flags, FLAG_14)) stream.writeString(message.params.get(xyz.eclipseofficial.vyrn.compat.VyrnBridge.EDIT_SNAPSHOT));")
 $t=$t.Replace('            flags = stream.readInt32(true);',"            if (message.params != null) message.params.remove(xyz.eclipseofficial.vyrn.compat.VyrnBridge.EDIT_SNAPSHOT);`n            flags = stream.readInt32(true);")
 $t.Replace($read,$read+"`n            if (hasFlag(flags, FLAG_14)) { if (message.params == null) message.params = new java.util.HashMap<>(); message.params.put(xyz.eclipseofficial.vyrn.compat.VyrnBridge.EDIT_SNAPSHOT, stream.readString(exception)); }")
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java") -join "`n"
 # Native pending group/private rows recover their original random/client identity from the same joined record.
 $retryIdentity='if (DialogObject.isEncryptedDialog(dialogId) && !cursor.isNull(5)) {'
 if(!$t.Contains($retryIdentity)){throw 'Pinned history random ID restore marker missing'}
 $t=$t.Replace($retryIdentity,'if ((DialogObject.isEncryptedDialog(dialogId) || message.id < 0 && message.peer_id != null && (message.peer_id.chat_id != 0 || DialogObject.isUserDialog(message.peer_id.user_id))) && !cursor.isNull(5)) {')
 foreach($marker in @('public void openDatabase(int openTries) {','            databaseCreated = true;','private void cleanupInternal(boolean deleteFiles) {')){if(!$t.Contains($marker)){throw 'Pinned native deletion snapshot lifecycle marker missing'}}
 $t=$t.Replace('public void openDatabase(int openTries) {',"public void openDatabase(int openTries) {`n        vyrnDeletedMessages = null;")
 $t=$t.Replace('            databaseCreated = true;',"            xyz.eclipseofficial.vyrn.compat.VyrnBridge.initializeDeletedCache(database);`n            vyrnReloadDeletedMessages();`n            databaseCreated = true;")
 $t=$t.Replace('private void cleanupInternal(boolean deleteFiles) {',"private void cleanupInternal(boolean deleteFiles) {`n        vyrnDeletedMessages = null;")
 $snapshot=@'
    private volatile java.util.Map<Long, java.util.Set<Integer>> vyrnDeletedMessages;

    public java.util.Map<Long, java.util.Set<Integer>> getVyrnDeletedMessages() {
        return vyrnDeletedMessages;
    }

    public void vyrnReloadDeletedMessages() throws org.telegram.SQLite.SQLiteException {
        if (Thread.currentThread() != storageQueue) throw new IllegalStateException("storage_queue_required");
        try {
        java.util.Map<Long, java.util.Set<Integer>> loaded = new java.util.HashMap<>();
        if (database.tableExists("vyrn_deleted_messages")) {
            SQLiteCursor cursor = database.queryFinalized("SELECT uid, mid FROM vyrn_deleted_messages");
            try {
                while (cursor.next()) loaded.computeIfAbsent(cursor.longValue(0), key -> new java.util.HashSet<>()).add(cursor.intValue(1));
            } finally { cursor.dispose(); }
        }
        for (java.util.Map.Entry<Long, java.util.Set<Integer>> entry : loaded.entrySet()) entry.setValue(java.util.Collections.unmodifiableSet(entry.getValue()));
        vyrnDeletedMessages = java.util.Collections.unmodifiableMap(loaded);
        } catch (org.telegram.SQLite.SQLiteException failure) { vyrnDeletedMessages = null; throw failure; }
    }

'@
 $start=$t.IndexOf('    public TLRPC.Message getMessage(long dialogId, long msgId) {')
 $end=$t.IndexOf('    private TLRPC.Message getMessageInternal(', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned stored message lookup markers missing'}
 $section=$t.Substring($start,$end-$start)
 $marker='                        ref.set(message);'
 if(!$section.Contains($marker)){throw 'Pinned stored message decode marker missing'}
 $section=$section.Replace('SELECT data FROM messages_v2','SELECT data, custom_params FROM messages_v2')
 $section=$section.Replace($marker,"                        NativeByteBuffer localParams = cursor.byteBufferValue(1);`n                        if (localParams != null) { try { MessageCustomParamsHelper.readLocalParams(message, localParams); } finally { localParams.reuse(); } }`n"+$marker)
 $t=$t.Substring(0,$start)+$snapshot+"`n"+$section+$t.Substring($end)
 $edit="                                    NativeByteBuffer customParams = cursor.byteBufferValue(6);`n                                    MessageCustomParamsHelper.readLocalParams(message, customParams);"
 $replace='                    customParams = cursor.byteBufferValue(2);'
 foreach($marker in @($edit,$replace)){if(!$t.Contains($marker)){throw 'Pinned existing message params merge marker missing'}}
 $t=$t.Replace($edit,"                                    NativeByteBuffer customParams = cursor.byteBufferValue(6);`n                                    xyz.eclipseofficial.vyrn.compat.VyrnBridge.mergeLocalMessageParams(message, customParams);")
 $t.Replace($replace,$replace+"`n                    xyz.eclipseofficial.vyrn.compat.VyrnBridge.mergeLocalMessageParams(message, customParams);`n                    if (customParams != null) customParams.reuse();`n                    customParams = MessageCustomParamsHelper.writeLocalParams(message);")
}
Edit 'TMessagesProj/jni/tgnet/Connection.cpp' {
 param($t)
 if(!$t.Contains('VYRN: Telegram sockets are disabled')){$t=$t.Replace('void Connection::connect() {',"void Connection::connect() {`n    return; // VYRN: Telegram sockets are disabled; REST/WebSocket own transport.")};$t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java' {
 param($t)
 $t=$t.Replace('app.initPushServices();','// VYRN: Firebase is not initialized in the core build.')
 $t=$t.Replace('BillingController.getInstance().startConnection();','// VYRN: Telegram billing disabled.')
 $t=$t.Replace('LauncherIconController.tryFixLauncherIconIfNeeded();','// VYRN: fixed placeholder icon until final assets supplied.')
 $t=$t.Replace('ProxyRotationController.init();','// VYRN: Telegram proxy rotation disabled.')
 $start=$t.IndexOf('    public static void startPushService() {')
 $end=$t.IndexOf('    @Override', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned notification service startup missing'}
 $t=$t.Substring(0,$start)+"    public static void startPushService() { xyz.eclipseofficial.vyrn.compat.VyrnNotificationsService.refresh(); }`n`n"+$t.Substring($end)
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/NotificationsService.java' {
 param($t)
 "package org.telegram.messenger;`npublic class NotificationsService extends xyz.eclipseofficial.vyrn.compat.VyrnNotificationsService {}`n"
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/NotificationsSettingsActivity.java' {
 param($t)
 $t=$t.Replace('notificationsServiceConnectionRow = rowCount++;','notificationsServiceConnectionRow = -1; // VYRN has one managed connection, not Telegram push transport.')
 $t=$t.Replace('preferences.getBoolean("pushService", getMessagesController().keepAliveService)','preferences.getBoolean("pushService", false)')
 $marker='} else if (position == notificationsServiceRow) {'
 $start=$t.IndexOf($marker)
 $end=$t.IndexOf('} else if (position == callsVibrateRow)', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned notification service setting missing'}
 $part=$t.Substring($start,$end-$start).Replace('MessagesController.getNotificationsSettings(currentAccount)','MessagesController.getGlobalNotificationsSettings()')
 $t=$t.Substring(0,$start)+$part+$t.Substring($end)
 $t=$t.Replace('checkCell.setTextAndValueAndCheck(getString("NotificationsService", R.string.NotificationsService), getString("NotificationsServiceInfo", R.string.NotificationsServiceInfo), preferences.getBoolean("pushService", false), true, true);','checkCell.setTextAndValueAndCheck("fa".equals(java.util.Locale.getDefault().getLanguage()) ? "اتصال پس‌زمینه" : "Background connection", "fa".equals(java.util.Locale.getDefault().getLanguage()) ? "با اعلان دائمی، دریافت پیام پس از بستن صفحهٔ اپ فعال می‌ماند و باتری بیشتری مصرف می‌کند. توقف اجباری یا محدودیت باتری می‌تواند دریافت را متوقف کند." : "Keeps messaging active after closing the app screen, with a persistent notification and additional battery use. Force stop or battery restrictions can prevent delivery.", xyz.eclipseofficial.vyrn.compat.VyrnNotificationsService.enabled(), true, true);')
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/ImageUpdater.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/Components/ImageUpdater.java") -join "`n"
 $t=$t.Replace('private boolean canceled;',"private boolean canceled; private java.util.concurrent.atomic.AtomicBoolean vyrnAvatarUpload; private long vyrnAvatarOrigin;")
 $t=$t.Replace('canSelectVideo = allowVideo;','canSelectVideo = allowVideo && setForType != FOR_TYPE_USER; vyrnAvatarOrigin = UserConfig.getInstance(currentAccount).getClientUserId();')
 $t=$t.Replace('this.supportEmojiMarkup = supportEmojiMarkup;','this.supportEmojiMarkup = supportEmojiMarkup && setForType != FOR_TYPE_USER;')
 $t=$t.Replace('public void setDelegate(ImageUpdaterDelegate imageUpdaterDelegate) {',"public void setDelegate(ImageUpdaterDelegate imageUpdaterDelegate) {`n        if (parentFragment != null) { currentAccount = parentFragment.getCurrentAccount(); vyrnAvatarOrigin = UserConfig.getInstance(currentAccount).getClientUserId(); }")
 $t=$t.Replace('public void cancel() {',"public void cancel() {`n        if (vyrnAvatarUpload != null) vyrnAvatarUpload.set(true);")
 $t=$t.Replace('private void processBitmap(boolean fromAvatarConstructor, Bitmap bitmap, MessageObject avatarObject) {',"private void processBitmap(boolean fromAvatarConstructor, Bitmap bitmap, MessageObject avatarObject) {`n        if (vyrnAvatarUpload != null) vyrnAvatarUpload.set(true);")
 $upload='FileLoader.getInstance(currentAccount).uploadFile(uploadingImage, false, true, ConnectionsManager.FileTypePhoto);'
 if(!$t.Contains($upload)){throw 'Pinned profile image-upload marker missing'}
 $t=$t.Replace($upload,"if (setForType == FOR_TYPE_USER && type == TYPE_DEFAULT) vyrnAvatarUpload = xyz.eclipseofficial.vyrn.compat.VyrnBridge.uploadAvatar(currentAccount, uploadingImage, vyrnAvatarOrigin);`n                    else $upload")
 $failed="delegate.didUploadPhoto(uploadedPhoto, uploadedVideo, videoTimestamp, videoPath, bigPhoto, smallPhoto, isVideo, vectorMarkup);`n                    }`n                }`n                cleanup();"
 if(!$t.Contains($failed)){throw 'Pinned image-upload completion marker missing'}
 $t=$t.Replace($failed,"delegate.didUploadPhoto(uploadedPhoto, uploadedVideo, videoTimestamp, videoPath, bigPhoto, smallPhoto, isVideo, vectorMarkup);`n                    }`n                } else if (delegate != null) { delegate.didUploadFailed(); }`n                cleanup();")
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java") -join "`n"
 $marker='    public void didUploadPhoto(final TLRPC.InputFile photo, final TLRPC.InputFile video, double videoStartTimestamp, String videoPath, TLRPC.PhotoSize bigSize, final TLRPC.PhotoSize smallSize, boolean isVideo, TLRPC.VideoSize emojiMarkup) {'
 if(!$t.Contains($marker)){throw 'Pinned settings avatar callback missing'}
 $failed=@'
    @Override
    public void didUploadFailed() {
        avatar = null; avatarBig = null; showAvatarProgress(false, true);
        TLRPC.User current = getUserConfig().getCurrentUser(); if (current != null) setInfo(current);
        android.widget.Toast.makeText(ApplicationLoader.applicationContext, LocaleController.isRTL ? "بارگذاری عکس انجام نشد. دوباره تلاش کنید." : "Photo upload failed. Try again.", android.widget.Toast.LENGTH_LONG).show();
    }

'@
 $t=$t.Replace('    @Override'+"`n"+$marker,$failed+'    @Override'+"`n"+$marker)
 if(!$t.Contains('public void didUploadFailed()')){throw 'Settings photo error handler not inserted'}
 $begin=$t.IndexOf($marker);$end=$t.IndexOf('    private void showAvatarProgress(', $begin)
 if($begin -lt 0 -or $end -lt 0){throw 'Pinned settings photo response section missing'}
 $section=$t.Substring($begin,$end-$begin)
 $section=$section.Replace('final TLRPC.TL_photos_uploadProfilePhoto req =',"final long vyrnOrigin = getUserConfig().getClientUserId();`n                final TLRPC.TL_photos_uploadProfilePhoto req =")
 $section=$section.Replace('                    avatarUploadingRequest = -1;',"                    if (getUserConfig().getClientUserId() != vyrnOrigin) return;`n                    avatarUploadingRequest = -1;")
 $section=$section.Replace('                        user.photo = new TLRPC.TL_userProfilePhoto();',"                        TLRPC.UserProfilePhoto vyrnPreviousPhoto = user.photo;`n                        user.photo = new TLRPC.TL_userProfilePhoto();")
 $section=$section.Replace('                        user.photo.photo_id = photos_photo.photo.id;',"                        user.photo.photo_id = photos_photo.photo.id; user.photo.dc_id = photos_photo.photo.dc_id;")
 $section=$section.Replace('                        if (small != null && avatar != null) {',"                        if (!getUserConfig().vyrnSaveCurrentUser(user)) { user.photo = vyrnPreviousPhoto; didUploadFailed(); return; }`n                        if (small != null && avatar != null) {")
 $section=$section.Replace('                            userFull.profile_photo = photos_photo.photo;',"                            userFull.profile_photo = photos_photo.photo; userFull.flags |= 4;")
 $section=$section.Replace('                    getUserConfig().saveConfig(true);','')
 $t=$t.Substring(0,$begin)+$section+$t.Substring($end)
 $t=$t.Replace('                    avatar = null;',"                    if (error != null) didUploadFailed();`n                    avatar = null;")
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java' {
 param($t)
 $t=$t -replace 'public static boolean LOGS_ENABLED = [^;]+;', 'public static boolean LOGS_ENABLED = false;'
 $t=$t -replace 'LOGS_ENABLED = DEBUG_VERSION \|\| sharedPreferences.getBoolean\("logsEnabled", DEBUG_VERSION\);','LOGS_ENABLED = false;'
 $t=$t -replace 'public static int APP_ID = [^;]+;','public static int APP_ID = 0;' -replace 'public static String APP_HASH = [^;]+;','public static String APP_HASH = "";'
 $t
}
Edit 'TMessagesProj/src/main/AndroidManifest.xml' {
 param($t)
 $t=$t.Replace('android:usesCleartextTraffic="true"','android:usesCleartextTraffic="false"').Replace('android:supportsRtl="false"','android:supportsRtl="true"').Replace('android:allowBackup="true"','android:allowBackup="false"')
 $t=$t -replace '(?s)<activity-alias\s+android:enabled="false".*?</activity-alias>',''
 $t=$t -replace 'android:backupAgent="[^"]+"',''
 $t
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java") -join "`n"
 $start=$t.IndexOf('    private String getStringV2(');$end=$t.IndexOf('    private Localization localizationInternalDefault;', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned localization branding marker missing'}
 $body=$t.Substring($start,$end-$start).Replace('return value;','return xyz.eclipseofficial.vyrn.compat.VyrnBrand.text(value);').Replace('return localizationInternal.getByResName(fallback);','return xyz.eclipseofficial.vyrn.compat.VyrnBrand.text(localizationInternal.getByResName(fallback));')
 $t.Substring(0,$start)+$body+$t.Substring($end)
}
$logoSource=Join-Path $root 'app/src/main/res/drawable/vyrn_logo.jpg'
$logoMask=Join-Path $root 'app/src/main/res/drawable-nodpi/vyrn_mark.png'
$logoDir=Join-Path $upstream 'TMessagesProj/src/main/res/drawable-nodpi'
New-Item -ItemType Directory -Path $logoDir -Force | Out-Null
Copy-Item -LiteralPath $logoSource -Destination (Join-Path $logoDir 'vyrn_logo.jpg')
if(Test-Path -LiteralPath $logoMask){Copy-Item -LiteralPath $logoMask -Destination (Join-Path $logoDir 'vyrn_mark.png')}
# Replace existing resource identifiers so every native caller keeps working.
foreach($resource in @('telegram_logo','telegram_logo_2','logo_middle','intro_tg_plane','menu_invit_telegram','ic_launcher_dr','notification','tg_splash_320','tg_splash_108','icon_plane')){
 Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Recurse -File | Where-Object {$_.BaseName -eq $resource -and $_.Directory.Name -like 'drawable*'} | Remove-Item
 if(Test-Path -LiteralPath $logoMask){Copy-Item -LiteralPath $logoMask -Destination (Join-Path $logoDir ($resource+'.png'))}else{Copy-Item -LiteralPath $logoSource -Destination (Join-Path $logoDir ($resource+'.jpg'))}
}
if(Test-Path -LiteralPath $logoMask){
 # Activity aliases and share targets reference the upstream launcher IDs directly.
 foreach($launcher in @('ic_launcher','ic_launcher_round')){
  Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Recurse -File | Where-Object {$_.BaseName -eq $launcher -and $_.Directory.Name -like 'mipmap*'} | ForEach-Object {Remove-Item -LiteralPath $_.FullName}
  $launcherXml='<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@android:color/black"/><foreground android:drawable="@drawable/vyrn_launcher_foreground"/><monochrome android:drawable="@drawable/vyrn_launcher_foreground"/></adaptive-icon>'
  Write-Text (Join-Path $upstream ('TMessagesProj/src/main/res/mipmap-anydpi-v26/'+$launcher+'.xml')) $launcherXml
 }
 Copy-Item -LiteralPath (Join-Path $root 'app/src/main/res/drawable/vyrn_launcher_foreground.xml') -Destination (Join-Path $upstream 'TMessagesProj/src/main/res/drawable/vyrn_launcher_foreground.xml')
 # Native icon previews use foreground resources even when their aliases are disabled.
 $previews=@(Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Recurse -File | Where-Object {$_.Directory.Name -like 'mipmap*' -and $_.BaseName -match '^icon(?:_\d+)?_foreground(?:_sa)?$'} | Select-Object -ExpandProperty BaseName -Unique)
 foreach($preview in $previews){
  Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Recurse -File | Where-Object {$_.Directory.Name -like 'mipmap*' -and $_.BaseName -eq $preview} | ForEach-Object {Remove-Item -LiteralPath $_.FullName}
  Copy-Item -LiteralPath $logoMask -Destination (Join-Path $upstream ('TMessagesProj/src/main/res/mipmap-anydpi-v26/'+$preview+'.png'))
 }
 $imageData=[Convert]::ToBase64String([IO.File]::ReadAllBytes($logoMask))
 $animation=@{v='5.7.4';fr=60;ip=0;op=60;w=1024;h=1024;nm='Vyrn';ddd=0;assets=@(@{id='vyrn';w=1024;h=1024;u='';p=('data:image/png;base64,'+$imageData);e=1});layers=@(@{ddd=0;ind=1;ty=2;nm='Vyrn';refId='vyrn';sr=1;ks=@{o=@{a=0;k=100};r=@{a=0;k=0};p=@{a=0;k=@(512,512,0)};a=@{a=0;k=@(512,512,0)};s=@{a=0;k=@(100,100,100)}};ip=0;op=60;st=0;bm=0})}|ConvertTo-Json -Depth 12 -Compress
 foreach($raw in @('plane_logo_plain','qr_code_logo')){Write-Text (Join-Path $upstream ('TMessagesProj/src/main/res/raw/'+$raw+'.json')) $animation}
}
Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Recurse -Filter styles.xml | ForEach-Object {
 $style=[IO.File]::ReadAllText($_.FullName)
 $style=$style.Replace('<item name="android:windowSplashScreenAnimatedIcon">@drawable/tg_splash_320</item>','<item name="android:windowSplashScreenAnimatedIcon">@drawable/vyrn_launcher_foreground</item>')
 Write-Text $_.FullName $style
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java' {
 param($t)
 $t.Replace('logoDrawable.setBounds(0, dp(2), logoDrawable.getIntrinsicWidth(), dp(2) + logoDrawable.getIntrinsicHeight());','logoDrawable.setBounds(0, dp(2), dp(28), dp(30));')
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/ThemePreviewActivity.java' {param($t) $t.Replace('"Telegram Beta Chat"','"Vyrn Preview Chat"')}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAttachAlertDocumentLayout.java' {param($t) $t.Replace('fs.title = "Telegram";','fs.title = "Vyrn";')}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/QRCodeBottomSheet.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/Components/QRCodeBottomSheet.java") -join "`n"
 $t.Replace('public void setCenterAnimation(int resId) {','public void setCenterAnimation(int resId) { if (resId == org.telegram.messenger.R.raw.qr_code_logo || resId == org.telegram.messenger.R.raw.plane_logo_plain) { iconImage.setImageBitmap(xyz.eclipseofficial.vyrn.compat.VyrnBrand.notificationBitmap(getContext())); return; }')
}
foreach($relative in @('TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java','TMessagesProj/src/main/java/org/telegram/messenger/FilesMigrationService.java')){
 $nativeBuilder=$relative.EndsWith('FilesMigrationService.java')
 Edit $relative {
  param($t)
  $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:$relative") -join "`n"
  $icon='androidx.core.graphics.drawable.IconCompat.createWithBitmap(xyz.eclipseofficial.vyrn.compat.VyrnBrand.notificationBitmap(ApplicationLoader.applicationContext))'
  if($nativeBuilder){$icon+='.toIcon(ApplicationLoader.applicationContext)'}
  $t.Replace('.setSmallIcon(R.drawable.notification)',('.setSmallIcon('+$icon+')'))
 }
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java") -join "`n"
 $t=$t.Replace('new LoginActivity().setIntroView(frameContainerView, startMessagingButton)','new xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity()')
 # The pinned logout animator never starts or invokes its completion callback. Use the native default transition.
 $introTransition='(?s)\s+@Override\s+public AnimatorSet onCustomTransitionAnimation\(boolean isOpen, Runnable callback\) \{.*?\n    \}'
 if($t -notmatch $introTransition){throw 'Pinned intro transition changed'}
 $t=[regex]::Replace($t,$introTransition,'')
 $t.Replace('logoDrawable.setBounds(0, dp(8.666f), dp(115), dp(35));','logoDrawable.setBounds(0, 0, dp(36), dp(36));')
}
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/TelegramQRCodeWriter.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/TelegramQRCodeWriter.java") -join "`n"
 $t.Replace('SvgHelper.getBitmap(readRes(R.raw.qr_logo), imageSize, imageSize, false)','Bitmap.createScaledBitmap(xyz.eclipseofficial.vyrn.compat.VyrnBrand.notificationBitmap(ApplicationLoader.applicationContext), imageSize, imageSize, true).copy(Bitmap.Config.ARGB_8888, true)')
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/SessionsActivity.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/SessionsActivity.java") -join "`n"
 foreach($marker in @('if (currentType == 0 && getMessagesController().qrLoginCamera) {','ttlDays = res.authorization_ttl_days;')){if(!$t.Contains($marker)){throw 'Pinned session feature gate changed'}}
 $t=$t.Replace('if (currentType == 0 && getMessagesController().qrLoginCamera) {','if (false) { // VYRN QR authorization is unavailable.')
 $t=$t.Replace('ttlDays = res.authorization_ttl_days;','ttlDays = 0; // VYRN session lifetime changes are unavailable.')
 $single=@'
                        if (listAdapter != null) {
                            listAdapter.notifyDataSetChanged();
                        }
                    }
                }));
            }
        });
        bottomSheet.show();
'@
 if(!$t.Contains($single)){throw 'Pinned session revocation callback changed'}
 $feedback='BulletinFactory.of(SessionsActivity.this).createSimpleBulletin(R.raw.error, getString(R.string.UnknownError)).show();'
 $t=$t.Replace($single,$single.Replace("                    }`n                }));", "                    } else { $feedback }`n                }));"))
 $bulk=@'
                                    loadSessions(false);
                                }
                            });
'@
 if(!$t.Contains($bulk)){throw 'Pinned bulk session revocation callback changed'}
 $t=$t.Replace($bulk,$bulk.Replace("                                }`n                            });", "                                } else { $feedback }`n                            });"))
 foreach($marker in @('private int ttlDays;','if (position == ttlRow) {','loading = false;','//                itemsEnterAnimator.showItemsAnimated(oldItemsCount + 1);','qrCodeRow = -1;','if (position == terminateAllSessionsRow) {')){if(!$t.Contains($marker)){throw 'Pinned session retry marker changed'}}
 $t=$t.Replace('private int ttlDays;',"private int ttlDays;`n    private boolean sessionLoadFailed;`n    private int sessionRetryRow = -1;`n    private String sessionLoadError() { return java.util.Locale.getDefault().getLanguage().equals(`"fa`") ? `"فهرست دستگاه‌ها دریافت نشد`" : `"Could not load devices`"; }")
 $click="listView.setOnItemClickListener((view, position) -> {`n            if (position == ttlRow) {"
 if(!$t.Contains($click)){throw 'Pinned session row click marker changed'}
 $t=$t.Replace($click,"listView.setOnItemClickListener((view, position) -> {`n            if (position == sessionRetryRow) { loadSessions(false); } else if (position == ttlRow) {")
 $t=$t.Replace('loading = false;', 'loading = false; sessionLoadFailed = currentType == 0 && error != null;')
 $t=$t.Replace('//                itemsEnterAnimator.showItemsAnimated(oldItemsCount + 1);','if (error != null) updateRows();')
 $t=$t.Replace('qrCodeRow = -1;',"sessionRetryRow = sessionLoadFailed ? rowCount++ : -1;`n        qrCodeRow = -1;")
 $t=$t.Replace('return position == terminateAllSessionsRow ||', 'return position == sessionRetryRow && !loading || position == terminateAllSessionsRow ||')
 $bind="TextCell textCell = (TextCell) holder.itemView;`n                    if (position == terminateAllSessionsRow) {"
 if(!$t.Contains($bind)){throw 'Pinned session retry binding changed'}
 $t=$t.Replace($bind,"TextCell textCell = (TextCell) holder.itemView;`n                    if (position == sessionRetryRow) { textCell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular); textCell.setTag(Theme.key_text_RedRegular); textCell.setTextAndValue(sessionLoadError(), getString(R.string.Retry), false); } else if (position == terminateAllSessionsRow) {")
 foreach($method in @('public long getItemId(int position) {','public int getItemViewType(int position) {')){
  $marker="$method`n            if (position == terminateAllSessionsRow) {"
  if(!$t.Contains($marker)){throw 'Pinned session retry adapter changed'}
  if($method.StartsWith('public long')){$t=$t.Replace($marker,"$method`n            if (position == sessionRetryRow) return Objects.hash(0, 14);`n            if (position == terminateAllSessionsRow) {")}
  else{$t=$t.Replace($marker,"$method`n            if (position == sessionRetryRow || position == terminateAllSessionsRow) {")}
 }
 $t
}
Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Filter strings.xml -Recurse | ForEach-Object {
 $t=[IO.File]::ReadAllText($_.FullName)
 $t=$t -replace '(<string name="AppName">)[^<]+','$1Vyrn' -replace '(<string name="AppNameBeta">)[^<]+','$1Vyrn Dev'
 $t=[regex]::Replace($t,'(?s)>([^<]+)<',{param($m) '>'+([regex]::Replace($m.Groups[1].Value,'(?i)(?<![./:@\w])telegram(?![.\w])|تلگرام','Vyrn'))+'<'})
 Write-Text $_.FullName $t
}
Write-Output 'Pinned Telegram prepared for VYRN. Original UI retained; Telegram transport disabled.'
Edit 'build.gradle' {
 param($t)
 if(!$t.Contains('VYRN_AIDL_WINDOWS_FIX')){
 $t+=@'

// VYRN_AIDL_WINDOWS_FIX: AIDL 35 emits unescaped Windows paths in generated Java comments.
subprojects {
    tasks.withType(JavaCompile).configureEach {
        options.encoding = 'UTF-8'
        doFirst {
            source.files.findAll { it.path.contains('aidl_source_output_dir') && it.name.endsWith('.java') }.each { f ->
                String text = f.getText('UTF-8')
                String corrected = text.replaceAll(/(?m)^ \* Using:.*$/) { line -> line.replace('\\', '/') }
                if (corrected != text) f.setText(corrected, 'UTF-8')
            }
        }
    }
}
'@
 };$t
}
