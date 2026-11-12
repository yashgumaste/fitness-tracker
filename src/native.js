/* ════════════════════════════════════════════════════════════════════
   Native bridge for the Android app (Capacitor).
   Gives the app the same storage API it had as a Claude artifact
   (window.claude.use('db') / 'user'), plus AI with your own API key,
   water reminders, backup sharing, the back button and system bars.
   Everything falls back to plain browser behaviour when Capacitor is absent,
   so the page still runs in a desktop browser for testing.
════════════════════════════════════════════════════════════════════ */
(function(){
  const Cap=window.Capacitor;
  const isNative=!!(Cap&&Cap.isNativePlatform&&Cap.isNativePlatform());
  const plug=n=>{if(!isNative)return null;try{return (Cap.Plugins&&Cap.Plugins[n])||Cap.registerPlugin(n);}catch(_){return null;}};
  const FS=plug('Filesystem'),Prefs=plug('Preferences'),LN=plug('LocalNotifications'),Share=plug('Share'),
        AppP=plug('App'),Browser=plug('Browser'),StatusBar=plug('StatusBar');

  /* ── Storage: one JSON file per document in the app's private data folder ── */
  const fileOf=p=>'store/'+String(p).replace(/[^a-zA-Z0-9._-]/g,'_')+'.json';
  const db={doc(path){return{
    async get(){
      if(!FS){const s=localStorage.getItem('fs:'+path);return{exists:s!=null,data:()=>s!=null?JSON.parse(s):null};}
      try{const r=await FS.readFile({path:fileOf(path),directory:'DATA',encoding:'utf8'});
        return{exists:true,data:()=>JSON.parse(r.data)};}
      catch(e){
        // A missing file is normal on first launch; anything else is a real read failure
        if(/not exist|no such|not found|ENOENT/i.test(String(e&&e.message||e)))return{exists:false,data:()=>null};
        throw e;}
    },
    async set(obj){
      const data=JSON.stringify(obj);
      if(!FS){localStorage.setItem('fs:'+path,data);return;}
      await FS.writeFile({path:fileOf(path),directory:'DATA',encoding:'utf8',data,recursive:true});
    }
  };}};
  window.claude={use:async name=>{
    if(name==='db')return db;
    if(name==='user')return{id:async()=>'me'};
    throw new Error('Not available in the app: '+name);
  }};

  /* ── Small key/value settings (API key etc.) ── */
  const kv={
    async get(k){if(Prefs){const r=await Prefs.get({key:k});return r.value;}try{return localStorage.getItem('kv:'+k);}catch(_){return null;}},
    async set(k,v){if(Prefs){if(v==null)await Prefs.remove({key:k});else await Prefs.set({key:k,value:v});return;}
      try{v==null?localStorage.removeItem('kv:'+k):localStorage.setItem('kv:'+k,v);}catch(_){}}
  };

  /* ── AI: Claude API with your own key ── */
  const MODELS={
    fast:{id:'claude-haiku-5-5',name:'Haiku 5.5',in:0.10,out:0.50,extra:{thinking:{type:'disabled'},output_config:{effort:'low'}}},
    smart:{id:'claude-sonnet-5-5',name:'Sonnet 5.5',in:2,out:10,extra:{thinking:{type:'between_tools'},output_config:{effort:'medium'}}},
    best:{id:'claude-opus-5-5',name:'Opus 5.5',in:4,out:20,extra:{output_config:{effort:'low'}}}
  };
  let keyCache;
  async function apiKey(){if(keyCache===undefined)keyCache=await kv.get('anthropic_key');return keyCache;}
  function recordUsage(model,u){
    try{const m=new Date().toISOString().slice(0,7);const all=JSON.parse(localStorage.getItem('lm-ai-usage')||'{}');
      const mm=all[m]=all[m]||{};const r=mm[model]=mm[model]||{in:0,out:0,n:0};
      r.in+=u.input_tokens||0;r.out+=u.output_tokens||0;r.n++;
      localStorage.setItem('lm-ai-usage',JSON.stringify(all));}catch(_){}
  }
  // Which AI to use: 'gemini' (free, default) or 'claude' (your own key)
  const FREE_GEMINI_KEY='__GEMINI_FREE_KEY__';
  const hasFreeKey=()=>FREE_GEMINI_KEY&&!FREE_GEMINI_KEY.startsWith('__');
  function provider(){try{return localStorage.getItem('lm-ai-provider')||'gemini';}catch(_){return 'gemini';}}
  let gemKeyCache;
  async function geminiKey(){if(gemKeyCache===undefined)gemKeyCache=await kv.get('gemini_key');return gemKeyCache||(hasFreeKey()?FREE_GEMINI_KEY:null);}
  const GEMINI={fast:'gemini-flash-lite-latest',smart:'gemini-flash-latest'};

  async function askGemini(prompt,maxTokens,kind,json){
    const key=await geminiKey();
    if(!key)throw new Error('The free AI is not set up in this build — add a Gemini key or switch to Claude in Settings → AI');
    const model=GEMINI[kind==='fast'?'fast':'smart'];
    const send=thinking=>fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,{method:'POST',
      headers:{'content-type':'application/json','x-goog-api-key':key},
      body:JSON.stringify({contents:[{role:'user',parts:[{text:prompt}]}],
        // room for the model's own reasoning plus the answer
        generationConfig:{maxOutputTokens:maxTokens*3+1024,...(json?{responseMimeType:'application/json'}:{}),...(thinking?{thinkingConfig:thinking}:{})}})});
    let r;
    try{r=await send({thinkingLevel:'low'});}catch(e){throw new Error('No internet connection — try again');}
    if(r.status===400)r=await send({thinkingBudget:0});
    if(r.status===400)r=await send(null);
    if(!r.ok){
      const t=await r.text();let msg=t.slice(0,160);try{msg=JSON.parse(t).error.message;}catch(_){}
      if(r.status===429)throw new Error((gemKeyCache?'Your Gemini key':'The free AI')+' has used up its allowance for now — try again later, or add your own key in Settings → AI');
      if(r.status===400&&/API key/i.test(msg)||r.status===403)throw new Error('Gemini key not accepted — check Settings → AI');
      throw new Error('AI error '+r.status+': '+msg);
    }
    const d=await r.json();
    const u=d.usageMetadata||{};recordUsage(model,{input_tokens:u.promptTokenCount||0,output_tokens:(u.candidatesTokenCount||0)+(u.thoughtsTokenCount||0)});
    const parts=(((d.candidates||[])[0]||{}).content||{}).parts||[];
    const text=parts.filter(x=>!x.thought).map(x=>x.text||'').join('').trim();
    if(!text)throw new Error('The free AI returned nothing — try again');
    return text;
  }

  async function ask(prompt,maxTokens,kind,json){
    if(provider()==='gemini')return askGemini(prompt,maxTokens,kind,json);
    const key=await apiKey();
    if(!key)throw new Error('Add your Claude API key in Settings → AI, or switch to the free AI');
    const tier=(JSON.parse(localStorage.getItem('lm-ai-models')||'{}'))[kind]||(kind==='fast'?'fast':'smart');
    const M=MODELS[tier]||MODELS.smart;
    const send=async withExtra=>{
      const body={model:M.id,max_tokens:maxTokens,messages:[{role:'user',content:prompt}],
        ...(json?{system:'You are a nutrition data API. Reply with only the JSON the user asks for: no words, notes or markdown before or after it.'}:{}),...(withExtra?M.extra:{})};
      return fetch('https://api.anthropic.com/v1/messages',{method:'POST',
        headers:{'content-type':'application/json','x-api-key':key,'anthropic-version':'2023-06-01','anthropic-dangerous-direct-browser-access':'true'},
        body:JSON.stringify(body)});
    };
    let r;
    try{r=await send(true);}catch(e){throw new Error('No internet connection — try again');}
    // If a model rejects the thinking/effort options, retry once without them
    if(r.status===400){const t=await r.clone().text();if(/thinking|effort|output_config/i.test(t))r=await send(false);}
    if(!r.ok){
      const t=await r.text();let msg=t.slice(0,160);try{msg=JSON.parse(t).error.message;}catch(_){}
      if(r.status===401)throw new Error('API key not accepted — check it in Settings → AI');
      if(r.status===402||/credit balance/i.test(msg))throw new Error('Out of API credits — top up at platform.claude.com');
      if(r.status===429)throw new Error('Too many requests — wait a moment and try again');
      throw new Error('AI error '+r.status+': '+msg);
    }
    const d=await r.json();
    if(d.usage)recordUsage(M.id,d.usage);
    return(d.content||[]).map(c=>c.text||'').join('').trim();
  }
  function usage(){
    const m=new Date().toISOString().slice(0,7);let all={};try{all=JSON.parse(localStorage.getItem('lm-ai-usage')||'{}');}catch(_){}
    const mm=all[m]||{};let cost=0,n=0;const rows=[];
    for(const [id,r] of Object.entries(mm)){const M=Object.values(MODELS).find(x=>x.id===id)||(/gemini/.test(id)?{in:0,out:0,name:'Gemini (free)'}:{in:2,out:10,name:id});
      const c=r.in/1e6*M.in+r.out/1e6*M.out;cost+=c;n+=r.n;rows.push({name:M.name,n:r.n,cost:c});}
    return{cost,n,rows};
  }

  /* ── Water reminders: one-off notifications for the next 3 days, re-planned whenever you drink ── */
  const WATER_BASE=7000,WATER_MAX=60;
  const LINES=['Time for a glass of water 💧','Sip break — log it with one tap','Your body will thank you — have some water','Stay topped up: a glass now?'];
  let lnReady=false;
  async function lnSetup(){
    if(!LN||lnReady)return!!LN;
    try{
      await LN.createChannel({id:'water',name:'Water reminders',description:'Reminders to drink water',importance:4,visibility:1,vibration:true});
      await LN.registerActionTypes({types:[{id:'WATER',actions:[{id:'add250',title:'Log 250 ml'},{id:'add500',title:'Log 500 ml'}]}]});
      LN.addListener('localNotificationActionPerformed',ev=>{
        const a=ev&&ev.actionId,ml=a==='add250'?250:a==='add500'?500:0;
        if(ml){if(window.__lmWater)window.__lmWater(ml);else(window.__lmWaterQueue=window.__lmWaterQueue||[]).push(ml);}
      });
      lnReady=true;
    }catch(e){console.warn('notifications setup',e);}
    return lnReady;
  }
  async function planWater(cfg,lastDrinkTs){
    if(!LN)return{ok:false,reason:'browser'};
    await lnSetup();
    const pend=await LN.getPending().catch(()=>({notifications:[]}));
    const mine=(pend.notifications||[]).filter(n=>n.id>=WATER_BASE&&n.id<WATER_BASE+1000).map(n=>({id:n.id}));
    if(mine.length)await LN.cancel({notifications:mine}).catch(()=>{});
    if(!cfg||!cfg.on)return{ok:true,count:0};
    const perm=await LN.checkPermissions();
    if(perm.display!=='granted'){const p=await LN.requestPermissions();if(p.display!=='granted')return{ok:false,reason:'denied'};}
    const [sh,sm]=cfg.start.split(':').map(Number),[eh,em]=cfg.end.split(':').map(Number),every=Math.max(30,+cfg.every||90);
    const now=Date.now(),gap=every*60000*0.75,out=[];
    for(let d=0;d<3&&out.length<WATER_MAX;d++){
      const base=new Date();base.setHours(0,0,0,0);base.setDate(base.getDate()+d);
      for(let t=sh*60+sm;t<=eh*60+em&&out.length<WATER_MAX;t+=every){
        const at=base.getTime()+t*60000;
        if(at<now+60000)continue;
        if(lastDrinkTs&&at-lastDrinkTs<gap)continue;  // you just drank — skip this one
        out.push(at);
      }
    }
    await LN.schedule({notifications:out.map((at,i)=>({id:WATER_BASE+i,title:'💧 Drink water',body:LINES[i%LINES.length],
      channelId:'water',actionTypeId:'WATER',smallIcon:'ic_stat_water',iconColor:'#4FC3F7',schedule:{at:new Date(at),allowWhileIdle:true}}))});
    return{ok:true,count:out.length,next:out[0]||null};
  }
  async function testNotification(){
    if(!LN)return false;await lnSetup();
    const p=await LN.requestPermissions();if(p.display!=='granted')return false;
    await LN.schedule({notifications:[{id:WATER_BASE+999,title:'💧 Drink water',body:'This is how your reminders will look',channelId:'water',
      actionTypeId:'WATER',smallIcon:'ic_stat_water',iconColor:'#4FC3F7',schedule:{at:new Date(Date.now()+3000),allowWhileIdle:true}}]});
    return true;
  }

  /* ── Backup: save as a file and open the share sheet (pick Drive) ── */
  async function shareBackup(json){
    const name='fitness-backup-'+new Date().toISOString().slice(0,10)+'.json';
    if(!FS||!Share){
      const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([json],{type:'application/json'}));a.download=name;a.click();return true;}
    const w=await FS.writeFile({path:name,data:json,directory:'CACHE',encoding:'utf8'});
    await Share.share({title:'Fitness Tracker backup',text:'Fitness Tracker backup '+name.slice(15,25),files:[w.uri],dialogTitle:'Save backup to…'});
    return true;
  }

  /* ── Open a web page (updates, API console) ── */
  function openUrl(url){if(Browser)Browser.open({url});else window.open(url,'_blank');}

  /* ── Updates: ask GitHub for the latest release (public repo, no login needed) ── */
  const REPO='yashgumaste/fitness-tracker';
  const newerThan=(a,b)=>{const x=String(a).split('.').map(Number),y=String(b).split('.').map(Number);
    for(let i=0;i<Math.max(x.length,y.length);i++){const d=(x[i]||0)-(y[i]||0);if(d)return d>0;}return false;};
  async function checkUpdate(force){
    const cur='__APP_VERSION__';if(!/^\d/.test(cur))return null;
    try{
      if(!force){const c=JSON.parse(localStorage.getItem('lm-update')||'null');if(c&&Date.now()-c.at<6*3600e3)return newerThan(c.latest,cur)?c:null;}
      const r=await fetch(`https://api.github.com/repos/${REPO}/releases/latest`,{headers:{Accept:'application/vnd.github+json'}});
      if(!r.ok)return null;const j=await r.json();const latest=String(j.tag_name||'').replace(/^v/,'');if(!latest)return null;
      const apk=(j.assets||[]).find(a=>/\.apk$/i.test(a.name));
      const newer=newerThan(latest,cur);
      const info={at:Date.now(),latest,url:apk?apk.browser_download_url:j.html_url,page:j.html_url,newer,items:newer?await notesFor(latest,j.body):[]};
      try{localStorage.setItem('lm-update',JSON.stringify(info));}catch(_){}
      return force||info.newer?info:null;
    }catch(_){return null;}
  }

  // Download with Android's downloader and open the installer (falls back to the browser)
  const AppUpdate=plug('AppUpdate');
  if(AppUpdate)AppUpdate.addListener('update',e=>{const t=window.__lmToast;if(!t)return;
    if(e.status==='installing')t('Tap Update on the next screen to install');
    else if(e.status==='failed')t('Download failed — try again, or get it from the Releases page');});
  async function downloadUpdate(u){
    if(AppUpdate){try{await AppUpdate.download({url:u.url,name:'FitnessTracker-'+u.latest+'.apk'});return 'native';}catch(e){console.warn('update',e);}}
    openUrl(u.page||u.url);return 'browser';
  }
  async function updateStatus(){if(!AppUpdate)return{state:'idle'};try{return await AppUpdate.status();}catch(_){return{state:'failed'};}}
  async function installUpdate(){if(AppUpdate)await AppUpdate.install();}

  /* What's new for a version: from the changelog on GitHub, else the release notes' bullet lines */
  const RAW='https://raw.githubusercontent.com/'+REPO+'/main/';
  async function notesFor(version,body){
    try{const r=await fetch(RAW+'changelog.json',{cache:'no-store'});if(r.ok){const c=await r.json();
      const rel=(c.releases||[]).find(x=>x.version===version);
      if(rel)return rel.items.map(i=>({text:i.text,img:i.img?RAW+i.img:null}));}}catch(_){}
    return String(body||'').split('\n').filter(l=>/^\s*- /.test(l)).map(l=>({text:l.replace(/^\s*- /,'').trim()}));
  }

  /* ── Back button: close a sheet / go home / leave ── */
  if(AppP){
    let lastBack=0;
    AppP.addListener('backButton',()=>{
      const handled=window.__lmBack?window.__lmBack():false;
      if(handled)return;
      // on Home: first press warns, a second press within 2 s leaves
      const now=Date.now();
      if(now-lastBack<2000){AppP.exitApp();return;}
      lastBack=now;
      window.__lmToast&&window.__lmToast('Press back again to exit');
    });
    AppP.addListener('resume',()=>{window.dispatchEvent(new Event('lm-resume'));});
  }

  /* ── Launcher icon follows the accent colour. Switched when you leave the app,
        because some launchers restart an app whose icon changes while it is open. ── */
  const AppIcon=plug('AppIcon');let wantIcon=null;
  async function applyIcon(){
    if(!AppIcon||!wantIcon)return;
    try{const cur=await AppIcon.get();if(cur.name!==wantIcon)await AppIcon.set({name:wantIcon});}catch(e){console.warn('icon',e);}
  }
  if(AppP)AppP.addListener('pause',applyIcon);
  function setIcon(name){wantIcon=name;}

  /* ── System bars follow the app theme ── */
  async function bars(light){
    if(!StatusBar)return;
    try{await StatusBar.setStyle({style:light?'LIGHT':'DARK'});}catch(_){}
    try{await StatusBar.setBackgroundColor({color:light?'#F6FAFA':'#000000'});}catch(_){}
  }

  /* ── Health Connect ── */
  const HCP=plug('HealthConnect');
  const hc={
    async available(){if(!HCP)return{available:false};try{return await HCP.available();}catch(_){return{available:false};}},
    async granted(){if(!HCP)return{read:[],write:[]};try{return await HCP.granted();}catch(_){return{read:[],write:[]};}},
    async authorize(read,write){if(!HCP)return{read:[],write:[]};return HCP.authorize({read,write});},
    async readSteps(days){if(!HCP)return{days:[]};return HCP.readSteps({days});},
    async upsert(records){if(!HCP||!records.length)return{written:0};return HCP.upsert({records});},
    async remove(type,ids){if(!HCP||!ids.length)return;return HCP.remove({type,ids});}
  };

  window.LMNative={hc,provider,setProvider:p=>{try{localStorage.setItem('lm-ai-provider',p);}catch(_){}},hasFreeKey,
    setGeminiKey:async k=>{gemKeyCache=k||null;await kv.set('gemini_key',k||null);},hasGeminiKey:async()=>!!(await kv.get('gemini_key')),
isNative,kv,ask,usage,MODELS,setKey:async k=>{keyCache=k||null;await kv.set('anthropic_key',k||null);},hasKey:async()=>!!(await apiKey()),
    planWater,testNotification,shareBackup,openUrl,bars,setIcon,checkUpdate,downloadUpdate,updateStatus,installUpdate,rawBase:RAW,version:'__APP_VERSION__',
    releasesUrl:'https://github.com/'+REPO+'/releases/latest'};
})();
