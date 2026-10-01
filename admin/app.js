'use strict';
const $=id=>document.getElementById(id);
const dictionary={fa:{restricted:'مدیریت شبکه',welcome:'شبکه شما.<br>کنترل شما.',loginHelp:'با حساب مدیر وارد شوید و سپس کد برنامه احراز هویت را وارد کنید.',email:'ایمیل',password:'رمز عبور',signIn:'ورود',verify:'تأیید دسترسی',code:'کد احراز هویت',unlock:'ورود به پنل',workspace:'شبکه خصوصی',overview:'نمای کلی',refresh:'تازه‌سازی',logout:'خروج',users:'کاربران',invites:'دعوت‌ها',groups:'گروه‌ها',audit:'گزارش فعالیت',uses:'تعداد استفاده',days:'اعتبار به روز',createInvite:'ساخت دعوت',empty:'هنوز داده‌ای ثبت نشده است.',privacy:'محتوای پیام‌های خصوصی در دسترس مدیر نیست. تماس صوتی و تصویری غیرفعال است.',newPassword:'رمز عبور جدید',resetPassword:'بازنشانی رمز',revoke:'خروج همه نشست‌ها',close:'بستن',sessions:'نشست‌ها',storage:'حافظه مصرفی',name:'نام',status:'وضعیت',created:'ساخته‌شده',action:'عملیات',manage:'مدیریت',active:'فعال',disabled:'مسدود',block:'مسدود کردن',unblock:'رفع مسدودی',remaining:'باقی‌مانده',expiry:'انقضا',members:'اعضا',actor:'انجام‌دهنده',target:'هدف',done:'انجام شد',failed:'درخواست انجام نشد. اتصال و دسترسی را بررسی کنید.',confirm:'این تغییر اعمال شود؟',inviteNote:'این کد فقط همین بار نمایش داده می‌شود: '}};
const english={};document.querySelectorAll('[data-i18n]').forEach(e=>english[e.dataset.i18n]=e.innerHTML);
Object.assign(english,{sessions:'Sessions',storage:'Storage used',name:'Name',status:'Status',created:'Created',action:'Action',manage:'Manage',active:'Active',disabled:'Blocked',block:'Block user',unblock:'Unblock user',remaining:'Remaining',expiry:'Expires',members:'Members',actor:'Actor',target:'Target',done:'Saved',failed:'Request failed. Check your connection and access.',confirm:'Apply this change?',inviteNote:'Save this invite code; it is shown only once: '});
let language=navigator.language.startsWith('fa')?'fa':'en';let session=null;let adminToken='';let page='users';let selected=null;let refreshFlight=null;
function newDevice(){const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;const h=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);}
let device;try{device=localStorage.getItem('vyrn_control_device')||newDevice();localStorage.setItem('vyrn_control_device',device);}catch{device=newDevice();}
function tr(key){return dictionary[language]?.[key]??english[key]??key;}
function setLanguage(){document.documentElement.lang=language;document.documentElement.dir=language==='fa'?'rtl':'ltr';document.querySelectorAll('[data-i18n]').forEach(e=>{const key=e.dataset.i18n;if(key==='welcome'){e.replaceChildren();tr(key).split('<br>').forEach((line,i)=>{if(i)e.append(document.createElement('br'));e.append(document.createTextNode(line));});}else e.textContent=tr(key);});$('language').textContent=language==='fa'?'English':'فارسی';}
function notice(text){$('notice').textContent=text;}
function view(name){for(const id of ['login','verify','control'])$(id).hidden=id!==name;}
async function api(path,{method='GET',body=null,admin=false,retry=true}={}){
 const sentAccess=session?.access_token;
 const headers={'Content-Type':'application/json'};if(session)headers.Authorization='Bearer '+session.access_token;if(admin)headers['X-VYRN-Admin-Token']=adminToken;
 const response=await fetch('/v1/'+path,{method,headers,body:body===null?undefined:JSON.stringify(body),cache:'no-store',credentials:'omit',redirect:'error'});
 if(response.status===401&&session&&retry){try{
   if(session.access_token===sentAccess){
     if(!refreshFlight){const saved=session;refreshFlight=fetch('/v1/auth/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refresh_token:saved.refresh_token}),cache:'no-store',credentials:'omit',redirect:'error'}).then(async result=>{if(!result.ok)throw new Error('refresh_failed');session=await result.json();}).finally(()=>{refreshFlight=null;});}
     await refreshFlight;
   }
   return api(path,{method,body,admin,retry:false});
 }catch(error){session=null;adminToken='';view('login');throw error;}}
 if(response.status===403&&admin){adminToken='';view('verify');throw new Error('verification_required');}
 if(!response.ok)throw new Error('request_failed');return response.status===204?null:response.json();
}
async function busy(button,fn){button.disabled=true;notice('');try{await fn();}catch(error){notice(tr('failed'));}finally{button.disabled=false;}}
$('language').onclick=()=>{language=language==='fa'?'en':'fa';setLanguage();if(session&&adminToken)load().catch(()=>notice(tr('failed')));};
$('login-form').onsubmit=e=>{e.preventDefault();busy(e.submitter,async()=>{session=await api('auth/login',{method:'POST',body:{email:$('email').value,password:$('password').value,device_id:device,device_name:'VYRN Control'},retry:false});$('password').value='';view('verify');$('code').focus();});};
$('verify-form').onsubmit=e=>{e.preventDefault();busy(e.submitter,async()=>{const grant=await api('admin/unlock',{method:'POST',body:{code:$('code').value}});adminToken=grant.admin_token;$('code').value='';view('control');await load();});};
$('logout').onclick=()=>busy($('logout'),async()=>{await api('auth/logout',{method:'POST',body:{}});session=null;adminToken='';$('table-body').replaceChildren();view('login');});
$('refresh').onclick=()=>busy($('refresh'),load);
document.querySelectorAll('[data-page]').forEach(button=>button.onclick=()=>busy(button,async()=>{page=button.dataset.page;document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',b===button));await load();}));
const date=value=>value?new Date(value).toLocaleString(language):'—';
function cell(text){const td=document.createElement('td');td.textContent=String(text??'—');return td;}
function head(names){$('table-head').replaceChildren();const row=document.createElement('tr');for(const name of names){const th=document.createElement('th');th.textContent=tr(name);row.append(th);}$('table-head').append(row);}
function metrics(data){$('metrics').replaceChildren();for(const [key,value]of [['users',data.users],['sessions',data.sessions],['groups',data.groups],['storage',(data.storage_bytes/1024/1024).toFixed(1)+' MB']]){const box=document.createElement('div');box.className='metric';const label=document.createElement('span');label.className='label';label.textContent=tr(key);const number=document.createElement('strong');number.textContent=value;box.append(label,number);$('metrics').append(box);}}
async function load(){
 const [dashboard,rows]=await Promise.all([api('admin/dashboard',{admin:true}),api('admin/'+page,{admin:true})]);metrics(dashboard);$('table-body').replaceChildren();$('empty').hidden=rows.length!==0;$('invite-actions').hidden=page!=='invites';
 const columns={users:['name','email','status','action'],invites:['target','remaining','expiry','action'],groups:['name','members','created'],audit:['created','actor','action','target']};head(columns[page]);
 for(const record of rows){const row=document.createElement('tr');
 if(page==='users'){row.append(cell(record.display_name),cell(record.email),cell(tr(record.disabled?'disabled':'active')));const td=cell('');const button=document.createElement('button');button.className='quiet';button.textContent=tr('manage');button.onclick=()=>{selected=record;$('user-title').textContent=record.display_name;$('user-id').value=record.id;$('new-password').value='';$('block').textContent=tr(record.disabled?'unblock':'block');$('user-dialog').showModal();};td.append(button);row.append(td);}
 if(page==='invites'){row.append(cell(record.id),cell(record.remaining),cell(date(record.expires_at)));const td=cell('');const button=document.createElement('button');button.className='quiet';button.textContent=tr('block');button.onclick=()=>busy(button,async()=>{if(!confirm(tr('confirm')))return;await api('admin/invites/'+record.id,{method:'DELETE',admin:true});await load();});td.append(button);row.append(td);}
 if(page==='groups')row.append(cell(record.title),cell(record.member_count),cell(date(record.created_at)));
 if(page==='audit')row.append(cell(date(record.created_at)),cell(record.actor_id),cell(record.action),cell(record.target_id));$('table-body').append(row);
 }
}
$('invite-form').onsubmit=e=>{e.preventDefault();busy(e.submitter,async()=>{const invite=await api('admin/invites',{method:'POST',body:{uses:Number($('uses').value),days:Number($('days').value)},admin:true});$('invite-output').textContent=tr('inviteNote')+invite.code;await load();});};
$('close-dialog').onclick=()=>$('user-dialog').close();$('user-form').onsubmit=e=>e.preventDefault();
$('block').onclick=()=>busy($('block'),async()=>{if(!confirm(tr('confirm')))return;await api('admin/users/'+selected.id,{method:'PATCH',body:{disabled:!selected.disabled},admin:true});$('user-dialog').close();await load();});
$('revoke').onclick=()=>busy($('revoke'),async()=>{if(!confirm(tr('confirm')))return;await api('admin/users/'+selected.id+'/revoke-sessions',{method:'POST',body:{},admin:true});notice(tr('done'));});
$('reset-password').onclick=()=>busy($('reset-password'),async()=>{if(!$('new-password').reportValidity()||!$('new-password').value)return;if(!confirm(tr('confirm')))return;await api('admin/users/'+selected.id+'/reset-password',{method:'POST',body:{new_password:$('new-password').value},admin:true});$('new-password').value='';notice(tr('done'));});
setLanguage();
