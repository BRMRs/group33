/* THROWAWAY C v2. All records, photos, clocks and notifications are memory-only stubs. */
for(const href of ['c-prototype.css','c-v2.css']){const link=document.createElement('link');link.rel='stylesheet';link.href=href;document.head.append(link)}
document.body.className='c-mode';
const C_COPY={
 zh:{study:'C 版 · 设计验证',elder:'长者端',caregiver:'照护者 · 模拟视图',brand:'安心用药',home:'到时间会提醒您',next:'下一次',dueHome:'有一次吃药提醒',open:'看看提醒',alarm:'该吃药了',scope:n=>`这次 ${n} 盒`,taken:'药吃好了',later:'10 分钟后提醒',help:'需要帮助',camera:'拍药盒',photoTitle:'拍一张药盒',frame:'把药盒放进画面',shutter:'拍照',usePhoto:'使用这张',retake:'重拍',photoSaved:'照片已保存',done:'完成',back:'返回',homeButton:'主页',recorded:'这次吃药已记下',snoozed:'稍后再提醒您',again:'再提醒',handoff:'这次已交给照护者',handoffSub:'不用再操作这次提醒',helpSaved:'求助已记下',helpSub:'这次提醒已交给照护者',careHandled:'这次照护者已处理',zoom:'查看药盒照片',box:n=>`药盒 ${n}`,amount:n=>`${n}片`,powerOff:'设备已关机 · 演示',lateSnooze:'这次将交给照护者',lateSnoozeSub:'不会再延后十分钟提醒',careRole:'照护者 · 模拟视图',todo:'本次跟进',original:'原提醒',localCutoff:'长者端停止提醒',pending:'待联系',needEntry:'已联系 · 待补登记',registered:'已补登记',unknownRegistered:'已补登记 · 仍需关注',responded:'已收到长者确认',noTask:'暂时无需跟进',helpReason:'长者请求帮助',timeoutReason:'30 分钟未收到确认',deviceReason:'设备失去联系',deviceDescription:'无法确定是断网、关机还是没电，请联系长者核实。',lastContact:'最后收到设备消息',detected:'失联告警时间',deviceRestored:'设备联系已恢复',deviceStillLost:'已联系，设备仍未恢复',markContact:'标记已联系',contactNote:'联系后请补登记本次结果，无需让长者回来操作。',beforeDue:'提醒尚未到时，暂不登记未来的服药结果。',entryTitle:'补登记本次结果',outcome:'联系后了解到的情况',yes:'已服用',no:'未服用',unsure:'无法确认',actualTime:'实际服药时间',unknownTime:'实际时间无法确认',note:'备注（可选）',save:'保存补登记',update:'更新登记',source:'登记来源',careSource:'照护者补登记',elderSource:'长者自行确认',entryAt:'补登记时间',reportAt:'长者操作时间',receivedAt:'记录接收时间',unknown:'未确定',entrySaved:'本次结果已补登记',notTakenNote:'登记未服用，不自动安排补服。',unsureNote:'结果仍不明确，请继续关注；不标成已服用或未服用。',takenNote:'照护者根据联系情况登记，不代表系统核实吞服。',saveError:'请先选择结果。',timeError:'请填写实际时间，或选择时间无法确认。',futureError:'实际服药时间不能晚于当前模拟时间。',deviceAckPending:'处理结果待设备接收，离线手机上的旧提醒不能立即远程关闭。',deviceAckDone:'长者设备已接收处理结果，旧提醒已关闭。',conflict:'新收到的长者记录与补登记不同，请核对后更新登记。',latest:'最新已接收记录',noResponse:'尚未收到本次回应',planned:'提醒时间已设定',elderLater:'长者选择十分钟后提醒',elderHelp:'长者提出求助',accepted:'最新记录已接收',receiveError:'记录接收失败，需要处理',transmission:'传输与接收记录',notifications:'模拟通知',times:'次',history:'跟进记录',noHistory:'暂无操作记录',photos:'药盒照片待查看',photoAt:'拍摄时间',photoReceived:'照片接收时间',photoReview:'标记已查看',photoReviewDone:'已查看；未改变现有用药计划',photoPending:'收到照片后由照护者查看，不自动新增用药。',controls:'演示控制台',time:'模拟时间',ring:'触发闹钟',reset:'重置到主页',step2:'+2 分钟',step5:'+5 分钟',step10:'+10 分钟',step30:'+30 分钟',count:'本次药盒数量（重置本次演示）',connection:'设备状态',online:'正常连接',offline:'手机开机但断网',off:'手机关机',reject:'模拟服务端拒收记录',samplePhoto:'模拟拍一张药盒',font:'字体兼容检查',voice:'启用提醒语音',voiceText:'该吃药了。请看药盒照片。药吃好了，点药吃好了。暂时忙，可以十分钟后提醒。',state:'完整模拟状态',rules:'原提醒后 30 分钟交接；设备连续 2 分钟无消息单独告警（演示值）。',demo:'所有通知、跟进和照片接收均为本页模拟；不会发消息、拨号或写入 Android。',research:'规则与验证说明',noPhotos:'尚未收到药盒照片',photosFixtures:'真实药盒照片，组合与数量仅作交互示例。',linkA:'历史 A',linkB:'历史 B',eventTaken:'收到长者服药报告',eventLater:'收到延后请求',eventHelp:'收到求助',eventDisconnect:'生成设备失联告警',eventRecover:'设备重新联系',eventTimeout:'生成未确认跟进',eventContact:'照护者已联系，待补登记',eventEntry:'照护者补登记',eventPhoto:'收到药盒照片',eventConflict:'发现补登记与迟到记录不同',eventHandoff:'手机停止旧提醒',eventReceived:'设备接收照护者处理结果',rejectedAt:'最近接收失败',none:'—'},
 en:{study:'C · Design prototype',elder:'Elder',caregiver:'Caregiver · Simulation',brand:'CareDose',home:'We will remind you when it is time',next:'Next reminder',dueHome:'A medicine reminder is waiting',open:'See reminder',alarm:'Time for your medicine',scope:n=>`${n} boxes this time`,taken:'Medicines taken',later:'Remind in 10 minutes',help:'Need help',camera:'Photograph a box',photoTitle:'Photograph your medicine box',frame:'Keep the box inside the picture',shutter:'Take photo',usePhoto:'Use this photo',retake:'Retake',photoSaved:'Photo saved',done:'Done',back:'Back',homeButton:'Home',recorded:'Your report is recorded',snoozed:'We will remind you later',again:'Next reminder',handoff:'Your caregiver will follow up',handoffSub:'No more action needed for this reminder',helpSaved:'Your help request is recorded',helpSub:'This reminder is now for your caregiver to follow up',careHandled:'Your caregiver handled this reminder',zoom:'View box photo',box:n=>`Box ${n}`,amount:n=>`${n} ${n===1?'tablet':'tablets'}`,powerOff:'Device powered off · Demo',lateSnooze:'Your caregiver will follow up',lateSnoozeSub:'There will not be another ten-minute reminder',careRole:'Caregiver · Simulation',todo:'Follow-up for this reminder',original:'Original reminder',localCutoff:'Elder reminders stop at',pending:'Contact needed',needEntry:'Contact made · Record result',registered:'Result recorded',unknownRegistered:'Recorded · Still needs attention',responded:'Elder response received',noTask:'No follow-up needed yet',helpReason:'Elder requested help',timeoutReason:'No confirmation received within 30 minutes',deviceReason:'Device contact lost',deviceDescription:'Network loss, power-off and a flat battery cannot be distinguished. Contact the elder to check.',lastContact:'Last device contact',detected:'Connection alert at',deviceRestored:'Device contact restored',deviceStillLost:'Contact made; device still unreachable',markContact:'Mark contact made',contactNote:'Record the result after making contact. Do not ask the elder to return and tap.',beforeDue:'The reminder is not due yet. Do not record a future medicine outcome.',entryTitle:'Record the outcome',outcome:'What did you learn after contacting them?',yes:'Taken',no:'Not taken',unsure:'Unable to confirm',actualTime:'Actual intake time',unknownTime:'Actual time is unknown',note:'Note (optional)',save:'Save outcome',update:'Update outcome',source:'Record source',careSource:'Recorded by caregiver',elderSource:'Reported by elder',entryAt:'Caregiver entry time',reportAt:'Elder action time',receivedAt:'Record received at',unknown:'Unknown',entrySaved:'Outcome recorded',notTakenNote:'Records non-intake; does not schedule a replacement dose.',unsureNote:'The result is still unclear. Continue following up; do not classify it as taken or not taken.',takenNote:'Based on caregiver follow-up, not system-verified ingestion.',saveError:'Choose an outcome first.',timeError:'Enter the actual time or select time unknown.',futureError:'Actual intake time cannot be after the simulated current time.',deviceAckPending:'Device acknowledgement pending. An offline phone cannot immediately receive a remote stop.',deviceAckDone:'Elder device received the handoff; old reminder stopped.',conflict:'A newly received elder report differs from the caregiver entry. Check and update the outcome.',latest:'Latest received record',noResponse:'No response received for this reminder',planned:'Reminder is set',elderLater:'Elder requested a ten-minute delay',elderHelp:'Elder requested help',accepted:'Latest record received',receiveError:'Record rejected; attention needed',transmission:'Transmission and receipt details',notifications:'Simulated notifications',times:'',history:'Follow-up history',noHistory:'No actions yet',photos:'Medicine-box photos to review',photoAt:'Photo captured at',photoReceived:'Photo received at',photoReview:'Mark reviewed',photoReviewDone:'Reviewed; existing reminder plan unchanged',photoPending:'Caregiver reviews received photos. No medication is added automatically.',controls:'Demo controls',time:'Simulated time',ring:'Trigger alarm',reset:'Reset to Home',step2:'+2 minutes',step5:'+5 minutes',step10:'+10 minutes',step30:'+30 minutes',count:'Boxes (resets this reminder)',connection:'Device state',online:'Connected',offline:'Phone on, network disconnected',off:'Phone powered off',reject:'Simulate record rejection',samplePhoto:'Simulate a box photo',font:'Font compatibility check',voice:'Enable spoken reminder',voiceText:'Time for your medicine. Look at the box photos. After taking the medicine, tap Medicines taken. If busy, choose Remind in ten minutes.',state:'Full simulation state',rules:'Handoff after 30 minutes. Separate device alert after 2 minutes without contact (demo value).',demo:'Notifications, follow-up and photo receipt are local simulations. No messages, calls or Android changes.',research:'Rules and verification notes',noPhotos:'No medicine-box photos received',photosFixtures:'Real packaging photos; grouping and quantities are interaction fixtures.',linkA:'Historical A',linkB:'Historical B',eventTaken:'Elder intake report received',eventLater:'Delay request received',eventHelp:'Help request received',eventDisconnect:'Device contact alert generated',eventRecover:'Device reconnected',eventTimeout:'No-confirmation follow-up generated',eventContact:'Caregiver contacted elder; outcome needed',eventEntry:'Caregiver recorded an outcome',eventPhoto:'Box photo received',eventConflict:'Caregiver entry differs from arriving elder report',eventHandoff:'Phone stopped the old reminder',eventReceived:'Device received caregiver handoff',rejectedAt:'Latest failed receipt',none:'—'}
};
const C_ICONS={check:'<path d="m5 12 4 4L19 6"/>',clock:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',help:'<path d="M8 8a4 4 0 0 1 8 0c0 3-4 3-4 6m0 4h.01"/><circle cx="12" cy="12" r="10"/>',camera:'<path d="M3 7h4l2-3h6l2 3h4v14H3Z"/><circle cx="12" cy="13" r="4"/>',zoom:'<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6M7 10h6m-3-3v6"/>'};
const cIcon=name=>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${C_ICONS[name]}</svg>`;
const cState={lang:navigator.language.startsWith('zh')?'zh':'en',now:1075,planned:1080,cutoff:1110,lossDelay:2,phase:'scheduled',screen:'home',count:6,font:100,voice:false,link:'online',reject:false,snoozeAt:null,records:[],serverRecords:[],lastContact:1075,loss:null,lossEpisode:0,case:null,careEntries:[],careDraft:{outcome:'',actualTime:'',unknownTime:false,note:''},entryOpen:false,entryError:'',serverTakeover:false,deviceAck:false,conflict:false,serverErrorAt:null,notifications:[],history:[],sequence:0,photos:['assets/box-a.jpg','assets/box-b.jpg','assets/box-c.jpg','assets/box-d.jpg','assets/box-e.jpg','assets/box-f.jpg'],photoQueue:[],serverPhotos:[],candidate:null,candidateAt:null,zoom:0,returnScreen:'alarm'};
const cT=()=>C_COPY[cState.lang];
const cTime=m=>m===null||m===undefined?'—':`${String(Math.floor(m/60)%24).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;
const cEscape=x=>String(x).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
const cButton=(label,act,kind='',icon='',extra='')=>`<button type="button" class="c-action ${kind}" data-c="${act}" ${extra}>${icon?cIcon(icon):''}<span>${label}</span></button>`;
const cLatest=()=>cState.serverRecords.at(-1);
const cEntry=()=>cState.careEntries.at(-1);
const cHistory=(key,details={})=>cState.history.push({key,at:cState.now,...details});
function cNotify(key,reason){if(cState.notifications.some(n=>n.key===key))return;cState.notifications.push({key,reason,at:cState.now})}
function cEnsureCase(reason){if(!cState.case)cState.case={reason,at:cState.now,stage:'pending',contactAt:null};else if(reason==='help'&&cState.case.stage!=='recorded')cState.case.reason='help'}
function cReceive(){
 if(cState.link!=='online')return;
 cState.lastContact=cState.now;
 if(cState.loss&&!cState.loss.recoveredAt){cState.loss.recoveredAt=cState.now;cHistory('eventRecover')}
 for(const record of cState.records.filter(r=>r.status!=='received')){
  if(cState.reject){record.status='rejected';cState.serverErrorAt=cState.now;continue}
  record.status='received';record.receivedAt=cState.now;cState.serverRecords.push({...record});cState.serverErrorAt=null;
  cHistory({taken:'eventTaken',snooze:'eventLater',help:'eventHelp'}[record.type],{actionAt:record.at});
  if(record.type==='help'&&!cEntry()){cEnsureCase('help');cNotify('help','help')}
  if(record.type==='taken'&&cEntry()&&cEntry().outcome!=='taken'){
   cState.conflict=true;cEnsureCase('conflict');cState.case.stage='contacted';cHistory('eventConflict');cNotify('conflict','conflict');
  }else if(record.type==='taken'&&cState.case&&cState.case.stage==='pending'&&!cEntry())cState.case.stage='responded';
 }
 for(const photo of cState.photoQueue.filter(p=>!p.receivedAt)){
  if(cState.reject)continue;
  photo.receivedAt=cState.now;cState.serverPhotos.push({...photo});cHistory('eventPhoto');
 }
 if(cState.serverTakeover&&!cState.deviceAck){cState.deviceAck=true;cHistory('eventReceived')}
}
function cLocal(){
 if(cState.link==='off')return;
 const camera=['camera','photo-review','photo-saved'].includes(cState.screen);
 if(cState.serverTakeover&&cState.deviceAck&&cState.phase!=='taken'){
  cState.phase='care-handled';cState.snoozeAt=null;if(!camera)cState.screen='home';return;
 }
 if(cState.now>=cState.cutoff&&['scheduled','due','snoozed'].includes(cState.phase)){
  cState.phase='handoff';cState.snoozeAt=null;if(!camera)cState.screen='home';cHistory('eventHandoff');return;
 }
 if(cState.now<cState.cutoff&&cState.phase==='scheduled'&&cState.now>=cState.planned){cState.phase='due';if(!camera)cState.screen='alarm';cSpeak()}
 if(cState.phase==='snoozed'&&cState.now>=cState.snoozeAt&&cState.now<cState.cutoff){cState.phase='due';cState.snoozeAt=null;if(!camera)cState.screen='alarm';cSpeak()}
}
function cProcess(){
 cReceive();cLocal();
 if(cState.now-cState.lastContact>=cState.lossDelay&&(!cState.loss||cState.loss.recoveredAt)){
  cState.loss={episode:++cState.lossEpisode,detectedAt:cState.now,recoveredAt:null,contactAt:null};cNotify(`device-${cState.lossEpisode}`,'device');cHistory('eventDisconnect');
 }
 if(cState.now>=cState.cutoff&&!cEntry()&&cLatest()?.type!=='taken'&&cLatest()?.type!=='help'){
  cEnsureCase('timeout');cNotify('timeout','timeout');
 }
}
function cAdvance(minutes){for(let n=0;n<minutes;n++){cState.now++;cProcess()}cRender(true)}
function cRecord(type){
 if(!['due','snoozed'].includes(cState.phase)||cState.now>=cState.cutoff||cState.link==='off')return;
 if(type==='snooze'&&cState.now+10>=cState.cutoff){cState.screen='snooze-boundary';cRender(true);return}
 cState.records.push({id:++cState.sequence,type,at:cState.now,status:'pending',receivedAt:null,groupCount:cState.count});
 if(type==='taken'){cState.phase='taken';cState.screen='home';cState.snoozeAt=null}
 if(type==='help'){cState.phase='help';cState.screen='home';cState.snoozeAt=null}
 if(type==='snooze'){cState.phase='snoozed';cState.screen='home';cState.snoozeAt=cState.now+10}
 cProcess();cRender(true);
}
function cContact(){
 if(cState.loss&&!cState.loss.recoveredAt)cState.loss.contactAt=cState.now;
 if(cState.now>=cState.planned){
  cEnsureCase('device');cState.case.stage='contacted';cState.case.contactAt=cState.now;cState.serverTakeover=true;cState.entryOpen=true;cState.entryError='';cHistory('eventContact');
 }
 cProcess();cRender();
}
function cSaveEntry(){
 const a=cT(),draft=cState.careDraft;
 if(!draft.outcome){cState.entryError=a.saveError;cRender();return}
 let actual=null;
 if(draft.outcome==='taken'&&!draft.unknownTime){
  if(!draft.actualTime){cState.entryError=a.timeError;cRender();return}
  const [h,m]=draft.actualTime.split(':').map(Number);actual=h*60+m;
  if(actual>cState.now){cState.entryError=a.futureError;cRender();return}
 }
 const entry={id:++cState.sequence,outcome:draft.outcome,actualAt:draft.outcome==='taken'?actual:null,enteredAt:cState.now,source:'caregiver',contactAt:cState.case.contactAt,note:draft.note};
 cState.careEntries.push(entry);cState.case.stage='recorded';cState.entryOpen=false;cState.entryError='';cState.serverTakeover=true;cState.conflict=false;cHistory('eventEntry',{outcome:entry.outcome});cProcess();cRender();
}
function cSpeak(){if(!cState.voice||!('speechSynthesis'in window))return;speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(cT().voiceText);u.lang=cState.lang==='zh'?'zh-CN':'en-US';u.rate=.85;speechSynthesis.speak(u)}
function cCamera(){cState.screen='camera';cState.candidate=null;cRender(true)}
function cSetCandidate(url){cState.candidate=url;cState.candidateAt=cState.now;cState.screen='photo-review';cRender(true)}
function cSavePhoto(){if(!cState.candidate)return;cState.photoQueue.push({id:++cState.sequence,url:cState.candidate,at:cState.candidateAt,receivedAt:null,reviewed:false});cState.candidate=null;cState.screen='photo-saved';cProcess();cRender(true)}
function cGrid(){const a=cT(),amounts=[1,1,2,1,1,1];return `<section class="c-photos ${cState.count===1?'c-one':''}" aria-label="${a.scope(cState.count)}" tabindex="0">${Array.from({length:cState.count},(_,i)=>`<figure class="c-box"><button type="button" data-c="zoom" data-box="${i}" aria-label="${a.zoom} · ${a.box(i+1)}"><img src="${cEscape(cState.photos[i])}" alt="${a.box(i+1)}" draggable="false"><span class="c-zoom-mark">${cIcon('zoom')}</span></button><figcaption>${a.amount(amounts[i])}</figcaption></figure>`).join('')}</section>`}
function cHome(){
 const a=cT();let title=a.home,sub='',middle=`<div class="c-pill">${a.next} ${cTime(cState.planned)}</div>`,symbol='clock',kind='',extra='';
 switch(cState.phase){
 case'due':title=a.dueHome;middle=`<div class="c-pill">${cTime(cState.planned)}</div>`;extra=cButton(a.open,'alarm','c-primary','clock');break;
 case'taken':title=a.recorded;middle='';symbol='check';kind='c-confirm-icon';break;
 case'snoozed':title=a.snoozed;middle=`<div class="c-pill">${cTime(cState.snoozeAt)} ${a.again}</div>`;break;
 case'handoff':title=a.handoff;sub=a.handoffSub;middle='';break;
 case'help':title=a.helpSaved;sub=a.helpSub;middle='';symbol='help';kind='c-help-icon';break;
 case'care-handled':title=cEntry()?a.careHandled:a.handoff;sub=a.handoffSub;middle='';symbol='check';break;
 }
 return `<div class="c-header"><span>${a.brand}</span><time>${cTime(cState.now)}</time></div><section class="c-home"><div class="c-state-icon ${kind}">${cIcon(symbol)}</div><h1 tabindex="-1">${title}</h1>${sub?`<p class="c-sub">${sub}</p>`:''}${middle}<div class="c-home-space"></div><div class="c-actions">${extra}${cButton(a.camera,'camera',extra?'':'c-primary','camera')}</div></section>`;
}
function cElder(){
 const a=cT();if(cState.link==='off')return `<div class="c-off">${a.powerOff}</div>`;
 if(cState.screen==='camera')return `<section class="c-camera"><button class="c-mini-back" data-c="home">${a.back}</button><h1 tabindex="-1">${a.photoTitle}</h1><div class="c-camera-box"><div>${cIcon('camera')}<p>${a.frame}</p></div></div>${cButton(a.shutter,'shutter','c-primary','camera')}</section>`;
 if(cState.screen==='photo-review')return `<section class="c-camera"><h1 tabindex="-1">${a.photoTitle}</h1><img class="c-capture-preview" src="${cEscape(cState.candidate)}" alt="${a.photoTitle}"><div class="c-home-space"></div><div class="c-actions">${cButton(a.usePhoto,'save-photo','c-primary','check')}${cButton(a.retake,'camera','','camera')}${cButton(a.back,'home')}</div></section>`;
 if(cState.screen==='photo-saved')return `<section class="c-home"><div class="c-state-icon c-confirm-icon">${cIcon('check')}</div><h1 tabindex="-1">${a.photoSaved}</h1><div class="c-home-space"></div>${cButton(a.done,'home','c-primary','check')}</section>`;
 if(cState.screen==='zoom'&&['due','snoozed'].includes(cState.phase))return `<button class="c-mini-back" data-c="zoom-back">${a.back}</button><h1 tabindex="-1">${a.box(cState.zoom+1)}</h1><img class="c-zoom-image" src="${cEscape(cState.photos[cState.zoom])}" alt="${a.box(cState.zoom+1)}">`;
 if(cState.screen==='snooze-boundary'&&['due','snoozed'].includes(cState.phase))return `<section class="c-home"><div class="c-state-icon">${cIcon('clock')}</div><h1 tabindex="-1">${a.lateSnooze}</h1><p class="c-sub">${a.lateSnoozeSub}</p><div class="c-home-space"></div><div class="c-actions">${cButton(a.taken,'taken','c-primary','check')}${cButton(a.help,'help','c-help','help')}${cButton(a.back,'alarm')}</div></section>`;
 if(cState.screen==='alarm'&&['due','snoozed'].includes(cState.phase)&&cState.now<cState.cutoff)return `<section class="c-alarm"><div class="c-alarm-heading"><h1 tabindex="-1">${a.alarm}</h1><small>${a.scope(cState.count)}</small></div><p class="c-alarm-time">${cTime(cState.planned)}</p>${cGrid()}<div class="c-actions">${cButton(a.taken,'taken','c-primary','check')}${cButton(a.later,'snooze','c-later','clock')}${cButton(a.help,'help','c-help','help')}</div></section>`;
 return cHome();
}
function cEntryForm(){
 const a=cT(),d=cState.careDraft;
 return `<form class="c-entry-form" id="c-entry-form"><fieldset class="c-result-options"><legend>${a.outcome}</legend>${[['taken',a.yes],['not-taken',a.no],['unknown',a.unsure]].map(([v,label])=>`<label class="c-result-option"><input type="radio" name="c-outcome" value="${v}" ${d.outcome===v?'checked':''}><span>${label}</span></label>`).join('')}</fieldset>${d.outcome==='taken'?`<label class="c-field" for="c-actual">${a.actualTime}</label><input type="time" id="c-actual" value="${cEscape(d.actualTime)}" ${d.unknownTime?'disabled':''}><label class="c-check-line"><input type="checkbox" id="c-unknown-time" ${d.unknownTime?'checked':''}>${a.unknownTime}</label>`:''}<label class="c-field" for="c-note">${a.note}</label><textarea id="c-note">${cEscape(d.note)}</textarea>${cState.entryError?`<p class="c-entry-error" role="alert">${cState.entryError}</p>`:''}<button type="submit" class="c-care-action">${a.save}</button></form>`;
}
function cCaregiver(){
 const a=cT(),entry=cEntry(),latest=cLatest(),task=cState.case,loss=cState.loss,lost=loss&&!loss.recoveredAt;
 let connection='';
 if(loss){connection=`<section class="c-care-card"><div class="c-care-summary ${lost?'c-attention':'c-ok'}"><strong>${lost?(loss.contactAt?a.deviceStillLost:a.deviceReason):a.deviceRestored}</strong><p>${a.deviceDescription}</p></div><dl class="c-metadata"><dt>${a.lastContact}</dt><dd>${cTime(cState.lastContact)}</dd><dt>${a.detected}</dt><dd>${cTime(loss.detectedAt)}</dd></dl>${lost&&!loss.contactAt?`<button class="c-care-action" data-c="contacted">${a.markContact}</button>`:''}${loss.contactAt&&cState.now>=cState.planned&&!task&&!entry?`<button class="c-care-action" data-c="entry-after-contact">${a.entryTitle}</button>`:''}${cState.now<cState.planned&&loss.contactAt?`<p>${a.beforeDue}</p>`:''}</section>`}
 let followup=`<div class="c-care-summary ${latest?.type==='taken'?'c-ok':''}"><strong>${latest?.type==='taken'?a.responded:a.noTask}</strong>${latest?.type==='taken'?`<p>${a.reportAt} ${cTime(latest.at)} · ${a.receivedAt} ${cTime(latest.receivedAt)}</p>`:''}</div>`;
 if(task){
  const stage=task.stage==='contacted'?a.needEntry:task.stage==='recorded'?(entry?.outcome==='unknown'?a.unknownRegistered:a.registered):task.stage==='responded'?a.responded:a.pending;
  const reason={help:a.helpReason,timeout:a.timeoutReason,device:a.deviceReason,conflict:a.conflict}[task.reason];
  followup=`<div class="c-case-stage ${['recorded','responded'].includes(task.stage)&&entry?.outcome!=='unknown'&&!cState.conflict?'c-case-closed':''}">${stage}</div><p><strong>${reason}</strong></p>${task.stage==='pending'?`<p>${a.contactNote}</p><button class="c-care-action" data-c="contacted">${a.markContact}</button>`:''}${task.stage==='contacted'?`<p>${a.contactNote}</p>`:''}`;
 }
 if(entry){
  const result={taken:a.yes,'not-taken':a.no,unknown:a.unsure}[entry.outcome];
  followup+=`<div class="c-care-summary ${entry.outcome==='taken'?'c-ok':'c-attention'}"><strong>${result}</strong><p>${{taken:a.takenNote,'not-taken':a.notTakenNote,unknown:a.unsureNote}[entry.outcome]}</p></div><dl class="c-metadata"><dt>${a.source}</dt><dd>${a.careSource}</dd>${entry.outcome==='taken'?`<dt>${a.actualTime}</dt><dd>${entry.actualAt===null?a.unknown:cTime(entry.actualAt)}</dd>`:''}<dt>${a.entryAt}</dt><dd>${cTime(entry.enteredAt)}</dd></dl>${entry.note?`<p>${cEscape(entry.note)}</p>`:''}<button class="c-care-action c-care-secondary" data-c="update-entry">${a.update}</button>`;
 }
 if(cState.conflict)followup+=`<div class="c-care-summary c-attention"><strong>${a.conflict}</strong></div>`;
 if(cState.entryOpen&&task?.contactAt!==null&&cState.now>=cState.planned)followup+=`<h3 style="margin-top:20px">${a.entryTitle}</h3>${cEntryForm()}`;
 if(cState.serverTakeover)followup+=`<p>${cState.deviceAck?a.deviceAckDone:a.deviceAckPending}</p>`;
 const receivedTitle=latest?{taken:a.elderSource,snooze:a.elderLater,help:a.elderHelp}[latest.type]:cState.now<cState.planned?a.planned:a.noResponse;
 const receipts=`<details><summary>${a.transmission}</summary><p><strong>${receivedTitle}</strong></p><dl class="c-metadata"><dt>${a.reportAt}</dt><dd>${cTime(latest?.at)}</dd><dt>${a.receivedAt}</dt><dd>${cTime(latest?.receivedAt)}</dd><dt>${a.lastContact}</dt><dd>${cTime(cState.lastContact)}</dd></dl>${cState.serverErrorAt!==null?`<p>${a.receiveError} · ${cTime(cState.serverErrorAt)}</p>`:latest?`<p>${a.accepted}</p>`:''}</details>`;
 const photoCard=`<section class="c-care-card"><h2>${a.photos} · ${cState.serverPhotos.filter(p=>!p.reviewed).length}</h2><p>${a.photoPending}</p>${cState.serverPhotos.map(p=>`<article><img class="c-captured-thumb" src="${cEscape(p.url)}" alt="${a.camera}"><p>${a.photoAt} ${cTime(p.at)} · ${a.photoReceived} ${cTime(p.receivedAt)}</p>${p.reviewed?`<p>${a.photoReviewDone}</p>`:`<button class="c-care-action c-care-secondary" data-c="review-photo" data-id="${p.id}">${a.photoReview}</button>`}</article>`).join('')}</section>`;
 return `<p class="c-overline">${a.careRole}</p>${connection}<section class="c-care-card"><h2>${a.todo} · ${cTime(cState.planned)}</h2><p>${a.localCutoff} ${cTime(cState.cutoff)}</p>${followup}${receipts}</section>${photoCard}<section class="c-care-card"><h2>${a.notifications} · ${cState.notifications.length} ${a.times}</h2><details><summary>${a.history}</summary>${cState.history.length?`<ol class="c-history">${[...cState.history].reverse().map(h=>`<li><time>${cTime(h.at)}</time>${a[h.key]}${h.actionAt!==undefined&&h.actionAt!==h.at?` · ${a.reportAt} ${cTime(h.actionAt)}`:''}${h.outcome?` · ${{taken:a.yes,'not-taken':a.no,unknown:a.unsure}[h.outcome]}`:''}</li>`).join('')}</ol>`:`<p>${a.noHistory}</p>`}</details><p class="c-prototype-note">${a.demo}</p></section>`;
}
function cControls(){const a=cT();return `<label>${a.time} · ${cTime(cState.now)}</label><div class="c-row"><button data-c="ring">${a.ring}</button>${[2,5,10,30].map(n=>`<button data-c="advance" data-minutes="${n}">${a['step'+n]}</button>`).join('')}<button data-c="reset">${a.reset}</button></div><label for="c-count">${a.count}</label><select id="c-count">${[1,3,6].map(n=>`<option value="${n}" ${cState.count===n?'selected':''}>${n}</option>`).join('')}</select><label for="c-link">${a.connection}</label><select id="c-link">${['online','offline','off'].map(v=>`<option value="${v}" ${cState.link===v?'selected':''}>${a[v]}</option>`).join('')}</select><label><input type="checkbox" id="c-reject" ${cState.reject?'checked':''}> ${a.reject}</label><div class="c-row"><button data-c="sample-photo">${a.samplePhoto}</button></div><label for="c-font">${a.font}</label><select id="c-font">${[100,150,200].map(n=>`<option value="${n}" ${cState.font===n?'selected':''}>${n}%</option>`).join('')}</select><label><input type="checkbox" id="c-voice" ${cState.voice?'checked':''}> ${a.voice}</label><small>${a.rules}</small><small>${a.photosFixtures}</small><p><a href="C_V2_NOTES.md" target="_blank">${a.research}</a></p><nav class="c-nav-history"><a href="?variant=A">${a.linkA}</a><a href="?variant=B">${a.linkB}</a></nav><details><summary>${a.state}</summary><pre>${cEscape(JSON.stringify({time:cTime(cState.now),cutoff:cTime(cState.cutoff),phase:cState.phase,screen:cState.screen,link:cState.link,localRecords:cState.records,serverRecords:cState.serverRecords,deviceLoss:cState.loss,followup:cState.case,caregiverEntries:cState.careEntries,deviceAcknowledged:cState.deviceAck,conflict:cState.conflict,notifications:cState.notifications,localPhotos:cState.photoQueue.map(({url,...p})=>p),receivedPhotos:cState.serverPhotos.map(({url,...p})=>p)},null,2))}</pre></details>`}
document.body.innerHTML=`<header class="c-reviewbar"><strong id="c-study"></strong><div class="c-lang" aria-label="Language"><button data-c-lang="zh">简体中文</button><button data-c-lang="en">English</button></div></header><div class="c-stage"><section class="c-phone"><div class="c-role" id="c-elder-role"></div><main class="c-elder" id="c-elder"></main></section><aside class="c-companion"><div id="c-caregiver"></div><details class="c-controls" id="c-controls"><summary id="c-controls-title"></summary><div id="c-controls-body"></div></details></aside></div><input type="file" id="c-capture" accept="image/*" capture="environment" hidden><div class="c-live" id="c-live" role="status" aria-live="polite"></div>`;
function cRender(focus=false,keepForm=false){
 const a=cT();document.documentElement.lang=cState.lang==='zh'?'zh-CN':'en';document.title=cState.lang==='zh'?'C · 长者提醒与照护者接手':'C · Elder reminders and caregiver handoff';document.documentElement.style.setProperty('--c-font',cState.font/100);document.body.classList.toggle('c-font-large',cState.font>100);document.querySelector('#c-study').textContent=a.study;document.querySelector('#c-elder-role').textContent=a.elder;document.querySelector('#c-controls-title').textContent=a.controls;document.querySelectorAll('[data-c-lang]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.cLang===cState.lang));document.querySelector('#c-elder').innerHTML=cElder();if(!keepForm)document.querySelector('#c-caregiver').innerHTML=cCaregiver();document.querySelector('#c-controls-body').innerHTML=cControls();if(focus){document.querySelector('#c-elder h1')?.focus({preventScroll:true});window.scrollTo({top:0,behavior:'instant'})}
}
function cReset(){if('speechSynthesis'in window)speechSynthesis.cancel();Object.assign(cState,{now:1075,phase:'scheduled',screen:'home',link:'online',reject:false,snoozeAt:null,records:[],serverRecords:[],lastContact:1075,loss:null,lossEpisode:0,case:null,careEntries:[],careDraft:{outcome:'',actualTime:'',unknownTime:false,note:''},entryOpen:false,entryError:'',serverTakeover:false,deviceAck:false,conflict:false,serverErrorAt:null,notifications:[],history:[],sequence:0,photoQueue:[],serverPhotos:[],candidate:null,candidateAt:null});cRender(true)}
document.addEventListener('click',event=>{
 const b=event.target.closest('button');if(!b||b.disabled)return;
 if(b.dataset.cLang){cState.lang=b.dataset.cLang;cRender();return}
 switch(b.dataset.c){
 case'ring':if(cState.phase==='scheduled')cAdvance(Math.max(0,cState.planned-cState.now));else if(['due','snoozed'].includes(cState.phase)&&cState.now<cState.cutoff){if(cState.phase==='snoozed')cAdvance(cState.snoozeAt-cState.now);else{cState.screen='alarm';cRender(true)}}break;
 case'advance':cAdvance(Number(b.dataset.minutes));break;
 case'reset':cReset();break;
 case'alarm':if(['due','snoozed'].includes(cState.phase)&&cState.now<cState.cutoff){cState.screen='alarm';cRender(true)}break;
 case'taken':cRecord('taken');break;case'snooze':cRecord('snooze');break;case'help':cRecord('help');break;
 case'camera':if(cState.link!=='off')cCamera();break;
 case'shutter':document.querySelector('#c-capture').click();break;
 case'sample-photo':if(cState.link!=='off')cSetCandidate('assets/box-a.jpg');break;
 case'save-photo':cSavePhoto();break;
 case'home':cState.screen='home';cProcess();cRender(true);break;
 case'zoom':cState.zoom=Number(b.dataset.box);cState.returnScreen=cState.screen;cState.screen='zoom';cRender(true);break;
 case'zoom-back':cState.screen=cState.returnScreen;cProcess();cRender(true);break;
 case'contacted':cContact();break;
 case'entry-after-contact':if(cState.loss?.contactAt&&cState.now>=cState.planned){cEnsureCase('device');cState.case.stage='contacted';cState.case.contactAt=cState.loss.contactAt;cState.entryOpen=true;cState.serverTakeover=true;cProcess();cRender()}break;
 case'update-entry':{const e=cEntry();cState.careDraft={outcome:e.outcome,actualTime:e.actualAt===null?'':cTime(e.actualAt),unknownTime:e.outcome==='taken'&&e.actualAt===null,note:e.note};cState.entryOpen=true;cState.entryError='';cRender();break}
 case'review-photo':{const p=cState.serverPhotos.find(x=>x.id===Number(b.dataset.id));if(p)p.reviewed=true;cRender();break}
 }
});
document.addEventListener('change',event=>{
 const el=event.target;
 if(el.id==='c-link'){cState.link=el.value;cProcess();cRender(true)}
 if(el.id==='c-count'){cState.count=Number(el.value);cReset()}
 if(el.id==='c-font'){cState.font=Number(el.value);cRender()}
 if(el.id==='c-reject'){cState.reject=el.checked;cProcess();cRender()}
 if(el.id==='c-voice')cState.voice=el.checked;
 if(el.name==='c-outcome'){cState.careDraft.outcome=el.value;cState.entryError='';cRender();document.querySelector('input[name="c-outcome"]:checked')?.focus({preventScroll:true})}
 if(el.id==='c-unknown-time'){cState.careDraft.unknownTime=el.checked;cRender();document.querySelector('#c-unknown-time')?.focus({preventScroll:true})}
 if(el.id==='c-capture'&&el.files[0]){cSetCandidate(URL.createObjectURL(el.files[0]));el.value=''}
});
document.addEventListener('input',event=>{if(event.target.id==='c-actual')cState.careDraft.actualTime=event.target.value;if(event.target.id==='c-note')cState.careDraft.note=event.target.value});
document.addEventListener('submit',event=>{if(event.target.id==='c-entry-form'){event.preventDefault();cSaveEntry()}});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&cState.screen==='zoom'){cState.screen=cState.returnScreen;cProcess();cRender(true)}});
cRender();
