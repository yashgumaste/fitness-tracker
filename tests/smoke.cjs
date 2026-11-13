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
      if(/api\.github\.com/.test(String(u)))return{ok:true,status:200,json:async()=>({tag_name:'v99.1',html_url:'https://github.com/x/releases/v99.1',body:'**What\'s new**\n- Faster logging\n- Brighter charts',assets:[{name:'FitnessTracker-99.1.apk',browser_download_url:'https://github.com/x/FitnessTracker-99.1.apk'}]})};
      const body=o&&o.body?String(o.body):'';gem=/generativelanguage/.test(String(u));window.__aiCalls.push(String(u));
      if(/Split each meal/.test(body))return reply('[]');
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
  await step('Food history',async()=>{await click('.x-seg button','History');await expect('kcal');await click('.x-seg button','Today');});
  await step('Fitness day',async()=>{await tab(2);await expect('Walks');await expect('Session notes');});
  for(const v of ['Day','Week','Month','Year'])
    await step('Fitness history '+v,async()=>{await click('.x-seg button','History');await click('.x-seg button',v);await expect('Workouts');});
  await step('Progress',async()=>{await tab(3);await expect('Trends');await expect('Body');});
  await step('Settings',async()=>{await tab(4);for(const t of ['Profile','Body','Goals','AI','Water reminders','Move reminders','Health Connect','Theme & colour','Data & backup','About & updates']){
      await click('.x-item',t);await W();if(!(await p.$('[aria-label="Back to settings"]')))throw new Error(t+' page did not open');
      await p.click('[aria-label="Back to settings"]');await W();}
    await click('.x-item','Goals');await expect('Daily goals');await expect('Goal weight');await p.click('[aria-label="Back to settings"]');});
  await step('Steps page',async()=>{await tab(0);await p.click('[aria-label="Steps and walks"]');await W(500);
    await expect('By the hour');await expect('Brisk morning walk');await expect('Evening stroll');await expect('Everyday movement');await expect('kcal burned by moving');
    for(const v of ['Week','Month','Year']){await click('.x-seg button',v);await expect('Average a day');}
    await click('.x-seg button','Day');await p.click('[aria-label="Earlier"]');await W();await expect('Back to today');
    await p.click('[aria-label="Back"]');await W();await expect('Timeline');});
  await step('Burned counts steps',async()=>{await tab(2);if(!(await has('from steps'))&&!(await has('gym + steps')))throw new Error('Burned does not include steps');});
  await step('Water reminder tapped: pick an amount',async()=>{await tab(0);await p.evaluate(()=>window.__lmWaterPick());await W(700);
    await expect('How much did you drink');await p.click('[aria-label="500 ml"]');await W(3600);
    if(await has('How much did you drink'))throw new Error('picker did not close');});
  await step('Water logged from notification buttons',async()=>{
    await p.evaluate(()=>{window.LMNative.takeLoggedWater=async()=>{const r=window.__q||[];window.__q=[];return r;};window.__q=[{ts:Date.now()-60000,ml:250}];window.dispatchEvent(new Event('lm-resume'));});
    await W(900);await expect('logged from the reminder');});
  await step('Move reminders',async()=>{await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','Move reminders');
    await click('.x-seg button','On');await expect('Remind me after sitting for');await click('.x-chip','45 min');await click('.x-seg button','Off');
    await p.click('[aria-label="Back to settings"]');await W();});
  await step('Log food with AI',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();
    await click('.x-sheet button','Food');await p.fill('.x-sheet textarea','2 boiled eggs');await click('.x-sheet-foot button','Log it');await W(800);
    await tab(1);await expect('2 boiled eggs');await expect('140 kcal');});
  await step('AI reply in words is handled',async()=>{await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();
    await click('.x-sheet button','Food');await p.fill('.x-sheet textarea','1 plate cabbage rice');await click('.x-sheet-foot button','Log it');await W(1200);
    await tab(1);await expect('420 kcal');if(!(await p.evaluate(()=>window.__jsonMode)))throw new Error('Gemini was not asked for JSON');});
  await step('Same with Claude',async()=>{await p.evaluate(()=>localStorage.setItem('lm-ai-provider','claude'));await tab(0);await p.evaluate(()=>document.querySelector('.dock-add').click());await W();
    await click('.x-sheet button','Food');await p.fill('.x-sheet textarea','2 bowls cabbage rice');await click('.x-sheet-foot button','Log it');await W(1200);
    await p.evaluate(()=>localStorage.setItem('lm-ai-provider','gemini'));await tab(1);await expect('2 bowls cabbage rice');
    if(!(await p.evaluate(()=>window.__jsonMode)))throw new Error('Claude was not told to reply in JSON');});
  await step('Water',async()=>{await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Water');
    await click('.x-sheet button','Glass');await expect('250 ml');await close();});
  await step('Walk',async()=>{await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Walk');
    await expect('Start + duration');await p.fill('.x-sheet input[inputmode=numeric]','3500');await click('.x-sheet-foot button','Save walk');
    if(await p.$('.x-sheet'))throw new Error('walk panel did not close after saving');});
  await step('Sleep',async()=>{await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Sleep');
    await click('.x-sheet button','Good');await click('.x-sheet-foot button','Save sleep');});
  await step('Weight',async()=>{await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Weight');
    await p.fill('.x-sheet input','78.4');await click('.x-sheet-foot button','Save');});
  await step('Feeling',async()=>{await p.evaluate(()=>document.querySelector('.dock-add').click());await W();await click('.x-sheet button','Feeling');
    await click('.x-sheet .x-chip','Gas');await close();});
  await step('Coach',async()=>{await tab(1);await click('.x-card','Ask the coach');await W(600);await expect('Coach');});
  await step('Free AI used by default',async()=>{const c=await p.evaluate(()=>window.__aiCalls);if(!c.some(u=>/generativelanguage/.test(u)))throw new Error('AI did not go to Gemini: '+c.join(','));});
  await step('Colour picker',async()=>{await tab(4);await click('.x-item','Theme & colour');await click('.x-btn','Make your own colour');for(const m of ['RGB','HSV','Hex'])await click('.x-seg button',m);await close();});
  await step('Update check',async()=>{await tab(4);await p.click('[aria-label="Back to settings"]').catch(()=>{});await W();await click('.x-item','About & updates');await click('.x-btn','Check for updates');await W(500);
    await expect('Version 99.1');await expect('Faster logging');await expect('Download & install');await expect('Previous versions');await expect('1.12');
    await p.click('[aria-label="Back to settings"]');});
  await step('Saved to storage',async()=>{await W(1200);const ok=await p.evaluate(()=>Object.keys(localStorage).some(k=>k.startsWith('fs:')));if(!ok)throw new Error('nothing was saved');});

  await b.close();
  console.log(steps.join('\n'));
  if(errors.length)console.log('\nErrors:\n'+errors.join('\n'));
  const failed=steps.filter(s=>s.startsWith('✗')).length+errors.length;
  console.log(failed?`\n${failed} problem(s) — tonight's build will not be published`:'\nAll checks passed');
  process.exit(failed?1:0);
})().catch(e=>{console.error(e);process.exit(1);});
