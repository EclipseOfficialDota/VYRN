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
 if(!$t.Contains("com.squareup.okhttp3:okhttp:5.3.0")){$t+="`ndependencies { implementation 'com.squareup.okhttp3:okhttp:5.3.0' }`n"}
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
 $t -replace '(?s)private BaseFragment getClientNotActivatedFragment\(\) \{.*?\n    \}', 'private BaseFragment getClientNotActivatedFragment() { return new xyz.eclipseofficial.vyrn.compat.VyrnLoginActivity(); }'
}
Edit 'TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java") -join "`n"
 $marker='private void sendRequestInternal(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId, int connectionType, boolean immediate, int requestToken) {'
 if(!$t.Contains($marker)){throw 'Pinned request dispatch marker missing'}
 $t=$t.Replace($marker,$marker+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.dispatch(currentAccount, object, onComplete, onCompleteTimestamp, requestToken)) return;")
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
Edit 'TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java") -join "`n"
 $marker='public void cancelSendingMessage(ArrayList<MessageObject> objects) {'
 if(!$t.Contains($marker)){throw 'Pinned message cancellation marker missing'}
 $t=$t.Replace($marker,$marker+"`n        if (xyz.eclipseofficial.vyrn.compat.VyrnBridge.cancelSendingMessages(currentAccount, objects)) return;")
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
 $t.Replace('    public boolean retrySendMessage(MessageObject messageObject, boolean unsent, long payStars) {',$proof+'    public boolean retrySendMessage(MessageObject messageObject, boolean unsent, long payStars) {')
}
Edit 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java' {
 param($t)
 $t=(& git -c "safe.directory=$($upstream.Replace('\','/'))" -C $upstream show "${pin}:TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java") -join "`n"
 $marker='        editingMessageObject = messageObject;'
 if(!$t.Contains($marker)){throw 'Pinned composer edit marker missing'}
 $t.Replace($marker,$marker+"`n        xyz.eclipseofficial.vyrn.compat.VyrnBridge.captureEdit(currentAccount, messageObject);")
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
 $start=$t.IndexOf('    public TLRPC.Message getMessage(long dialogId, long msgId) {')
 $end=$t.IndexOf('    private TLRPC.Message getMessageInternal(', $start)
 if($start -lt 0 -or $end -lt 0){throw 'Pinned stored message lookup markers missing'}
 $section=$t.Substring($start,$end-$start)
 $marker='                        ref.set(message);'
 if(!$section.Contains($marker)){throw 'Pinned stored message decode marker missing'}
 $section=$section.Replace('SELECT data FROM messages_v2','SELECT data, custom_params FROM messages_v2')
 $section=$section.Replace($marker,"                        NativeByteBuffer localParams = cursor.byteBufferValue(1);`n                        if (localParams != null) { try { MessageCustomParamsHelper.readLocalParams(message, localParams); } finally { localParams.reuse(); } }`n"+$marker)
 $t=$t.Substring(0,$start)+$section+$t.Substring($end)
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
Get-ChildItem (Join-Path $upstream 'TMessagesProj/src/main/res') -Filter strings.xml -Recurse | ForEach-Object {
 $t=[IO.File]::ReadAllText($_.FullName)
 $t=$t -replace '(<string name="AppName">)[^<]+','$1VYRN' -replace '(<string name="AppNameBeta">)[^<]+','$1VYRN Dev'
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
