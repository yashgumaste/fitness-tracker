// Nightly smoke test: opens the built app (www/) in a phone-sized headless Chrome with sample data,
// goes through every page and every "log" panel, and fails if anything throws or goes missing.
// The AI is faked, so no API key or credits are used.
//   node tests/smoke.cjs            (CI)
//   PW_PATH=/path/to/playwright CHROME=/path/to/chrome node tests/smoke.cjs   (local)
const path=require('path'),fs=require('fs');
const {chromium}=require(process.env.PW_PATH||'playwright');
const shots=path.join(__dirname,'..','test-results');fs.mkdirSync(shots,{recursive:true});

(async()=>{
  const b=await chromium.launch(process.env.CHROME?{executablePath:process.env.CHROME}:{});
  const ctx=await b.newContext({viewport:{width:393,height:860},deviceScaleFactor:1,timezoneId:'Asia/Kolkata',isMobile:true,hasTouch:true});
  const p=await ctx.newPage();
  const errors=[],steps=[];
  p.on('pageerror',e=>errors.push('page error: '+e.message));
  p.on('console',m=>{if(m.type()==='error'&&!/favicon|ERR_FILE_NOT_FOUND/.test(m.text()))errors.push('console: '+m.text());});
  // What's new pictures come from GitHub; serve a blank image so the test never depends on the network
  const blank=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=','base64');
  await p.route(/tile\.openstreetmap\.org/,r=>r.fulfill({status:200,contentType:'image/png',body:blank}));
  await p.route(/raw\.githubusercontent\.com/,r=>r.fulfill({status:200,contentType:'image/png',body:blank}));
  await p.clock.setFixedTime(new Date('2026-11-09T20:30:00+05:30'));
  await p.addInitScript(()=>{
    const at=(d,h)=>new Date(`${d}T${h}:00+05:30`).toISOString();
    const it=(n,c,pr,cb,f)=>[{name:n,calories:c,protein:pr,carbs:cb,fat:f}];
    const diet=[];let id=1;
    for(let i=6;i>=0;i--){const d=new Date('2026-11-09T12:00:00+05:30');d.setDate(d.getDate()-i);const k=d.toLocaleDateString('en-CA');
      diet.push({id:id++,food:'Poha with peanuts',mealType:'Breakfast',timestamp:at(k,'09:10'),emoji:'🍲',items:it('poha',380,8,60,12)});
      diet.push({id:id++,food:'2 chapati, dal, matki usal',mealType:'Lunch',timestamp:at(k,'13:40'),emoji:'🍛',items:it('lunch',620,26,80,18)});}
    localStorage.setItem('lm-diet-v1',JSON.stringify(diet));
    const walks={};for(let i=0;i<7;i++){const d=new Date('2026-11-09T12:00:00+05:30');d.setDate(d.getDate()-i);const k=d.toLocaleDateString('en-CA');
      walks[k]={morning:{from:'06:15',to:'06:45',steps:'3400',km:'2.1'},dayTotal:'9000'};}
    localStorage.setItem('lm-walk-v1',JSON.stringify(walks));
    // phone steps in 10-minute slices for "today": a brisk morning walk, everyday steps, an evening stroll
    const b=Array(144).fill(0);for(let i=48;i<126;i++)b[i]=(i*37)%9===0?160:40;
    [[37,700],[38,1120],[39,1100],[40,980],[41,500]].forEach(([i,v])=>b[i]=v);
    [[110,520],[111,640],[112,600]].forEach(([i,v])=>b[i]=v);
    localStorage.setItem('lm-steps-v1',JSON.stringify({'2026-11-09':{b,at:Date.now()}}));
    localStorage.setItem('lm-weight-v1',JSON.stringify({'2026-11-06':79.1,'2026-11-09':78.8}));
    localStorage.setItem('kv:anthropic_key','sk-ant-test');
    // fake AI: answers in Claude's or Gemini's format depending on where the app sends it
    let gem=false;
    const reply=t=>({ok:true,status:200,clone(){return this;},text:async()=>'',json:async()=>gem
      ?{candidates:[{content:{parts:[{text:t}]}}],usageMetadata:{promptTokenCount:100,candidatesTokenCount:50}}
      :{content:[{type:'text',text:t}],usage:{input_tokens:100,output_tokens:50}}});
    window.__aiCalls=[];
    window.fetch=async(u,o)=>{
      while(window.__slowAI)await new Promise(r=>setTimeout(r,50));   // lets a test see the loading state
      if(/api\.github\.com/.test(String(u)))return{ok:true,status:200,json:async()=>({tag_name:'v99.1',html_url:'https://github.com/x/releases/v99.1',body:'**What\'s new**\n- Faster logging\n- Brighter charts',assets:[{name:'FitnessTracker-99.1.apk',browser_download_url:'https://github.com/x/FitnessTracker-99.1.apk'}]})};
      const body=o&&o.body?String(o.body):'';gem=/generativelanguage/.test(String(u));window.__aiCalls.push(String(u));(window.__aiBodies=window.__aiBodies||[]).push(body);
      // Google's "high demand" answer, for one model
      if(window.__busyModel&&String(u).includes('/models/'+window.__busyModel+':'))return{ok:false,status:503,
        text:async()=>JSON.stringify({error:{message:'This model is currently experiencing high demand.'}}),json:async()=>({}),clone(){return this;}};
      if(/Split each meal/.test(body))return reply('[]');
      if(/reviewing one whole day/.test(body))return reply(JSON.stringify({rating:'✅ Solid',summary:'A steady day.',food_insight:'Protein was low at lunch.',walk_insight:null,workout_insight:null,body_insight:'Slept well.',top_win:'Good sleep.',focus_next:'Add dal at dinner.',recovery_tip:'Drink water early.',tomorrow:{Breakfast:'Protein first: 2 boiled eggs with 1 chapati.',Lunch:window.__lunchTip||null,Snack:null,Dinner:'Dinner by 8 PM, half the rice.',walk_morning:'Aim for 11:30 min/km at 105 steps a minute.',walk_second:'A 20-minute brisk walk after tea.',keep:[{at:'Lunch',text:'Yesterday’s dal-and-sabji lunch was spot on. Same again?'}],water:'500 ml before 10 AM this time.',move:'Yesterday’s walk was great. Stand up and keep it rolling.'}}));
      // the free AI sometimes answers in words: first reply prose, the retry wraps JSON in a sentence
      if(/cabbage rice/.test(body)){window.__jsonMode=/responseMimeType|nutrition data API/.test(body);
        return reply(/not valid JSON/.test(body)?'Sure! {"emoji":"🍚","items":[{"name":"cabbage rice","calories":420,"protein":9,"carbs":70,"fat":11}]} Hope that helps.':'Cabbage is on the list, so the gas risk is high.');}
      if(/JSON/.test(body))return reply(JSON.stringify({emoji:'🥚',items:[{name:'boiled egg',calories:140,protein:12,carbs:1,fat:10}]}));
      return reply('**Coach** looks good.');};
  });
  await p.goto('file://'+path.join(__dirname,'..','www','index.html'));
  await p.waitForTimeout(1500);

  const W=ms=>p.waitForTimeout(ms||300);
  const has=t=>p.evaluate(t=>document.body.innerText.toLowerCase().includes(t.toLowerCase()),t);
  const click=async(sel,txt)=>{const ok=await p.evaluate(([s,t])=>{const e=[...document.querySelectorAll(s)].find(e=>e.textContent.includes(t));if(e){e.click();return true;}return false;},[sel,txt]);
    if(!ok)throw new Error(`could not find "${txt}" (${sel})`);await W();};
  const tab=async i=>{await p.evaluate(i=>document.querySelectorAll('.dock-tab')[i].click(),i);await W(400);};
  const close=async()=>{await p.evaluate(()=>document.querySelector('.x-sheet [aria-label=Close]')?.click());await W();};
  const step=async(name,fn)=>{try{await close();await fn();steps.push('✓ '+name);}catch(e){steps.push('✗ '+name+' — '+e.message);await p.screenshot({path:path.join(shots,name.replace(/\W+/g,'_')+'.png')}).catch(()=>{});}};
  const expect=async t=>{if(!(await has(t)))throw new Error(`"${t}" not on screen`);};

  await step('First-time setup',async()=>{
    await expect('Hey there');await click('.x-btn','Let’s go');
    await p.fill('input[placeholder="Your name"]','Tester');await click('.x-card','A woman');await click('.x-btn','Next');
    await expect('Height and weight');await p.fill('input[placeholder="170"]','165');await p.fill('input[placeholder="75"]','62');await click('.x-btn','Next');
    await expect('Body fat');await p.fill('input[placeholder="36"]','33');await p.fill('input[placeholder="90"]','78');await p.fill('input[placeholder="100"]','98');
    await expect('% body fat');await click('.x-btn','Next');
    await click('.x-card','Lose fat');await click('.x-btn','Next');
    await expect('daily targets');await click('.x-card','Suggested for you');await click('.x-btn','Next');
    await expect('Pick your AI');await click('.x-btn','Next');
    if(await has('Hook up your phone'))await click('.x-btn','Next');
    await expect('all set, Tester');await click('.x-btn','Start tracking');await W(500);
    await expect('Tester');});
  await step('Home loads',async()=>{await expect('Fuel');await expect('Timeline');});
  await step('Food today',async()=>{await tab(1);await expect('Breakfast');await expect('Water');});
  await step('Food history',async()=>{await click('.x-seg button','History');await expect('kcal');
    const meals=await p.evaluate(()=>[...document.querySelectorAll('section[aria-label]')].map(x=>x.getAttribute('aria-label')));
    if(!meals.some(m=>['Breakfast','Lunch','Snack','Dinner'].includes(m)))throw new Error('history days should be split by meal; got '+meals.join(', '));
    // log for a past day: the app's own date picker, not the browser's
    await click('.x-btn','Log food for a past day');await expect('8 Nov');
    if(await p.$('.x-sheet input[type=date]'))throw new Error('the browser date field is back');
    await click('.x-sheet button','Change');await expect('November 2026');
    await click('.x-sheet button','2 days ago');await expect('7 Nov');await close();
    await click('.x-seg button','Today');});
  await step('Fitness day',async()=>{await tab(2);await expect('Walks');await expect('Session notes');});
  for(const v of ['Day','Week','Month','Year'])
    await step('Fitness history '+v,async()=>{await click('.x-seg button','History');await click('.x-seg button',v);await expect('Workouts');});
  await step('Progress',async()=>{await tab(3);await expect('Trends');await expect('Body');});
  await step('Settings',async()=>{await tab(4);for(const t of ['Profile','Body','Goals','AI','Reminders','Health Connect','Look & feel','Data & backup','About & updates']){
      await click('.x-item',t);await W();if(!(await p.$('[aria-label="Back to settings"]')))throw new Error(t+' page did not open');
      await p.click('[aria-label="Back to settings"]');await W();}
    await click('.x-item','Goals');await expect('Daily goals');await expect('Goal weight');
    if(await p.$('#g-calMin'))throw new Error('Goals should show values, not fields, until Edit');
    await click('.x-btn','Edit goals');await expect('Editing goals');await p.fill('#g-calMin','1234');await p.fill('#g-target','72');
    await click('.x-btn','Save');await W(400);await expect('1,234');if(await p.$('#g-calMin'))throw new Error('Still editing after Save');
    await p.click('[aria-label="Back to settings"]');});
  await step('Steps page',async()=>{await tab(0);await p.click('[aria-label="Steps and walks"]');await W(500);
    await expect('By the hour');await expect('Brisk morning walk');await expect('Evening stroll');await expect('Everyday movement');await expect('kcal burned by moving');
    for(const v of ['Week','Month','Year']){await click('.x-seg button',v);await expect('Average a day');}
    // every bar carries its count; tapping a month in Year opens that month, a day in Month opens that day
    await click('.x-seg button','Year');const nums=await p.$$eval('svg[aria-label="Steps per day"] text',t=>t.map(x=>x.textContent).filter(x=>/^\d+(\.\d)?k?$/.test(x)).length);
    if(nums<1)throw new Error('no counts on the year bars');
    await p.evaluate(()=>{const g=[...document.querySelectorAll('svg[aria-label="Steps per day"] > g')][10];g.dispatchEvent(new MouseEvent('click',{bubbles:true}));});await W(500);
    if(!(await has('November 2026')))throw new Error('tapping a month did not open it');
    await p.evaluate(()=>{const g=[...document.querySelectorAll('svg[aria-label="Steps per day"] > g')][8];g.dispatchEvent(new MouseEvent('click',{bubbles:true}));});await W(500);
    await expect('By the hour');
    await click('.x-seg button','Day');await p.click('[aria-label="Earlier"]');await W();await expect('Back to today');
    await p.click('[aria-label="Back"]');await W();await expect('Timeline');});
  await step('Walk found from steps opens its page',async()=>{await tab(0);await p.click('[aria-label="Steps and walks"]');await W(400);
    await click('.x-card','Brisk morning walk');await W(400);await expect('estimated from your steps');await expect('Cadence');
    await p.click('[aria-label="Back"]');await W();await p.click('[aria-label="Back"]');await W();});
  await step('Record a walk (start, pause, finish)',async()=>{
    // a pretend GPS recorder: 25 minutes around a park
    await p.evaluate(()=>{window.LMNative.walk={available:()=>true,_st:'idle',_t0:0,_pts:[],
      async start(){this._st='recording';this._t0=Date.now()-25*60000;const c=[18.5204,73.8567];
        this._pts=Array.from({length:120},(_,i)=>{const a=i/120*2*Math.PI;return[this._t0+i*12500,c[0]+0.004*Math.sin(a),c[1]+0.006*Math.cos(a)+i*0.00003,560+10*Math.sin(a*2),5,null,i*27];});return this.status(0);},
      async status(from){if(this._noGps)return{state:this._st,start:this._t0,movingMs:5*60000,distanceM:0,steps:600,spm:120,gps:'searching',accuracy:0,count:0,sats:[3,11],from:from||0,points:[]};
        return{state:this._st,start:this._t0,movingMs:25*60000,distanceM:2600,steps:3300,spm:this._spm,stepSrc:this._src||'counter',stepIssue:this._issue||null,gps:'ok',accuracy:5,count:this._pts.length,from:from||0,points:this._pts.slice(from||0)};},
        allowSteps:async function(){this._issue=null;window.__allowSteps=true;return{granted:true};},
      async pause(){this._st='paused';return this.status(1e9);},async resume(){this._st='recording';return this.status(1e9);},
      async stop(){const s=await this.status(0);s.end=this._t0+26*60000;this._st='idle';return s;},async discard(){this._st='idle';}};});
    await tab(0);await p.click('[aria-label="Steps and walks"]');await W(400);
    // 3 · 2 · 1 · Go first, with Cancel for a mis-tap
    await click('.x-btn','Start a walk');await W(500);await expect('Walk starts in');await click('button','Cancel');await W(400);
    if(await has('Started'))throw new Error('Cancel should stop the recording from starting');await W(3500);if(await has('Started'))throw new Error('started after Cancel');
    await p.evaluate(()=>{window.LMNative.walk._noGps=true;});
    await click('.x-btn','Start a walk');await W(1200);if(await has('Started'))throw new Error('recording started before the countdown ended');await W(3800);
    await expect('Started');
    // no GPS yet (indoors): distance, speed, pace and stride from steps, marked ≈, and the satellites it can see
    await expect('3/11 satellites');await expect('from steps');
    if(!(await p.evaluate(()=>/≈\s*0\.\d\d/.test(document.body.innerText))))throw new Error('distance from steps should show as ≈0.xx km');
    await p.evaluate(()=>{window.LMNative.walk._noGps=false;});await W(2500);await expect('Now ');await expect('steps/min');
    if(await p.$('nav.dock-pill'))throw new Error('the floating island should be hidden while recording');
    await click('.x-btn','Layout');await expect('Numbers on this screen');
    // more than 2 big numbers: Pace, Cadence and Calories join Time and Steps up top
    for(const n of ['Pace','Cadence','Calories'])await p.evaluate(n=>{const row=[...document.querySelectorAll('div')].find(d=>d.children.length===4&&d.firstElementChild.textContent.endsWith(n));row.querySelector('button').click();},n);
    await W(300);
    const bigs=await p.evaluate(()=>[...document.querySelectorAll('[data-rec-big]')].map(e=>e.dataset.recBig));
    for(const n of ['Time','Steps','Pace','Cadence','Calories'])if(!bigs.some(t=>t.toLowerCase()===n.toLowerCase()))throw new Error(n+' is not big: '+bigs.join(', '));
    await click('.x-btn','Done');
    // jogging: a big amber pill with how long and how far; cadence well above average turns amber after 3 s
    await p.evaluate(()=>{window.LMNative.walk._spm=150;});await W(1500);await expect('Jogged');
    const amber=await p.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue('--amber').trim());
    const cadCol=()=>p.evaluate(()=>getComputedStyle(document.querySelector('[data-rec-big=cadence] .x-num')).color);
    const toRgb=await p.evaluate(c=>{const d=document.createElement('i');d.style.color=c;document.body.appendChild(d);const r=getComputedStyle(d).color;d.remove();return r;},amber);
    const t0=await p.evaluate(()=>Date.now());await p.clock.setFixedTime(t0+4000);   // the test clock stands still: move it on 4 s
    await W(1500);if(await cadCol()!==toRgb)throw new Error('cadence should turn amber after 3 s above average: '+await cadCol()+' vs '+toRgb);
    await p.evaluate(()=>{window.LMNative.walk._spm=100;});await W(1500);
    if(await has('Jogged'))throw new Error('jog pill should go when you slow down');if(await cadCol()===toRgb)throw new Error('cadence should go back to normal');
    // the step sensor goes quiet (0) while GPS still has you walking: cadence comes from the step count, not 0
    await p.evaluate(()=>{const w=window.LMNative.walk,L=w._pts[w._pts.length-1],now=Date.now();w._spm=0;   // fresh fixes: walking ~4.7 km/h, 110 steps a minute
      for(let k=15;k>=0;k--)w._pts.push([now-k*2000,L[1]+(15-k+1)*0.0000235,L[2],L[3],5,null,L[6]+Math.round((15-k+1)*3.67)]);});await W(1500);
    {const c=await p.evaluate(()=>document.querySelector('[data-rec-big=cadence] .x-num').textContent.trim());if(c==='0')throw new Error('cadence stuck at 0 while walking');}
    await p.evaluate(()=>{window.LMNative.walk._spm=100;});await W(500);
    // step sensors quiet or not allowed: steps from motion, marked ≈, and a button to allow the step sensor
    await p.evaluate(()=>{const w=window.LMNative.walk;w._src='motion';w._issue='permission';});await W(1500);
    await click('button','Allow step counting');await W(400);if(!(await p.evaluate(()=>window.__allowSteps)))throw new Error('Allow step counting did not ask');
    await p.evaluate(()=>{window.LMNative.walk._issue=null;});await W(1500);await expect('Steps from motion');await expect('counted from motion');
    await p.evaluate(()=>{window.LMNative.walk._src='counter';});await W(1200);
    // leave the recording screen: a pinned pill at the top shows it's still going, and opens it again
    await p.evaluate(()=>{const b=[...document.querySelectorAll('button')].find(b=>/^Back/.test(b.getAttribute('aria-label')||''));b&&b.click();});await W(1200);
    const pill=await p.$('[aria-label="Open the recording"]');if(!pill)throw new Error('no recording pill after leaving the recording screen');
    if(!/Walk/.test(await pill.innerText())||!/km/.test(await pill.innerText()))throw new Error('recording pill text: '+await pill.innerText());
    await pill.click();await W(600);if(await p.$('[aria-label="Open the recording"]'))throw new Error('pill should hide on the recording screen');await expect('Started');
    await click('button','Pause');await expect('Resume');await click('button','Resume');
    await click('button','Finish');await expect('Finish this walk');await click('.x-btn','Save walk');await W(900);
    await expect('Pace for each km');await expect('Moving time');
    if(!(await p.evaluate(()=>/25\s*mins\s*0\s*secs/.test(document.body.innerText))))throw new Error('moving time should read 25 mins 0 secs');
    // the map moves with a finger and glides back 2 s later
    const tile0=await p.evaluate(()=>{const i=document.querySelector('[aria-label="Route map"]').parentElement.querySelector('img');return i?i.style.left:null;});
    const box=await (await p.$('[aria-label="Route map"]')).boundingBox();
    await p.mouse.move(box.x+box.width/2,box.y+box.height/2);await p.mouse.down();await p.mouse.move(box.x+box.width/2+80,box.y+box.height/2+30,{steps:5});await p.mouse.up();await W(200);
    const tile1=await p.evaluate(()=>document.querySelector('[aria-label="Route map"]').parentElement.querySelector('img').style.left);
    if(tile1===tile0)throw new Error('map did not move when dragged');
    await W(2900);const tile2=await p.evaluate(()=>document.querySelector('[aria-label="Route map"]').parentElement.querySelector('img').style.left);
    if(tile2!==tile0)throw new Error('map should glide back after 2 s: '+tile0+' → '+tile2);
    // a point thrown across the road by a tall building is dropped (and so is the distance it added)
    const cl=await p.evaluate(()=>{const a=[];for(let i=0;i<20;i++)a.push([i*2,19.0645+i*0.00005,72.8655+i*0.00003]);
      a.splice(10,0,[19,19.0630,72.8670]);const r=window.__lmCleanTrack(a);return{n:r.pts.length,cut:Math.round(r.cut)};});
    if(cl.n!==20||cl.cut<200)throw new Error('spike not removed: '+JSON.stringify(cl));
    // GPS that barely moved while the steps show real walking (indoors): the distance comes from the steps instead
    const wk=await p.evaluate(()=>{const s=new Date('2026-11-09T14:00:00+05:30').getTime(),pts=[];for(let i=0;i<40;i++)pts.push([i*30,19.0645+i*0.000002,72.8655]);
      window.__lmTest.addTrack({id:'weak1',start:s,end:s+40*60000,kind:'walk',movingMs:40*60000,steps:4125,distGps:370,pts});return true;});
    await W(300);
    const w=await p.evaluate(()=>{const mv=window.__lmTest.moveDay('2026-11-09'),x=mv.walks.find(w=>w.trackId==='weak1');window.__lmTest.dropTrack('weak1');return x&&{km:x.km,weak:x.gpsWeak,h14:mv.hours&&mv.hours[14]};});
    if(!w||!w.weak||w.km<2.5)throw new Error('weak-GPS walk should use steps × step length: '+JSON.stringify(w));
    // and its steps show in the hour it ran, even if Health Connect hasn't got them yet
    if(!(w.h14>=4000))throw new Error('2–2:40 PM walk should put ~4,100 steps in the 2 PM bar: '+JSON.stringify(w));await expect('Elevation');
    await expect('How it went over time');await expect('Cadence');await expect('Stride');await expect('higher = faster');
    if(!(await p.$('[aria-label="Route map"]')))throw new Error('no route map');
    await p.click('[aria-label="Back"]');await W();});
  await step('Record a hike',async()=>{
    await p.evaluate(()=>{const W=window.LMNative.walk;W.start=async function(kind){this._st='recording';this._kind=kind;this._t0=Date.now()-60*60000;const c=[18.366,73.747];
      this._pts=Array.from({length:300},(_,i)=>{const up=i<150?i:300-i;return[this._t0+i*12000,c[0]+i*0.00006,c[1]+0.0003*Math.sin(i/20),700+up*3+Math.random()*8,6,660+up*3];});return this.status(0);};
      const st=W.status;W.status=async function(from){const r=await st.call(this,from);r.kind=this._kind;r.gainM=120;r.alt=900;r.distanceM=4000;return r;};});
    await tab(0);await p.click('[aria-label="Steps and walks"]');await W(400);await click('.x-btn','Start a hike');await W(4800);
    await expect('Climb');await expect('m above sea');if(await p.$('nav.dock-pill'))throw new Error('island should be hidden');await click('button','Finish');await expect('Finish this hike');await click('.x-btn','Save hike');await W(900);
    await expect('Elevation gain');await expect('Highest point');await expect('Each km: pace and climb');await expect('Speed');
    await p.click('[aria-label="Back"]');await W();});
  await step('Burned counts steps',async()=>{await tab(2);if(!(await has('from steps'))&&!(await has('gym + steps')))throw new Error('Burned does not include steps');});
  await step('Water reminder tapped: pick an amount',async()=>{await tab(0);await p.evaluate(()=>window.__lmWaterPick());await W(700);
    await expect('How much did you drink');await p.click('[aria-label="500 ml"]');await W(3600);
    if(await has('How much did you drink'))throw new Error('picker did not close');});
  await step('Water logged from notification buttons',async()=>{
    await p.evaluate(()=>{window.LMNative.takeLoggedWater=async()=>{const r=window.__q||[];window.__q=[];return r;};window.__q=[{ts:Date.now()-60000,ml:250}];window.dispatchEvent(new Event('lm-resume'));});
    await W(900);await expect('logged from the reminder');});
  await step('Google Drive backup',async()=>{
    await p.evaluate(()=>{const st={email:null,freq:'daily',wifiOnly:true,last:0,lastSize:0,error:null};window.__drive=[];
      window.LMNative.drive={available:()=>true,status:async()=>({...st}),
        signIn:async()=>{st.email='tester@gmail.com';return{...st};},signOut:async()=>{st.email=null;return{...st};},
        schedule:async(f,w)=>{st.freq=f;st.wifiOnly=w;window.__drive.push('schedule:'+f+':'+w);return{...st};},
        backupNow:async()=>{st.last=Date.now();st.lastSize=48000;window.__drive.push('backup');return{...st};},
        list:async()=>[{id:'b1',name:'fitness-backup-1.json',size:'48000',createdTime:new Date().toISOString()}],
        restore:async id=>{window.__drive.push('restore:'+id);return{files:9};}};});
    await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Data & backup');await W();
    await click('.x-btn','Sign in with Google');await W(600);await expect('tester@gmail.com');await expect('KB');
    await click('.x-seg button','Weekly');await click('.x-seg button','Wi-Fi or mobile data');
    await click('.x-btn','Restore from Drive');await W(300);await expect('latest');
    const log=await p.evaluate(()=>window.__drive.join(','));if(!/backup/.test(log)||!/schedule:weekly:false/.test(log))throw new Error('drive calls: '+log);
    await click('.x-btn','Close');await p.click('[aria-label="Back to settings"]');await W();});
  await step('Move reminders',async()=>{await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Reminders');
    // the On/Off inside the Movement section of the one Reminders page
    const seg=lab=>p.evaluate(lab=>{const hd=[...document.querySelectorAll('h2')].find(e=>e.textContent==='Movement');
      const card=hd.closest('div').nextElementSibling;[...card.querySelectorAll('.x-seg button')].find(x=>x.textContent.trim()===lab).click();},lab);
    await seg('On');await W(300);await expect('Remind me after sitting for');await click('.x-chip','45 min');await seg('Off');await W(300);
    await p.click('[aria-label="Back to settings"]');await W();});
  await step('Water in sips, gulps & glasses',async()=>{await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Reminders');
    await click('button','Sips, gulps & glasses');await expect('Your gulp is about');await p.click('[aria-label="Back to settings"]');await W();
    await tab(0);await expect('+1 glass');            // favourites: the sample history is all glassesconst before=await p.evaluate(()=>document.body.innerText.match(/([\d.]+)\s*(ml|L)\b/)?.[0]);
    await click('.x-btn','+1 glass');await W(600);
    await tab(4);await click('.x-item','Reminders');await click('button','ml');await p.click('[aria-label="Back to settings"]');await W();await tab(0);await expect('+250');});
  await step('Logging reminders',async()=>{await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Reminders');
    for(const h of ['Logging','Coach','Movement','Water'])if(!(await p.$(`h2:text-is("${h}")`)))throw new Error('Reminders page should have a '+h+' section');await expect('Next ·');await click('.x-item','Snack');
    // each reminder has its own timing: move Dinner's to 1 h after, the coach's to 2 h before
    const dinnerAt=async()=>p.evaluate(()=>{const r=[...document.querySelectorAll('button.x-item')].find(e=>/\bDinner\b/.test(e.innerText)&&/Usually/.test(e.innerText));return r?r.innerText:'';});
    await p.click('[aria-label="Dinner: 1 h after your usual time"]');await W(300);
    const row=await dinnerAt();if(!/Reminder (today|tomorrow) 9:\d\d PM/.test(row))throw new Error('Dinner reminder should move to ~1 h after its usual time: '+row.replace(/\n/g,' '));
    await p.click('[aria-label="Coach’s plan: 2 h before each usual meal"]');await W(300);
    if(!await p.$('[aria-label="Coach’s plan: 2 h before each usual meal"].on'))throw new Error('coach timing chip did not stick');
    if(await p.$('[aria-label="Brisk walk: 2 h before your usual walk"]'))throw new Error('walk chips should be their own');
    await p.click('[aria-label="Back to settings"]');await W();});
  await step('Burned card and In & out page',async()=>{await tab(0);await expect('Burned');await expect('Resting');await expect('Digesting');
    await p.click('[aria-label^="Calories burned"]');await W(600);await expect('In & out');await expect('Eating target');
    await click('.x-seg button','Day');await expect('Mifflin');await expect(' kg (');await expect('Most calories');await expect('Breakfast');await expect('From');
    for(const v of ['Month','Qtr','Year','Day','Week'])await click('.x-seg button',v);
    await expect('a day');await expect('Resting burn');await expect('healthy deficit');
    const pairs=await p.evaluate(()=>document.querySelectorAll('svg[aria-label="Calories eaten and burned"] g').length);if(pairs!==7)throw new Error('week should have 7 pairs, got '+pairs);
    await p.click('[aria-label="Back"]');await W();await expect('Fuel');});
  await step('Widget snapshot',async()=>{
    const s=await p.evaluate(()=>window.__lmWidgetSnap());
    if(!s||s.today.date!=='2026-11-09')throw new Error('snapshot is not for today: '+(s&&s.today&&s.today.date));
    if(Object.keys(s.days).length!==14)throw new Error('want 14 days, got '+Object.keys(s.days).length);
    if(s.today.meals.length!==4||s.today.meals[0][0]!=='Breakfast')throw new Error('meals: '+JSON.stringify(s.today.meals));
    if(!(s.today.eat>0)||!(s.today.steps>0)||!(s.restDay>1000)||s.today.hours.length!==24)throw new Error('numbers missing: '+JSON.stringify({eat:s.today.eat,steps:s.today.steps,rest:s.restDay}));
    const d=s.days['2026-11-08'];if(!(d.e>0&&d.o>d.r&&d.r>1000))throw new Error('past day incomplete: '+JSON.stringify(d));});
  await step('Island changes with the screen',async()=>{
    const fab=()=>p.evaluate(()=>{const b=document.querySelector('.dock-add');return b?b.getAttribute('aria-label'):null;});
    await tab(0);if(await fab()!=='Log something')throw new Error('home + should open the Log menu, got '+await fab());
    await tab(1);if(await fab()!=='Log food')throw new Error('Food + should log food, got '+await fab());
    await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await expect('Log food');if(!(await p.$('.x-sheet textarea')))throw new Error('+ on Food should open the food sheet');await close();
    await tab(3);if(await fab()!=='Log weight')throw new Error('Progress + should log weight, got '+await fab());
    await tab(4);if(await fab()!==null)throw new Error('Settings should have no +');
    await tab(0);await p.click('[aria-label^="Calories burned"]');await W(600);
    if(await p.$('nav.dock-pill'))throw new Error('tabs should give way to the page’s own buttons');
    const acts=await p.evaluate(()=>[...document.querySelectorAll('.dock-pill.bar button')].map(b=>b.getAttribute('aria-label')).join('|'));
    if(acts!=='Back|Earlier|This week|Later')throw new Error('In & out island: '+acts);
    if(!(await p.evaluate(()=>document.querySelector('.dock-pill.bar [aria-label=Later]').disabled)))throw new Error('Later should be off on this week');
    await p.click('.dock-pill.bar [aria-label=Earlier]');await W();await expect('2 Nov');
    await p.click('.dock-pill.bar [aria-label="This week"]');await W();await expect('9 Nov');
    await p.click('.dock-pill.bar [aria-label=Back]');await W();await expect('Fuel');
    if(!(await p.$('nav.dock-pill')))throw new Error('tabs should be back on Home');});
  await step('Coach plans tomorrow’s reminders',async()=>{
    await p.evaluate(()=>{window.__planned=null;window.LMNative.planLogs=async items=>{window.__planned=items;return{ok:true,count:items.length,next:items[0]||null};};});
    await tab(0);await p.evaluate(()=>window.__lmLogOpen('coach'));await W(2500);await expect('Reminders for');await expect('2 boiled eggs');
    const items=await p.evaluate(()=>window.__planned||[]);const tags=items.filter(x=>x.kind==='nudge').map(x=>x.tag);
    for(const t of ['2026-11-10-Breakfast','2026-11-10-Dinner','2026-11-10-keep0','2026-11-10-walkPM'])if(!tags.includes(t))throw new Error('missing reminder '+t+'; got '+tags.join(', '));
    const b=items.find(x=>x.tag==='2026-11-10-Breakfast');if(!/boiled eggs/.test(b.body)||b.open!=='home'||b.icon!=='meal')throw new Error('breakfast tip is wrong: '+JSON.stringify(b));
    const bt=await p.evaluate(at=>{const d=new Date(at);return{h:d.getHours(),m:d.getMinutes(),s:d.toTimeString()};},b.at);if(!(bt.h===7&&bt.m===10))throw new Error('breakfast tip should be 2 h (set in the reminders step) before the usual 9:10, at 7:10; got '+bt.s);
    // "Dinner by 8 PM": the tip comes 2 h before 8 PM (or earlier) and goes stale at 8:15 PM
    const dn=items.find(x=>x.tag==='2026-11-10-Dinner');
    const dt=await p.evaluate(([a,u])=>{const f=x=>{const d=new Date(x);return d.getHours()*60+d.getMinutes();};return{at:f(a),until:u?f(u):null};},[dn.at,dn.until]);
    if(dt.at>18*60||dt.until!==20*60+15)throw new Error('dinner tip timing is wrong: '+JSON.stringify(dt));
    const tm=await p.evaluate(()=>['Eat by 8:30 PM: soup','before 1 pm','at 7.45am','by 8 P.M.','around 13:00 pm','Dinner at home'].map(window.__lmTipMin));
    if(JSON.stringify(tm)!==JSON.stringify([1230,780,465,1200,null,null]))throw new Error('tip times: '+JSON.stringify(tm));
    const wp=items.find(x=>x.tag==='2026-11-10-walkPM');
    if(!/after lunch/.test(wp.title))throw new Error('second walk should be the after-lunch walk: '+wp.title);
    const wt=await p.evaluate(at=>new Date(at).getHours(),wp.at);if(wt<11||wt>=15)throw new Error('after-lunch walk nudge should come around lunch, got hour '+wt);
    // a lunch tip and a lunch "keep it going" at the same time: only the tip
    await p.evaluate(()=>{window.__lunchTip='Add 150 g dahi to the chapati and sabji.';window.__planned=null;});
    const tc=await p.evaluate(()=>Date.now());await p.clock.setFixedTime(tc+60000);   // a new review gets a new time, which re-plans
    await p.click('[aria-label="Get a fresh review"]');await W(2800);
    const t2=(await p.evaluate(()=>window.__planned||[])).filter(x=>x.kind==='nudge').map(x=>x.tag);await p.evaluate(()=>{window.__lunchTip=null;});
    if(!t2.includes('2026-11-10-Lunch')||t2.includes('2026-11-10-keep0'))throw new Error('want the lunch tip without the habit nudge: '+t2.join(', ')+'');
    await p.click('[aria-label="Back"]');await W(600);
  });
  await step('Sore throat: the coach is told to suggest warm food',async()=>{
    await p.evaluate(()=>{window.__aiBodies=[];window.__lmLogSymptom('throat');window.__lmLogOpen('coach');});await W(800);
    await p.click('[aria-label="Get a fresh review"]');await W(2500);
    const b=await p.evaluate(()=>(window.__aiBodies||[]).filter(x=>/reviewing one whole day/.test(x)).pop()||'');
    if(!/ILLNESS FOOD RULE \(sore throat/.test(b)||!/no dahi/.test(b))throw new Error('coach prompt has no warm-food rule');
    await p.evaluate(()=>window.__lmLogSymptom('throat',true));await p.click('.dock-pill.bar [aria-label=Back]');await W();});
  await step('Due reminders show as pills',async()=>{try{
    // late evening, dinner not logged: a "Log dinner" pill and a bedtime pill, even with no notification tapped
    await p.clock.setFixedTime(new Date('2026-11-09T23:05:00+05:30'));await p.evaluate(()=>{try{localStorage.removeItem('lm-pill-hide')}catch(_){}});
    await tab(1);await tab(0);await W(800);
    const pills=await p.evaluate(()=>[...document.querySelectorAll('.top-pill')].map(e=>e.innerText));
    if(!pills.some(t=>/Log dinner/.test(t)))throw new Error('no Log dinner pill: '+pills.join(' | '));
    if(!pills.some(t=>/Bedtime passed|Lights out/.test(t)))throw new Error('no bedtime pill: '+pills.join(' | '));
    await click('.top-pill button','Bedtime passed');await expect('Start winding down');
    await click('.pill-card button','Got it');await W(400);
    if(await p.evaluate(()=>[...document.querySelectorAll('.top-pill')].some(e=>/Bedtime passed/.test(e.innerText))))throw new Error('bedtime pill should go after Got it');
    await click('.top-pill button','Log dinner');await expect('Write it the way you');await close();
    }finally{await p.clock.setFixedTime(new Date('2026-11-09T20:30:00+05:30'));}});
  await step('Reminder tapped: opens the right log',async()=>{await p.evaluate(()=>window.__lmLogOpen('sleep'));await W(600);await expect('How did you sleep?');await close();
    await p.evaluate(()=>window.__lmLogOpen('Lunch'));await W(600);
    const on=await p.evaluate(()=>[...document.querySelectorAll('.x-sheet .x-chip.on,.x-sheet [aria-pressed=true],.x-sheet .on')].map(e=>e.textContent).join('|'));
    if(!/Lunch/.test(on))throw new Error('Lunch not picked: '+on);await close();});
  await step('Log food with AI',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();
    // logged straight away; the macros follow when the AI answers
    await p.evaluate(()=>{window.__slowAI=true;});
    await click('.x-sheet button','Food');await p.fill('.x-sheet textarea','2 boiled eggs');await click('.x-sheet-head button','Log it');await W(500);
    if(await p.$('.x-sheet textarea'))throw new Error('the sheet should close at once, not wait for the AI');
    await tab(1);await expect('2 boiled eggs');await expect('Working out macros');
    await p.evaluate(()=>{window.__slowAI=false;});await W(1500);await expect('140 kcal');});
  await step('AI reply in words is handled',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();
    await click('.x-sheet button','Food');await p.fill('.x-sheet textarea','1 plate cabbage rice');await click('.x-sheet-head button','Log it');await W(1200);
    await tab(1);await expect('420 kcal');if(!(await p.evaluate(()=>window.__jsonMode)))throw new Error('Gemini was not asked for JSON');});
  await step('Same with Claude',async()=>{await p.evaluate(()=>localStorage.setItem('lm-ai-provider','claude'));await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();
    await click('.x-sheet button','Food');await p.fill('.x-sheet textarea','2 bowls cabbage rice');await click('.x-sheet-head button','Log it');await W(1200);
    await p.evaluate(()=>localStorage.setItem('lm-ai-provider','gemini'));await tab(1);await expect('2 bowls cabbage rice');
    if(!(await p.evaluate(()=>window.__jsonMode)))throw new Error('Claude was not told to reply in JSON');});
  await step('Water',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Water');
    await click('.x-sheet button','Glass');await expect('250 ml');
    // edit it: one step more and an hour earlier
    await p.click('.x-sheet [aria-label^="Edit 250 ml"]');await W();await expect('Edit this drink');
    await p.click('.x-sheet [aria-label="Edited drink: 50 ml more"]');await click('.x-sheet button','1 h ago');await click('.x-sheet button','Save');await W();
    const row=await p.evaluate(()=>[...document.querySelectorAll('.x-sheet .x-item')].map(e=>e.innerText).find(t=>/300 ml/.test(t)&&/✎/.test(t))||'');
    if(!/7:30\s*PM/i.test(row))throw new Error('edited drink should be 300 ml at 7:30 PM, got: '+row.replace(/\n/g,' '));
    await close();});
  await step('Walk',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Add a walk');
    await expect('Start + duration');await p.fill('.x-sheet input[inputmode=numeric]','3500');await click('.x-sheet-foot button','Save walk');
    if(await p.$('.x-sheet'))throw new Error('walk panel did not close after saving');});
  await step('Sleep',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Sleep');
    await click('.x-sheet button','Good');await click('.x-sheet-foot button','Save sleep');});
  await step('Weight',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Weight');
    await p.fill('.x-sheet input','78.4');await click('.x-sheet-foot button','Save');});
  await step('Feeling',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Feeling');
    await click('.x-sheet .x-chip','Gas');await close();});
  await step('Coach',async()=>{await tab(1);
    // the coach opens full screen from anywhere; it shows the loading indicator while it thinks
    await p.click('button.x-card[aria-label="Ask the coach"], button.x-card[aria-label="Open the coach’s review"]');await W(700);
    await expect('Coach');await expect('🍽️ Food');await expect('Protein was low at lunch.');await expect('Add dal at dinner.');
    await p.evaluate(()=>{window.__slowAI=true;});await p.click('[aria-label="Get a fresh review"]');await W(150);
    if(!await p.$('svg[role=progressbar]'))throw new Error('no loading indicator while the coach thinks');await expect('Reading');
    await p.evaluate(()=>{window.__slowAI=false;});await W(1500);await expect('Protein was low at lunch.');
    await p.click('[aria-label="Back"]');await W(600);await expect('Breakfast');});
  await step('Busy free AI: waits, then the lighter model',async()=>{
    await p.evaluate(()=>{window.__busyModel='gemini-flash-latest';window.__aiCalls=[];window.__lmLogOpen('coach');});await W(800);
    await p.click('[aria-label="Get a fresh review"]');await p.waitForTimeout(7500);
    const c=await p.evaluate(()=>window.__aiCalls);await p.evaluate(()=>{window.__busyModel=null;});
    if(c.filter(u=>/gemini-flash-latest/.test(u)).length<3||!c.some(u=>/flash-lite/.test(u)))throw new Error('calls: '+c.join(' | '));
    if(await p.evaluate(()=>/Couldn’t get the coach/.test(document.body.innerText)))throw new Error('the review should have come through');
    await p.click('.dock-pill.bar [aria-label=Back]');await W();});
  await step('Free AI used by default',async()=>{const c=await p.evaluate(()=>window.__aiCalls);if(!c.some(u=>/generativelanguage/.test(u)))throw new Error('AI did not go to Gemini: '+c.join(','));});
  await step('Colour picker',async()=>{await tab(4);await click('.x-item','Look & feel');await expect('Cloth-like scrolling');await expect('Springiness');await click('.x-btn','Make your own colour');for(const m of ['RGB','HSV','Hex'])await click('.x-seg button',m);await close();});
  await step('Update check',async()=>{await p.evaluate(()=>{window.LMNative.installSource=async()=>({installedAt:Date.parse('2026-11-01T10:00:00+05:30'),updatedAt:Date.parse('2026-11-09T01:58:00+05:30'),
      installer:'com.google.android.packageinstaller',initiator:'dev.imranr.obtainium',initiatorName:'Obtainium'});});
    await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','About & updates');await click('.x-btn','Check for updates');await W(500);
    await expect('Last updated:');await expect('Obtainium (dev.imranr.obtainium)');await expect('Android’s installer');
    await expect('Version 99.1');await expect('Faster logging');await expect('Download & install');await expect('Previous versions');await expect('1.12');
    // the download bar's wave keeps flowing while it downloads
    await p.evaluate(()=>{window.LMNative.downloadUpdate=async()=>'native';window.LMNative.updateStatus=async()=>({state:'downloading',done:40,total:100});});
    await click('.x-btn','Download & install');await W(1200);await expect('Downloading…');
    const wave=()=>p.evaluate(()=>{const x=[...document.querySelectorAll('.x-card svg[data-flow] path')].find(e=>e.closest('.x-card').innerText.includes('Downloading'));return x&&x.getAttribute('d');});
    const w1=await wave();await W(300);const w2=await wave();if(!w1||w1===w2)throw new Error('the download wave is not moving '+String(w1).slice(0,80));
    await p.click('[aria-label="Back to settings"]');});
  await step('BMI',async()=>{await tab(3);await W(400);await expect('BMI · ');await expect('Healthy weight for your height');
    await tab(0);await W(400);if(!(await p.evaluate(()=>[...document.querySelectorAll('.x-stat')].some(e=>/Weight/.test(e.textContent)&&/BMI \d/.test(e.textContent))))){
      // the weight tile shows BMI only on a day with a weigh-in
      if(await p.evaluate(()=>[...document.querySelectorAll('.x-stat')].some(e=>/Weight\d/.test(e.textContent))))throw new Error('weight tile has no BMI');}});
  await step('Batched step counts don’t spike stride or cadence',async()=>{
    // 15 min at 1.3 m/s and 110 steps a minute, a fix every 2 s; the phone holds the count back from 7 to 9 min
    const r=await p.evaluate(()=>{const pts=[];for(let t=0;t<=900;t+=2){const real=Math.round(t/60*110);
        pts.push([t,18.52+t*1.3/111000,73.85,560,5,(t>=420&&t<540)?Math.round(420/60*110):real]);}
      pts.forEach(q=>{q[4]=q[5];q.length=5;});
      const tr=window.__lmTrackStats(pts).trend;return{cad:tr.map(x=>x.cad).filter(x=>x!=null),st:tr.map(x=>x.stride).filter(x=>x!=null)};});
    if(Math.min(...r.cad)<95||Math.max(...r.cad)>125)throw new Error('cadence '+r.cad.join(','));
    if(Math.max(...r.st)>85||Math.min(...r.st)<60)throw new Error('stride '+r.st.join(','));});
  await step('Tabs grow out of what you tapped',async()=>{await tab(0);await W(300);await p.click('.x-card.pri.sa');await W(900);await expect('Today');
    if(await p.evaluate(()=>[...document.querySelectorAll('*')].some(e=>e.style&&e.style.viewTransitionName==='lm-hero')))throw new Error('a hero name was left behind');
    await tab(0);await W(600);});
  await step('Race: look it up, save it, and the app plans around it',async()=>{
    await p.evaluate(()=>{const f0=window.fetch;window.__geoN=0;
      window.fetch=async(u,o)=>{u=String(u);const body=o&&o.body?String(o.body):'';
        if(/nominatim/.test(u)){window.__geoN++;const k=window.__geoN;return{ok:true,status:200,json:async()=>[{lat:String(19.20+k*0.01),lon:String(72.97+k*0.005)}]};}
        if(/routing\.openstreetmap/.test(u))return{ok:true,status:200,json:async()=>({routes:[{distance:21300,geometry:{coordinates:[[72.975,19.21],[72.978,19.215],[72.98,19.22],[72.985,19.23]]}}]})};
        if(/Look up this running event/.test(body)){window.__raceSearch=/google_search/.test(body);
          const t=JSON.stringify({found:true,name:'Thane Lakes Half Marathon 2026',date:'2026-11-10',city:'Thane',place:'Upvan Lake, Thane',
            events:[{label:'Half Marathon',km:21.1,start:'05:45',cutoff_min:210},{label:'10K Run',km:10,start:'06:30',cutoff_min:null}],
            course:'Starts at Upvan Lake, out along Pokhran Road 2 and back.',landmarks:['Upvan Lake','Pokhran Road 2','Hiranandani Estate'],url:'https://example.com/race',note:null});
          return{ok:true,status:200,clone(){return this;},text:async()=>t,json:async()=>({candidates:[{content:{parts:[{text:t}]}}],usageMetadata:{promptTokenCount:10,candidatesTokenCount:10}})};}
        return f0(u,o);};});
    await p.evaluate(()=>{window.__planned=null;window.LMNative.planLogs=async items=>{window.__planned=items;return{ok:true,count:items.length,next:items[0]||null};};});
    await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Profile');await W(400);
    await p.click('[aria-label="Add a race"]');await W(300);
    await p.fill('[data-race-edit] input.x-field','thane lakes half');await click('[data-race-edit] button','Look it up');
    for(let i=0;i<40&&await has('Finding the route');i++)await W(300);
    for(let i=0;i<20&&!(await has('Which one are you doing'));i++)await W(300);
    if(!(await p.evaluate(()=>window.__raceSearch)))throw new Error('the race lookup did not search the web');
    await click('[data-race-edit] .x-chip','Half Marathon');await W(200);
    const dr=await p.evaluate(()=>[...document.querySelectorAll('[data-race-edit] input')].map(i=>i.value));
    if(!dr.includes('2026-11-10')||!dr.includes('05:45')||!dr.includes('21.1')||!dr.includes('3')||!dr.includes('30'))throw new Error('draft not filled: '+dr.join(' | '));
    if(!(await p.$('[data-race-edit] [aria-label="Route map"]')))throw new Error('no course map');
    await click('[data-race-edit] button','Save race');await W(500);
    await expect('Half marathon');await expect('Cutoff 3 hrs 30 mins');await expect('Tomorrow');await expect('Race tomorrow: rest');
    // the race is tomorrow (test clock: 9 Nov): reminders, workouts, targets and the coach follow
    await tab(0);await W(1800);await expect('race tomorrow');
    // at 5 PM the evening's kit check and early night are still to come
    const tNow=await p.evaluate(()=>Date.now());await p.clock.setFixedTime(new Date('2026-11-09T17:00:00+05:30'));
    await p.evaluate(()=>{window.__planned=null;window.dispatchEvent(new Event('lm-resume'));});await W(800);
    const items=await p.evaluate(()=>window.__planned||[]);await p.clock.setFixedTime(tNow);const tags=items.filter(x=>x.kind==='nudge').map(x=>x.tag);
    for(const t of ['2026-11-09-racekit','2026-11-09-racesleep','2026-11-10-racewake','2026-11-10-racewarm'])if(!tags.includes(t))throw new Error('missing race reminder '+t+'; got '+tags.join(', '));
    if(tags.some(t=>/walkAM|walkPM/.test(t)))throw new Error('brisk-walk nudges should pause around the race: '+tags.join(', '));
    const wake=items.find(x=>x.tag==='2026-11-10-racewake');const wt=await p.evaluate(a=>{const d=new Date(a);return d.getHours()*60+d.getMinutes();},wake.at);
    if(wt!==2*60+45)throw new Error('race-morning reminder should be 3 h before the 5:45 start, got '+wt);
    if(await has('/ 200 g'))throw new Error('carbs target should go up on the day before the race');
    await tab(2);await W(400);await click('.x-seg button','Today');await W(300);await expect('Rest: race tomorrow');
    await tab(0);await p.evaluate(()=>window.__lmLogOpen('coach'));await W(1500);
    const tc=await p.evaluate(()=>Date.now());await p.clock.setFixedTime(tc+60000);
    await p.click('[aria-label="Get a fresh review"]').catch(()=>{});await W(2800);
    if(!(await p.evaluate(()=>(window.__aiBodies||[]).some(b=>/race phase \\"eve\\"/.test(b)&&/Thane Lakes Half Marathon/.test(b)))))throw new Error('the coach was not told about the race');
    await p.click('[aria-label="Back"]').catch(()=>{});await W(500);
    // remove it again so the rest of the checks see a normal day
    await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Profile');await W(300);
    await click('[data-race] button','Remove');await W(300);await expect('Add a race');
    await p.click('[aria-label="Back to settings"]');await W();});
  await step('Saved to storage',async()=>{await W(1200);const ok=await p.evaluate(()=>Object.keys(localStorage).some(k=>k.startsWith('fs:')));if(!ok)throw new Error('nothing was saved');});

  await b.close();
  console.log(steps.join('\n'));
  if(errors.length)console.log('\nErrors:\n'+errors.join('\n'));
  const failed=steps.filter(s=>s.startsWith('✗')).length+errors.length;
  console.log(failed?`\n${failed} problem(s) — tonight's build will not be published`:'\nAll checks passed');
  process.exit(failed?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
